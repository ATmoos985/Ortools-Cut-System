package test.demo.apsmodule.generator.NewSolver.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Result;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.StationAssignment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 质量模式精修段：set-partition 微邻域迭代（残差导向列注入，笔记13 L12 系列）。
 *
 * <p>机制：把一个长度组的指令转成 (花型,整车配置) 列，逐块拆弱块（odd 优先，次 small）
 * + 共享需求键最多的捐赠块，对拆出的残差需求现场跑比例匹配列生成，小规模 set-partition
 * 精确重建（车数等式+废边≤拆除额，拆除块进池兜底），严格改善（组减，或组平 odd 减）
 * 才接受。B6 L12c 实证：生产公平管线解 47/3/13 → 46/1/12（追平人工 46/1/15），耗时 25s 级。
 *
 * <p>上下文感知选列的三条歧路已证伪：LP 对偶定价（L11，松弛结构性弱）、静态排序
 * （L9，跨宽度互补依赖全局上下文）、贪心构造（L6，尾部残差 NP 核）。
 * 邻域尺度是本质参数：全弱块/半解规模的邻域双零改善（L12 v1），微邻域才有效。
 *
 * <p>只在质量模式（cutting.quality=true）下由 InstructionConverter 调用；
 * 本类只产出候选指令，守恒+字典序验收由调用方（setPartitionRefinePass）负责。
 */
public final class SetPartitionRefiner {

    private static final Logger log = LoggerFactory.getLogger(SetPartitionRefiner.class);

    // 微邻域参数（B6 L12b/L12c 实证值）
    private static final int PARITY_DONORS = 3;
    // 每个子 MIP 的墙钟帽——最陡下降式每轮并行评估全部目标、墙钟=批内最慢，故子帽越小
    // 每轮越快。实测多数子解 <5s 即 OPTIMAL，帽只兜底少数难解；较串行(30/60s)大幅收窄。
    private static final long PARITY_BUDGET_MS = 20_000;
    private static final int MERGE_DONORS = 5;
    private static final long MERGE_BUDGET_MS = 40_000;
    // 最陡下降式的最大迭代数（每轮接受 1 个最优移动）；收敛或总预算先到即停。
    private static final int MAX_ITERATIONS = 40;
    // SPR 总墙钟预算（默认 5min）：硬上限，保证质量模式单组耗时可预期（UX：绝不失控长跑）。
    private static final long DEFAULT_SPR_BUDGET_MS = 300_000;
    private static final double ODD_WEIGHT = 0.02;
    private static final int SMALL_MAX_CARS = 5;

    // 残差形状枚举参数（B6 stratifiedShapes 同源）
    private static final int SHAPE_MAX_DISTINCT = 5;
    private static final int SHAPE_RAW_CAP = 6000;
    private static final int SHAPE_PER_BAND = 100;
    private static final int SHAPE_TOTAL_CAP = 400;
    private static final int MAX_MESSAGES_PER_WIDTH = 2;
    private static final int MAX_OPTIONS_PER_WIDTH = 20;
    private static final int CONFIGS_PER_SHAPE = 80;

    private SetPartitionRefiner() {
    }

    record SubproblemKey(String poolSignature,
                         String demandSignature,
                         int exactCars,
                         int wasteCap,
                         int totalWidth,
                         long timeLimitMs,
                         String warmStartSignature) {
    }

    static final class RefineStats {
        private final AtomicInteger iterations = new AtomicInteger();
        private final AtomicInteger targets = new AtomicInteger();
        private final AtomicInteger subproblemRequests = new AtomicInteger();
        private final AtomicInteger solverCalls = new AtomicInteger();
        private final AtomicInteger cacheHits = new AtomicInteger();
        private final AtomicInteger acceptedMoves = new AtomicInteger();
        private final AtomicInteger budgetStops = new AtomicInteger();

        int subproblemRequests() {
            return subproblemRequests.get();
        }

        int solverCalls() {
            return solverCalls.get();
        }

        int cacheHits() {
            return cacheHits.get();
        }
    }

    public static boolean isEnabled() {
        return SolverRuntimeProperties.getBoolean("cutting.spr.enabled", true);
    }

