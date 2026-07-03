package test.demo.apsmodule.generator.NewSolver.mip;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 根治实验：A+B 合并的统一 set-partition 模型。
 *
 * <p>列 = (花型, 整车序号配置)，计数变量 c_j 直接决定块大小；启用列数 Σy 就是序号组数
 * ——组数第一次真正进入目标函数，而不是靠花型数/奇偶/对齐等代理指标（四条代理已全部
 * 实测证伪，见笔记 11）。奇偶做次级 tie-break（人工解剖：47 块里 45 块偶，唯二奇块是
 * 数学必然，见笔记 12）。
 *
 * <p>实验性代码：不接入生产管线，由 B6UnifiedSetPartitionTest 驱动。
 */
public class UnifiedSetPartitionSolver {

    private static final Logger log = LoggerFactory.getLogger(UnifiedSetPartitionSolver.class);

    private static final String SCIP_DETERMINISTIC_PARAMS =
            "randomization/randomseedshift = 0\n"
          + "randomization/permutationseed = 0\n"
          + "randomization/lpseed = 0\n";

    /** 奇数车列的次级罚。须保证 Σ罚 < 1（一个组的代价），严格从属于组数目标。 */
    private static final double DEFAULT_ODD_WEIGHT = 0.02;

    /** 列：花型（宽度→条数）+ 整车配置（宽度→每工位序号，已排序）。 */
    public record Column(Map<Integer, Integer> pattern,
                         Map<Integer, List<String>> config,
                         int patternWidth) {

        public static Column of(Map<Integer, Integer> pattern, Map<Integer, List<String>> config) {
            Map<Integer, Integer> p = new TreeMap<>(pattern);
            Map<Integer, List<String>> g = new TreeMap<>();
            for (Map.Entry<Integer, List<String>> e : config.entrySet()) {
                List<String> messages = new ArrayList<>(e.getValue());
                messages.sort(String::compareTo);
                g.put(e.getKey(), List.copyOf(messages));
            }
            int pw = p.entrySet().stream().mapToInt(e -> e.getKey() * e.getValue()).sum();
            return new Column(p, g, pw);
        }

        public String signature() {
            return config.entrySet().stream()
                    .map(e -> e.getKey() + "=" + String.join(",", e.getValue()))
                    .collect(Collectors.joining("|"));
        }

        /** 该列每车对 (width|message) 需求的消耗量。 */
        public Map<String, Integer> demandUse() {
            Map<String, Integer> use = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<String>> e : config.entrySet()) {
                for (String m : e.getValue()) {
                    use.merge(e.getKey() + "|" + m, 1, Integer::sum);
                }
            }
            return use;
        }
    }

    public record ColumnUse(Column column, int count) {
    }

    public record Result(List<ColumnUse> uses, int groups, int oddBlocks, int smallBlocks,
                         int cars, int waste, String status) {
    }

    /**
     * @param pool      候选列池（按签名去重）
     * @param demand    (width|message) -> 需求卷数（精确覆盖）
     * @param exactCars 总车数（= 现有管线的最优车数，硬等式，得率锁死）
     * @param wasteCap  总废边上限（= 现有管线的最优废边，硬约束）
     * @param totalWidth 母卷总宽
     */
    public Result solve(List<Column> pool,
            Map<String, Integer> demand,
            int exactCars,
            int wasteCap,
            int totalWidth,
            long timeLimitMs,
            double oddWeight) {
        return solve(pool, demand, exactCars, wasteCap, totalWidth, timeLimitMs, oddWeight, List.of());
    }

    /**
     * @param warmStart 已知可行解（如人工方案/现有管线解），作为 SCIP hint 注入——
     *                  set-partition 千列规模下分支定界冷启动常在时限内找不到池中已有的
     *                  好组合（实测 180s 只到 54 组而人工 46 列就在池里），热启动后只做改进。
     */
    public Result solve(List<Column> pool,
            Map<String, Integer> demand,
            int exactCars,
            int wasteCap,
            int totalWidth,
            long timeLimitMs,
            double oddWeight,
            List<ColumnUse> warmStart) {
        return solve(pool, demand, exactCars, wasteCap, totalWidth, timeLimitMs, oddWeight,
                warmStart, null);
    }

    /**
     * @param maxGroups 非空时加硬约束 Σy ≤ maxGroups 且目标改为「odd 优先」（组数封顶后
     *                  专修奇偶）——字典序第二段：先 solve() 拿最少组数，再以其为帽调用本重载。
     */
    public Result solve(List<Column> pool,
            Map<String, Integer> demand,
            int exactCars,
            int wasteCap,
            int totalWidth,
            long timeLimitMs,
            double oddWeight,
            List<ColumnUse> warmStart,
            Integer maxGroups) {
        if (oddWeight < 0) {
            oddWeight = DEFAULT_ODD_WEIGHT;
        }
        // 去重 + 支持度过滤（列的每个 (w,m) 消耗不得超过需求）
        Map<String, Column> bySig = new LinkedHashMap<>();
        for (Column column : pool) {
            if (supportOf(column, demand) >= 1) {
                bySig.putIfAbsent(column.signature(), column);
            }
        }
        List<Column> columns = new ArrayList<>(bySig.values());
        log.info("UnifiedSP: pool={} (deduped from {}), demandKeys={}, cars={}, wasteCap={}",
                columns.size(), pool.size(), demand.size(), exactCars, wasteCap);

        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            solver = MPSolver.createSolver("CBC");
        }
        if (solver == null) {
            return null;
        }
        solver.setSolverSpecificParametersAsString(SCIP_DETERMINISTIC_PARAMS);
        try { solver.setNumThreads(1); } catch (Exception ignored) { }

        int n = columns.size();
        MPVariable[] c = new MPVariable[n];
        MPVariable[] y = new MPVariable[n];
        MPVariable[] o = new MPVariable[n];
        MPVariable[] h = new MPVariable[n];
        for (int j = 0; j < n; j++) {
            int support = Math.min(exactCars, supportOf(columns.get(j), demand));
            c[j] = solver.makeIntVar(0, support, "c_" + j);
            y[j] = solver.makeBoolVar("y_" + j);
            o[j] = solver.makeBoolVar("o_" + j);
            h[j] = solver.makeIntVar(0, support, "h_" + j);
            // c <= support*y ; c >= y ; c - 2h - o = 0
            MPConstraint upper = solver.makeConstraint(-MPSolver.infinity(), 0, "ub_" + j);
            upper.setCoefficient(c[j], 1);
            upper.setCoefficient(y[j], -support);
            MPConstraint lower = solver.makeConstraint(0, MPSolver.infinity(), "lb_" + j);
            lower.setCoefficient(c[j], 1);
            lower.setCoefficient(y[j], -1);
            MPConstraint parity = solver.makeConstraint(0, 0, "par_" + j);
            parity.setCoefficient(c[j], 1);
            parity.setCoefficient(h[j], -2);
            parity.setCoefficient(o[j], -1);
        }

        if (warmStart != null && !warmStart.isEmpty()) {
            Map<String, Integer> hintCounts = new LinkedHashMap<>();
            for (ColumnUse use : warmStart) {
                hintCounts.merge(use.column().signature(), use.count(), Integer::sum);
            }
            List<MPVariable> hintVars = new ArrayList<>(n * 4);
            List<Double> hintValues = new ArrayList<>(n * 4);
            int matched = 0;
            for (int j = 0; j < n; j++) {
                int count = hintCounts.getOrDefault(columns.get(j).signature(), 0);
                if (count > 0) {
                    matched++;
                }
                hintVars.add(c[j]);
                hintValues.add((double) count);
                hintVars.add(y[j]);
                hintValues.add(count > 0 ? 1.0 : 0.0);
                hintVars.add(o[j]);
                hintValues.add(count % 2 != 0 ? 1.0 : 0.0);
                hintVars.add(h[j]);
                hintValues.add((double) (count / 2));
            }
            double[] values = new double[hintValues.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = hintValues.get(i);
            }
            solver.setHint(hintVars.toArray(new MPVariable[0]), values);
            log.info("UnifiedSP warm start: {} hint columns matched into pool", matched);
        }

        // 需求精确覆盖
        Map<String, MPConstraint> demandConstraints = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : demand.entrySet()) {
            demandConstraints.put(e.getKey(),
                    solver.makeConstraint(e.getValue(), e.getValue(), "d_" + e.getKey()));
        }
        for (int j = 0; j < n; j++) {
            for (Map.Entry<String, Integer> use : columns.get(j).demandUse().entrySet()) {
                MPConstraint constraint = demandConstraints.get(use.getKey());
                if (constraint == null) {
                    // 列消耗了不存在的需求键——支持度过滤应已排除
                    return null;
                }
                constraint.setCoefficient(c[j], use.getValue());
            }
        }

        MPConstraint carsConstraint = solver.makeConstraint(exactCars, exactCars, "cars");
        for (int j = 0; j < n; j++) {
            carsConstraint.setCoefficient(c[j], 1);
        }
        MPConstraint wasteConstraint = solver.makeConstraint(0, wasteCap, "waste");
        for (int j = 0; j < n; j++) {
            wasteConstraint.setCoefficient(c[j], totalWidth - columns.get(j).patternWidth());
        }

        MPObjective objective = solver.objective();
        if (maxGroups != null) {
            // 字典序第二段：组数封顶为硬约束，odd 成为主目标（组数仅留微小系数防止无谓多开组）
            MPConstraint groupsCap = solver.makeConstraint(0, maxGroups, "groupsCap");
            for (int j = 0; j < n; j++) {
                groupsCap.setCoefficient(y[j], 1);
            }
            for (int j = 0; j < n; j++) {
                objective.setCoefficient(o[j], 1.0);
                objective.setCoefficient(y[j], 0.001);
            }
        } else {
            for (int j = 0; j < n; j++) {
                objective.setCoefficient(y[j], 1.0);
                objective.setCoefficient(o[j], oddWeight);
            }
        }
        objective.setMinimization();

        solver.setTimeLimit(Math.max(1000, timeLimitMs));
        MPSolver.ResultStatus status = solver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            log.warn("UnifiedSP returned {}", status);
            return new Result(List.of(), 0, 0, 0, 0, 0, status.toString());
        }

        List<ColumnUse> uses = new ArrayList<>();
        int cars = 0;
        int waste = 0;
        int odd = 0;
        int small = 0;
        for (int j = 0; j < n; j++) {
            int count = (int) Math.round(c[j].solutionValue());
            if (count <= 0) {
                continue;
            }
            uses.add(new ColumnUse(columns.get(j), count));
            cars += count;
            waste += count * (totalWidth - columns.get(j).patternWidth());
            if (count % 2 != 0) {
                odd++;
            }
            if (count <= 5) {
                small++;
            }
        }
        uses.sort(Comparator.comparingInt((ColumnUse u) -> u.count()).reversed());
        log.info("UnifiedSP solved: {} groups={} odd={} small={} cars={} waste={}",
                status, uses.size(), odd, small, cars, waste);
        return new Result(uses, uses.size(), odd, small, cars, waste, status.toString());
    }

    /**
     * 比例匹配贪心构造 warm start（模仿人工三板斧的装配顺序）：每步在池中选
     * "以最大可用车数 c 使用时,同时耗尽的序号最多 → c 为偶 → c 最大" 的列，
     * 直至需求耗尽或无列可用（允许部分覆盖——hint 不要求可行，SCIP 自行修复补全）。
     * 假设：warm start 与列池同源（对齐风格）时，SCIP 才能从正确盆地出发抛光到 45/1。
     */
    public static List<ColumnUse> greedyAlignedWarmStart(List<Column> pool, Map<String, Integer> demand) {
        return greedyAlignedWarmStart(pool, demand, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
    }

    /**
     * 带废边走廊守卫的版本：每步保证「已用废边 + 本列废边 + 剩余车数×池内最小单车废边」
     * 不超预算（v3 实测无守卫时贪心把废边预算烧穿，残差需要平均 pw>4400 而数学不可行），
     * 车数同理封顶。
     */
    public static List<ColumnUse> greedyAlignedWarmStart(List<Column> pool, Map<String, Integer> demand,
            int exactCars, int wasteCap, int totalWidth) {
        int maxPw = pool.stream().mapToInt(Column::patternWidth).max().orElse(totalWidth);
        int minWastePerCar = Math.max(0, totalWidth - maxPw);
        Map<String, Integer> remaining = new LinkedHashMap<>(demand);
        List<ColumnUse> uses = new ArrayList<>();
        int usedCars = 0;
        int usedWaste = 0;
        while (remaining.values().stream().anyMatch(v -> v > 0) && usedCars < exactCars) {
            Column best = null;
            int bestCount = 0;
            long bestKey = -1;
            for (Column column : pool) {
                int count = Math.min(supportOf(column, remaining), exactCars - usedCars);
                if (count < 1) {
                    continue;
                }
                int wastePerCar = Math.max(0, totalWidth - column.patternWidth());
                // 废边走廊：取 c 后，剩余 (exactCars-usedCars-c) 车即使全用最高pw也要能装下
                if (wastePerCar > minWastePerCar && wasteCap != Integer.MAX_VALUE) {
                    long budget = (long) wasteCap - usedWaste - (long) (exactCars - usedCars) * minWastePerCar;
                    int cFeasible = (int) (budget / (wastePerCar - minWastePerCar));
                    count = Math.min(count, cFeasible);
                    if (count < 1) {
                        continue;
                    }
                }
                int exhaust = 0;
                for (Map.Entry<String, Integer> e : column.demandUse().entrySet()) {
                    if (remaining.getOrDefault(e.getKey(), 0) == count * e.getValue()) {
                        exhaust++;
                    }
                }
                // 排序：同时耗尽数 → 单车废边小（pw 高——v4 实测 exhaust 优先会专挑低 pw 列
                // 把废边预算烧穿到边界，残差失去全部腾挪空间）→ c 偶 → c 大
                long key = ((long) exhaust << 44)
                        | ((long) Math.max(0, 2047 - wastePerCar) << 33)
                        | ((count % 2 == 0 ? 1L : 0L) << 32)
                        | count;
                if (key > bestKey) {
                    bestKey = key;
                    best = column;
                    bestCount = count;
                }
            }
            if (best == null) {
                break;
            }
            uses.add(new ColumnUse(best, bestCount));
            usedCars += bestCount;
            usedWaste += bestCount * Math.max(0, totalWidth - best.patternWidth());
            for (Map.Entry<String, Integer> e : best.demandUse().entrySet()) {
                remaining.merge(e.getKey(), -bestCount * e.getValue(), Integer::sum);
            }
        }
        int leftover = remaining.values().stream().filter(v -> v > 0).mapToInt(Integer::intValue).sum();
        log.info("greedyAlignedWarmStart: columns={} cars={} waste={} oddBlocks={} leftoverRolls={}",
                uses.size(), usedCars, usedWaste,
                uses.stream().filter(u -> u.count() % 2 != 0).count(),
                leftover);
        return uses;
    }

    private static int supportOf(Column column, Map<String, Integer> demand) {
        int support = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> use : column.demandUse().entrySet()) {
            Integer q = demand.get(use.getKey());
            if (q == null || q < use.getValue()) {
                return 0;
            }
            support = Math.min(support, q / use.getValue());
        }
        return support == Integer.MAX_VALUE ? 0 : support;
    }

    // ==================== 结构化列生成 ====================

    /**
     * 为每个花型枚举整车配置列。手法编码自人工解剖（笔记12）：每宽度枚举"纯序号填满"
     * 与"多序号混装"的多重集，按支持度排序截断——大需求序号自然成为高支持度纯列，
     * 小需求序号在混装列里被保护性整块使用。
     *
     * @param patterns       花型集合（宽度→条数）
     * @param demandByWidth  width -> (message -> q)
     */
    public static List<Column> structuredColumns(Collection<Map<Integer, Integer>> patterns,
            Map<Integer, Map<String, Integer>> demandByWidth,
            int maxMessagesPerWidth,
            int maxOptionsPerWidth,
            int maxConfigsPerPattern) {
        Set<String> seenPatterns = new LinkedHashSet<>();
        List<Column> result = new ArrayList<>();
        for (Map<Integer, Integer> pattern : patterns) {
            String patternSig = new TreeMap<>(pattern).toString();
            if (!seenPatterns.add(patternSig)) {
                continue;
            }
            List<Integer> widths = new ArrayList<>(new TreeMap<>(pattern).keySet());
            List<List<List<String>>> optionsByWidth = new ArrayList<>();
            boolean feasible = true;
            for (int width : widths) {
                int slots = pattern.get(width);
                List<List<String>> options = widthOptions(
                        demandByWidth.getOrDefault(width, Map.of()),
                        slots, maxMessagesPerWidth, maxOptionsPerWidth);
                if (options.isEmpty()) {
                    feasible = false;
                    break;
                }
                optionsByWidth.add(options);
            }
            if (!feasible) {
                continue;
            }
            List<Map<Integer, List<String>>> configs = new ArrayList<>();
            crossProduct(widths, optionsByWidth, 0, new LinkedHashMap<>(), configs,
                    maxConfigsPerPattern);
            for (Map<Integer, List<String>> config : configs) {
                result.add(Column.of(pattern, config));
            }
        }
        return result;
    }

    /**
     * 配对导向的单宽度工位填充枚举（编码自人工手法，笔记12/13）。核心规则：**比例匹配**——
     * 列以车数 c 使用时，序号 m 在 p 个工位上消耗 c·p；若 (m1..mk) 按 (p1..pk) 分配且
     * q1/p1 = q2/p2 = …，则取 c = q1/p1 时全部序号同时耗尽（人工 26/27@1000 等量对、
     * 56×2+58+52 的 2:1:1 组合皆此规则特例）。另加缓冲组合：最大量序号配任意搭档，
     * 吸收余量。排序：比例匹配且 c 偶 > 比例匹配 > 支持度大。
     */
    private static List<List<String>> widthOptions(Map<String, Integer> demandByMessage,
            int slots, int bufferCount, int maxOptions) {
        List<Map.Entry<String, Integer>> messages = demandByMessage.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
        if (messages.isEmpty()) {
            return List.of();
        }

        // option 候选 + 排序键（ratioMatched, evenC, support）
        record Ranked(List<String> option, boolean ratioMatched, boolean evenC, int support) {
        }
        Map<String, Ranked> bySig = new LinkedHashMap<>();
        java.util.function.BiConsumer<List<String>, Boolean> add = (option, ratioMatched) -> {
            List<String> sorted = new ArrayList<>(option);
            sorted.sort(String::compareTo);
            String sig = String.join(",", sorted);
            if (bySig.containsKey(sig)) {
                return;
            }
            Map<String, Long> mult = sorted.stream()
                    .collect(Collectors.groupingBy(m -> m, Collectors.counting()));
            int support = Integer.MAX_VALUE;
            for (Map.Entry<String, Long> e : mult.entrySet()) {
                support = Math.min(support,
                        demandByMessage.getOrDefault(e.getKey(), 0) / e.getValue().intValue());
            }
            if (support < 1) {
                return;
            }
            bySig.put(sig, new Ranked(List.copyOf(sorted), ratioMatched,
                    support % 2 == 0, support));
        };

        // 1) 纯列：每个序号独占全部工位
        for (Map.Entry<String, Integer> m : messages) {
            if (m.getValue() >= slots) {
                List<String> option = new ArrayList<>();
                for (int s = 0; s < slots; s++) {
                    option.add(m.getKey());
                }
                add.accept(option, true);
            }
        }
        // 2) 两分比例匹配：slots = p1 + p2，q1/p1 == q2/p2（整除）
        for (int p1 = slots - 1; p1 >= 1; p1--) {
            int p2 = slots - p1;
            if (p1 < p2) {
                break;
            }
            for (Map.Entry<String, Integer> m1 : messages) {
                if (m1.getValue() % p1 != 0) {
                    continue;
                }
                int c1 = m1.getValue() / p1;
                for (Map.Entry<String, Integer> m2 : messages) {
                    if (m2.getKey().equals(m1.getKey()) || m2.getValue() % p2 != 0
                            || m2.getValue() / p2 != c1) {
                        continue;
                    }
                    List<String> option = new ArrayList<>();
                    for (int s = 0; s < p1; s++) {
                        option.add(m1.getKey());
                    }
                    for (int s = 0; s < p2; s++) {
                        option.add(m2.getKey());
                    }
                    add.accept(option, true);
                }
            }
        }
        // 3) 三分比例匹配（slots≥3）：p1+p2+p3，q_i/p_i 全相等
        if (slots >= 3) {
            for (int p1 = slots - 2; p1 >= 1; p1--) {
                for (int p2 = Math.min(p1, slots - p1 - 1); p2 >= 1; p2--) {
                    int p3 = slots - p1 - p2;
                    if (p3 < 1 || p3 > p2) {
                        continue;
                    }
                    for (Map.Entry<String, Integer> m1 : messages) {
                        if (m1.getValue() % p1 != 0) {
                            continue;
                        }
                        int c1 = m1.getValue() / p1;
                        for (Map.Entry<String, Integer> m2 : messages) {
                            if (m2.getKey().equals(m1.getKey()) || m2.getValue() % p2 != 0
                                    || m2.getValue() / p2 != c1) {
                                continue;
                            }
                            for (Map.Entry<String, Integer> m3 : messages) {
                                if (m3.getKey().equals(m1.getKey()) || m3.getKey().equals(m2.getKey())
                                        || m3.getValue() % p3 != 0 || m3.getValue() / p3 != c1) {
                                    continue;
                                }
                                List<String> option = new ArrayList<>();
                                for (int s = 0; s < p1; s++) {
                                    option.add(m1.getKey());
                                }
                                for (int s = 0; s < p2; s++) {
                                    option.add(m2.getKey());
                                }
                                for (int s = 0; s < p3; s++) {
                                    option.add(m3.getKey());
                                }
                                add.accept(option, true);
                            }
                        }
                    }
                }
            }
        }
        // 4) 缓冲组合：最大量的 bufferCount 个序号做填缝剂，与任意搭档成列（无比例要求）
        List<Map.Entry<String, Integer>> buffers = messages.subList(0, Math.min(bufferCount, messages.size()));
        if (slots >= 2) {
            for (Map.Entry<String, Integer> buf : buffers) {
                for (Map.Entry<String, Integer> partner : messages) {
                    if (partner.getKey().equals(buf.getKey())) {
                        continue;
                    }
                    for (int pBuf = 1; pBuf < slots; pBuf++) {
                        List<String> option = new ArrayList<>();
                        for (int s = 0; s < pBuf; s++) {
                            option.add(buf.getKey());
                        }
                        for (int s = 0; s < slots - pBuf; s++) {
                            option.add(partner.getKey());
                        }
                        add.accept(option, false);
                    }
                }
            }
        }

        return bySig.values().stream()
                .sorted(Comparator
                        .comparing(Ranked::ratioMatched, Comparator.reverseOrder())
                        .thenComparing(Ranked::evenC, Comparator.reverseOrder())
                        .thenComparing(Comparator.comparingInt(Ranked::support).reversed())
                        .thenComparing(r -> String.join(",", r.option())))
                .limit(maxOptions)
                .map(Ranked::option)
                .collect(Collectors.toList());
    }

    private static void crossProduct(List<Integer> widths,
            List<List<List<String>>> optionsByWidth,
            int index,
            Map<Integer, List<String>> current,
            List<Map<Integer, List<String>>> out,
            int cap) {
        if (out.size() >= cap) {
            return;
        }
        if (index == widths.size()) {
            out.add(new LinkedHashMap<>(current));
            return;
        }
        for (List<String> option : optionsByWidth.get(index)) {
            current.put(widths.get(index), option);
            crossProduct(widths, optionsByWidth, index + 1, current, out, cap);
            current.remove(widths.get(index));
            if (out.size() >= cap) {
                return;
            }
        }
    }
}
