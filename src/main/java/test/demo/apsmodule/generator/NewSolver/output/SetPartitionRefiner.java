package test.demo.apsmodule.generator.NewSolver.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
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
    private static final long PARITY_BUDGET_MS = 30_000;
    private static final int PARITY_PASSES = 3;
    private static final int MERGE_DONORS = 5;
    private static final long MERGE_BUDGET_MS = 60_000;
    private static final int MERGE_PASSES = 2;
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

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("cutting.spr.enabled", "true").trim());
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
        List<ColumnUse> improved = microIterate(uses, PARITY_DONORS, PARITY_BUDGET_MS, PARITY_PASSES, totalWidth);
        improved = microIterate(improved, MERGE_DONORS, MERGE_BUDGET_MS, MERGE_PASSES, totalWidth);
        improved = coalesceBySignature(improved);
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
     * 微邻域迭代主循环（B6 L12b/L12c 同源）：每次 1 目标块（odd 优先，其次 small≤5）
     * + donorCount 个捐赠块（共享需求键×2+共享宽度打分），残差精确重建，
     * 严格改善（组减，或组平 odd 减）接受，pass 无改善即收敛退出。
     */
    private static List<ColumnUse> microIterate(List<ColumnUse> start,
            int donorCount, long budgetMs, int maxPasses, int totalWidth) {
        List<ColumnUse> current = new ArrayList<>(start);
        for (int pass = 1; pass <= maxPasses; pass++) {
            boolean improvedThisPass = false;
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
            for (ColumnUse target : targets) {
                int targetIdx = current.indexOf(target);
                if (targetIdx < 0) {
                    continue;
                }
                List<ColumnUse> others = new ArrayList<>(current);
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
                List<ColumnUse> kept = new ArrayList<>(current);
                for (ColumnUse r : removed) {
                    kept.remove(r);
                }

                Result sub = microRebuild(removed, budgetMs, totalWidth);
                if (sub == null || sub.groups() == 0) {
                    continue;
                }
                int removedOdd = 0;
                for (ColumnUse r : removed) {
                    if (r.count() % 2 != 0) {
                        removedOdd++;
                    }
                }
                boolean better = sub.groups() < removed.size()
                        || (sub.groups() == removed.size() && sub.oddBlocks() < removedOdd);
                if (better) {
                    log.info("SPR accept pass{}: {} blocks (odd {}) -> {} (odd {}) | target={}cars {}",
                            pass, removed.size(), removedOdd, sub.groups(), sub.oddBlocks(),
                            target.count(), target.column().signature());
                    current = kept;
                    current.addAll(sub.uses());
                    improvedThisPass = true;
                }
            }
            if (!improvedThisPass) {
                break;
            }
        }
        return current;
    }

    /** 微邻域重建：残差需求 + 比例匹配注入列 + 拆除块兜底，单段求解（odd 已在目标）。 */
    private static Result microRebuild(List<ColumnUse> removed, long budgetMs, int totalWidth) {
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
        return new UnifiedSetPartitionSolver().solve(
                subPool, residual, removedCars, removedWaste, totalWidth, budgetMs, ODD_WEIGHT,
                new ArrayList<>(removed));
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