    /**
     * 劣候选提前弃修阈值：候选（LNS 后）组数落后同 groupKey 已见最好成绩超过该值时跳过精修。
     * 实测垃圾候选精修最多追回 9 组（t9est188 109→100 仍被淘汰）；当前 45 单案例中，
     * 最好候选已到 25 组，后续 34 组候选精修 5min 后仍只到 26 组，因此默认收紧到 8，
     * 先跳过明显落后的深度精修，避免质量模式长尾耗时。
     */
    public static int skipGapThreshold() {
        return SolverRuntimeProperties.getInt("cutting.spr.skipGapThreshold", 8);
    }

    /**
     * 每次 refine 的墙钟预算帽（毫秒），默认 0=不设帽（opt-in）。注意：实测赢家候选的
     * 精修需 7-10min（sixian 42 组、t9est188 66 组都出自长尾），设帽可能截掉最好成绩，
     * 仅在对耗时敏感的场景开启。
     */
    static long budgetMs() {
        return SolverRuntimeProperties.getLong("cutting.spr.budgetMs", DEFAULT_SPR_BUDGET_MS);
    }

    /**
     * 并行评估微邻域目标块（默认 ON）。最陡下降式：每轮并行评估全部目标（独立 SCIP 子解、
     * 只读同一快照），只应用单个最优移动，下一轮对更新后的解重估——保留串行的接受链效应
     * （质量≈串行），同时把每轮墙钟从 Σ 降到 max。取代旧批量并行（会丢接受链、质量降）。
     */
    static boolean parallelEnabled() {
        return SolverRuntimeProperties.getBoolean("cutting.spr.parallel", true);
    }

    static boolean cacheEnabled() {
        return SolverRuntimeProperties.getBoolean("cutting.spr.cache", true);
    }

    /**
     * 对一个长度组的指令做微邻域精修，返回候选新指令（不修改入参；未做验收）。
     * 结构不适用时返回 null：空组、混合 长度/表面/厚度、工位序号不可重放、块数<2。
     * rollWidth 逐花型不同是正常态（可变母卷宽 = ceilToStep(pw)），写回时按同规则重算。
     */
    public static List<CuttingInstruction> refine(List<CuttingInstruction> instructions,
            SolverParameters params) {
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }
        int totalWidth = params.getTotalWidth();
        CuttingInstruction template = instructions.get(0);
        for (CuttingInstruction instruction : instructions) {
            if (instruction == null
                    || instruction.getLength() != template.getLength()
                    || instruction.getThickness() != template.getThickness()
                    || !Objects.equals(instruction.getSurfaceTreatment(), template.getSurfaceTreatment())) {
                log.info("SPR skip: mixed instruction attributes in group {}", template.getGroupKey());
                return null;
            }
        }
        List<ColumnUse> uses = extractColumnUses(instructions);
        if (uses == null) {
            log.info("SPR skip: station assignments not reconstructible in group {}", template.getGroupKey());
            return null;
        }
        if (uses.size() < 2) {
            log.info("SPR skip: only {} block(s) in group {}", uses.size(), template.getGroupKey());
            return null;
        }
        // 墙钟预算帽（cutting.spr.budgetMs>0 时生效）：跨两段的绝对截止时刻，
        // microIterate 每个目标块前检查，到点收工（已接受的改善保留）。
        long startedAt = System.currentTimeMillis();
        long budget = budgetMs();
        long deadline = budget > 0 ? System.currentTimeMillis() + budget : Long.MAX_VALUE;
        ConcurrentMap<SubproblemKey, Result> cache = new ConcurrentHashMap<>();
        RefineStats stats = new RefineStats();
        List<ColumnUse> improved = microIterate(uses, PARITY_DONORS, PARITY_BUDGET_MS, MAX_ITERATIONS,
                totalWidth, deadline, cache, stats);
        improved = microIterate(improved, MERGE_DONORS, MERGE_BUDGET_MS, MAX_ITERATIONS,
                totalWidth, deadline, cache, stats);
        improved = coalesceBySignature(improved);
        log.info("SPR summary: elapsedMs={}, iterations={}, targets={}, subproblemRequests={}, "
                        + "solverCalls={}, cacheHits={}, acceptedMoves={}, budgetStops={}, cachedResults={}",
                System.currentTimeMillis() - startedAt,
                stats.iterations.get(), stats.targets.get(), stats.subproblemRequests(),
                stats.solverCalls(), stats.cacheHits(), stats.acceptedMoves.get(),
                stats.budgetStops.get(), cache.size());
        return toInstructions(improved, template, params);
    }

    /** 花型实际母卷宽：pw 向上取整到 stepSize 并截到 [minRollWidth, maxRollWidth]（与花型生成端同规则）。 */
    private static int rollWidthFor(int patternWidth, SolverParameters params) {
        int step = Math.max(1, params.getStepSize());
        int rollWidth = (patternWidth + step - 1) / step * step;
        rollWidth = Math.max(rollWidth, params.getMinRollWidth());
        return Math.min(rollWidth, params.getMaxRollWidth());
    }

    /** 指令 → 列使用（同 B6 提取器）：按卷重放工位序号，连续同配置卷合并为一个块。 */
    static List<ColumnUse> extractColumnUses(List<CuttingInstruction> instructions) {
        List<ColumnUse> uses = new ArrayList<>();
        for (CuttingInstruction instruction : instructions) {
            if (instruction.getSubRolls() == null || instruction.getSubRolls().isEmpty()
                    || instruction.getStationAssignments() == null) {
                return null;
            }
            Map<Integer, ArrayDeque<String>> buckets = new LinkedHashMap<>();
            for (StationAssignment assignment : instruction.getStationAssignments()) {
                if (assignment.getMessageText() == null || assignment.getMessageText().isBlank()) {
                    return null;
                }
                buckets.computeIfAbsent(assignment.getWidth(), k -> new ArrayDeque<>())
                        .add(assignment.getMessageText());
            }
            String previousSig = null;
            Column previousColumn = null;
            int run = 0;
            for (int roll = 0; roll < instruction.getUsageCount(); roll++) {
                Map<Integer, List<String>> config = new TreeMap<>();
                for (Map.Entry<Integer, Integer> e : new TreeMap<>(instruction.getSubRolls()).entrySet()) {
                    List<String> messages = new ArrayList<>();
                    ArrayDeque<String> bucket = buckets.get(e.getKey());
                    for (int s = 0; s < e.getValue(); s++) {
                        if (bucket == null || bucket.isEmpty()) {
                            return null;
                        }
                        messages.add(bucket.poll());
                    }
                    config.put(e.getKey(), messages);
                }
                Column column = Column.of(instruction.getSubRolls(), config);
                String sig = column.signature();
                if (sig.equals(previousSig)) {
                    run++;
                } else {
                    if (run > 0) {
                        uses.add(new ColumnUse(previousColumn, run));
                    }
                    previousSig = sig;
                    previousColumn = column;
                    run = 1;
                }
            }
            if (run > 0) {
                uses.add(new ColumnUse(previousColumn, run));
            }
        }
        return uses;
    }

    /**
     * 微邻域最陡下降主循环（B6 L12b/L12c 机制 + 并行提速）：每轮枚举目标块
     * （odd 优先，其次 small≤5），**并行**评估各自的残差重建（donorCount 捐赠块、
     * 共享需求键×2+共享宽度打分），只应用**单个最优改善**移动，下一轮对更新后的解重估。
     *
     * <p>一次一动保留串行的接受链效应（后续移动能借前面接受的新块），质量≈串行贪心；
     * 评估并行把每轮墙钟从 Σ 降到 max。收敛（无改善）、达最大迭代数、或总预算到即停。
     */
    private static List<ColumnUse> microIterate(List<ColumnUse> start,
            int donorCount, long budgetMs, int maxIterations, int totalWidth, long deadline,
            ConcurrentMap<SubproblemKey, Result> cache, RefineStats stats) {
        List<ColumnUse> current = new ArrayList<>(start);
        for (int iter = 1; iter <= maxIterations; iter++) {
            if (System.currentTimeMillis() >= deadline) {
                stats.budgetStops.incrementAndGet();
                log.info("SPR budget cap reached at iter{}, stopping", iter);
                break;
            }
            stats.iterations.incrementAndGet();
            List<ColumnUse> targets = new ArrayList<>();
            for (ColumnUse use : current) {
                if (use.count() % 2 != 0) {
                    targets.add(use);
                }
            }
            for (ColumnUse use : current) {
                if (use.count() % 2 == 0 && use.count() <= SMALL_MAX_CARS) {
                    targets.add(use);
                }
            }
            if (targets.isEmpty()) {
                break;
            }
            stats.targets.addAndGet(targets.size());
            // 并行评估全部目标（对同一快照 current，纯读、独立 SCIP 子解）。
            final List<ColumnUse> snapshot = current;
            List<Move> moves;
            if (parallelEnabled()) {
                moves = targets.parallelStream()
                        .map(t -> evaluateTarget(t, snapshot, donorCount, budgetMs, totalWidth,
                                cache, stats))
                        .collect(java.util.stream.Collectors.toList());
            } else {
                moves = new ArrayList<>(targets.size());
                for (ColumnUse t : targets) {
                    moves.add(evaluateTarget(t, snapshot, donorCount, budgetMs, totalWidth,
                            cache, stats));
                }
            }
            // 选最优改善：组数降幅大 > 奇块降幅大 > target 顺序（稳定，确定）。
            Move best = null;
            int bestGroupsCut = 0;
            int bestOddCut = 0;
            for (Move move : moves) {
                if (move == null || !move.better()) {
                    continue;
                }
                int groupsCut = move.removed().size() - move.sub().groups();
                int oddCut = move.removedOdd() - move.sub().oddBlocks();
                if (best == null || groupsCut > bestGroupsCut
                        || (groupsCut == bestGroupsCut && oddCut > bestOddCut)) {
                    best = move;
                    bestGroupsCut = groupsCut;
                    bestOddCut = oddCut;
                }
            }
            if (best == null) {
                break;
            }
            // 应用（快照 == current，拆除块必全部在场）。
            List<ColumnUse> working = new ArrayList<>(current);
            for (ColumnUse r : best.removed()) {
                working.remove(r);
            }
            working.addAll(best.sub().uses());
            current = working;
            stats.acceptedMoves.incrementAndGet();
            log.info("SPR accept iter{}: {} blocks (odd {}) -> {} (odd {}) | target={}cars {}",
                    iter, best.removed().size(), best.removedOdd(),
                    best.sub().groups(), best.sub().oddBlocks(),
                    best.target().count(), best.target().column().signature());
        }
        return current;
    }

    /** 一次微邻域移动的评估结果（目标块、拆除块集、重建解、是否改善、拆除奇块数）。 */
    private record Move(ColumnUse target, List<ColumnUse> removed, Result sub,
                        boolean better, int removedOdd) {
    }

    /** 单目标评估（纯函数，只读快照、不改共享状态；供并行调用）：选捐赠块、残差重建、判断改善。 */
    private static Move evaluateTarget(ColumnUse target, List<ColumnUse> snapshot,
            int donorCount, long budgetMs, int totalWidth,
            ConcurrentMap<SubproblemKey, Result> cache, RefineStats stats) {
        int targetIdx = snapshot.indexOf(target);
        if (targetIdx < 0) {
            return null;
        }
        List<ColumnUse> others = new ArrayList<>(snapshot);
        others.remove(targetIdx);
        Map<String, Integer> targetUse = target.column().demandUse();
        others.sort(Comparator.comparingInt((ColumnUse s) -> {
            int sharedKeys = 0;
            for (String key : s.column().demandUse().keySet()) {
                if (targetUse.containsKey(key)) {
                    sharedKeys++;
                }
            }
            int sharedWidths = 0;
            for (Integer width : s.column().pattern().keySet()) {
                if (target.column().pattern().containsKey(width)) {
                    sharedWidths++;
                }
            }
            return -(sharedKeys * 2 + sharedWidths);
        }));
        List<ColumnUse> removed = new ArrayList<>();
        removed.add(target);
        removed.addAll(others.subList(0, Math.min(donorCount, others.size())));
        Result sub = microRebuild(removed, budgetMs, totalWidth, cache, stats);
        if (sub == null || sub.groups() == 0) {
            return null;
        }
        int removedOdd = 0;
        for (ColumnUse r : removed) {
            if (r.count() % 2 != 0) {
                removedOdd++;
            }
        }
        boolean better = sub.groups() < removed.size()
                || (sub.groups() == removed.size() && sub.oddBlocks() < removedOdd);
        return new Move(target, removed, sub, better, removedOdd);
    }

    /** 微邻域重建：残差需求 + 比例匹配注入列 + 拆除块兜底，单段求解（odd 已在目标）。 */
    private static Result microRebuild(List<ColumnUse> removed, long budgetMs, int totalWidth,
            ConcurrentMap<SubproblemKey, Result> cache, RefineStats stats) {
        Map<String, Integer> residual = new LinkedHashMap<>();
        int removedCars = 0;
        int removedWaste = 0;
        int minPw = Integer.MAX_VALUE;
        int maxPw = 0;
        for (ColumnUse use : removed) {
            removedCars += use.count();
            removedWaste += use.count() * (totalWidth - use.column().patternWidth());
            minPw = Math.min(minPw, use.column().patternWidth());
            maxPw = Math.max(maxPw, use.column().patternWidth());
            for (Map.Entry<String, Integer> e : use.column().demandUse().entrySet()) {
                residual.merge(e.getKey(), use.count() * e.getValue(), Integer::sum);
            }
        }
        Map<Integer, Map<String, Integer>> residualByWidth = byWidth(residual);
        List<Map<Integer, Integer>> shapes = stratifiedShapes(
                new ArrayList<>(residualByWidth.keySet()), minPw, maxPw, residualByWidth);
        List<Column> subPool = new ArrayList<>();
        for (ColumnUse use : removed) {
            subPool.add(use.column());
            shapes.add(use.column().pattern());
        }
        subPool.addAll(UnifiedSetPartitionSolver.structuredColumns(shapes, residualByWidth,
                MAX_MESSAGES_PER_WIDTH, MAX_OPTIONS_PER_WIDTH, CONFIGS_PER_SHAPE));
        List<ColumnUse> warmStart = new ArrayList<>(removed);
        SubproblemKey key = subproblemKey(subPool, residual, removedCars, removedWaste,
                totalWidth, budgetMs, warmStart);
        int exactCars = removedCars;
        int wasteCap = removedWaste;
        return solveCached(key, cache, stats, () -> new UnifiedSetPartitionSolver().solve(
                subPool, residual, exactCars, wasteCap, totalWidth, budgetMs, ODD_WEIGHT,
                warmStart));
    }

    static SubproblemKey subproblemKey(List<Column> pool,
            Map<String, Integer> demand,
            int exactCars,
            int wasteCap,
            int totalWidth,
            long timeLimitMs,
            List<ColumnUse> warmStart) {
        String poolSignature = pool.stream()
                .map(column -> column.patternWidth() + ":" + column.signature())
                .collect(Collectors.joining("\u001e"));
        String demandSignature = demand.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\u001e"));
        String warmStartSignature = warmStart.stream()
                .map(use -> use.column().patternWidth() + ":" + use.column().signature()
                        + "#" + use.count())
                .collect(Collectors.joining("\u001e"));
        return new SubproblemKey(poolSignature, demandSignature, exactCars, wasteCap,
                totalWidth, timeLimitMs, warmStartSignature);
    }

    static Result solveCached(SubproblemKey key,
            ConcurrentMap<SubproblemKey, Result> cache,
            RefineStats stats,
            Supplier<Result> solverCall) {
        stats.subproblemRequests.incrementAndGet();
        if (!cacheEnabled()) {
            stats.solverCalls.incrementAndGet();
            return solverCall.get();
        }
        AtomicReference<Result> solvedHere = new AtomicReference<>();
        Result cached = cache.compute(key, (ignored, existing) -> {
            if (existing != null) {
                return existing;
            }
            stats.solverCalls.incrementAndGet();
            Result result = solverCall.get();
            solvedHere.set(result);
            return cacheable(result) ? result : null;
        });
        if (solvedHere.get() != null) {
            return solvedHere.get();
        }
        if (cached != null) {
            stats.cacheHits.incrementAndGet();
        }
        return cached;
    }

    private static boolean cacheable(Result result) {
        return result != null
                && ("OPTIMAL".equals(result.status()) || "INFEASIBLE".equals(result.status()));
    }

    /** 残差宽度上的分层形状枚举（需求质量加权排序，B6 同源）。 */
    private static List<Map<Integer, Integer>> stratifiedShapes(List<Integer> widths,
            int minRw, int maxRw, Map<Integer, Map<String, Integer>> demandByWidth) {
        List<Map<Integer, Integer>> raw = new ArrayList<>();
        enumShapesRec(widths, 0, new LinkedHashMap<>(), 0, minRw, maxRw, raw);
        Map<Integer, List<Map<Integer, Integer>>> byBand = new TreeMap<>(Comparator.reverseOrder());
        for (Map<Integer, Integer> shape : raw) {
            int pw = shape.entrySet().stream().mapToInt(e -> e.getKey() * e.getValue()).sum();
            byBand.computeIfAbsent(pw / 10, k -> new ArrayList<>()).add(shape);
        }
        Comparator<Map<Integer, Integer>> ranking =
                Comparator.comparingLong((Map<Integer, Integer> s) -> -s.entrySet().stream()
                        .mapToLong(e -> (long) e.getValue() * demandByWidth
                                .getOrDefault(e.getKey(), Map.of()).values().stream()
                                .mapToInt(Integer::intValue).sum())
                        .sum());
        List<Map<Integer, Integer>> kept = new ArrayList<>();
        for (List<Map<Integer, Integer>> band : byBand.values()) {
            band.sort(ranking);
            for (int i = 0; i < Math.min(SHAPE_PER_BAND, band.size()) && kept.size() < SHAPE_TOTAL_CAP; i++) {
                kept.add(band.get(i));
            }
            if (kept.size() >= SHAPE_TOTAL_CAP) {
                break;
            }
        }
        return kept;
    }

    private static void enumShapesRec(List<Integer> widths, int idx, Map<Integer, Integer> current,
            int sum, int minRw, int maxRw, List<Map<Integer, Integer>> out) {
        if (out.size() >= SHAPE_RAW_CAP) {
            return;
        }
        if (idx == widths.size()) {
            if (sum >= minRw && sum <= maxRw && !current.isEmpty()) {
                out.add(new LinkedHashMap<>(current));
            }
            return;
        }
        int w = widths.get(idx);
        int maxCount = (maxRw - sum) / w;
        for (int count = 0; count <= maxCount && out.size() < SHAPE_RAW_CAP; count++) {
            if (count > 0) {
                if (!current.containsKey(w) && current.size() >= SHAPE_MAX_DISTINCT) {
                    break;
                }
                current.put(w, count);
            }
            enumShapesRec(widths, idx + 1, current, sum + count * w, minRw, maxRw, out);
        }
        current.remove(w);
    }

    private static Map<Integer, Map<String, Integer>> byWidth(Map<String, Integer> demand) {
        Map<Integer, Map<String, Integer>> byWidth = new TreeMap<>();
        for (Map.Entry<String, Integer> e : demand.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            byWidth.computeIfAbsent(Integer.parseInt(parts[0]), k -> new LinkedHashMap<>())
                    .put(parts[1], e.getValue());
        }
        return byWidth;
    }

    /** 同签名块合并（c1+c2 恒不劣）+ 车数降序排定，作为最终块序。 */
    private static List<ColumnUse> coalesceBySignature(List<ColumnUse> uses) {
        Map<String, ColumnUse> bySig = new LinkedHashMap<>();
        for (ColumnUse use : uses) {
            bySig.merge(use.column().signature(), use,
                    (a, b) -> new ColumnUse(a.column(), a.count() + b.count()));
        }
        List<ColumnUse> merged = new ArrayList<>(bySig.values());
        merged.sort(Comparator.comparingInt((ColumnUse u) -> -u.count())
                .thenComparing(u -> u.column().signature()));
        return merged;
    }

    /** 列使用 → 指令：一块一指令，工位序号按卷×宽度×工位铺开（与构建端约定一致）。 */
    private static List<CuttingInstruction> toInstructions(List<ColumnUse> uses,
            CuttingInstruction template, SolverParameters params) {
        int totalWidth = params.getTotalWidth();
        List<CuttingInstruction> out = new ArrayList<>();
        for (ColumnUse use : uses) {
            CuttingInstruction instruction = new CuttingInstruction();
            instruction.setGroupKey(template.getGroupKey());
            instruction.setRollWidth(rollWidthFor(use.column().patternWidth(), params));
            instruction.setLength(template.getLength());
            instruction.setSurfaceTreatment(template.getSurfaceTreatment());
            instruction.setThickness(template.getThickness());
            instruction.setSubRolls(new LinkedHashMap<>(use.column().pattern()));
            instruction.setUsageCount(use.count());
            instruction.setPatternWidth(use.column().patternWidth());
            instruction.setWaste(totalWidth - use.column().patternWidth());
            List<StationAssignment> assignments = new ArrayList<>();
            for (int roll = 0; roll < use.count(); roll++) {
                for (Map.Entry<Integer, List<String>> e : use.column().config().entrySet()) {
                    for (String message : e.getValue()) {
                        assignments.add(new StationAssignment(e.getKey(), message));
                    }
                }
            }
            instruction.setStationAssignments(assignments);
            out.add(instruction);
        }
        return out;
    }
}
