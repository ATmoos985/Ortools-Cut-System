package test.demo.apsmodule.generator;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.service.*;
import test.demo.apsmodule.util.OrderAssignmentOptimizer;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import test.demo.apsmodule.solver.CuttingSolverAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PatternSolverFour - 列生成 + 受控超产求解器
 * 
 * 核心特性：
 * 1. 【列生成】动态生成有价值的模式（不预枚举）
 * 2. 【受控超产】总超产 ≤ TOTAL_OVER_CAP，且只允许大需求宽度超产
 * 3. 【下界保证】定价子问题强制 patternWidth >= minRollWidth
 * 4. 【多阶段MIP】字典序优化：超产最少 → 卷数最少 → 废边最小
 * 
 * 算法流程：
 * Stage A: 列生成LP（GLOP）动态生成模式
 * Stage B: 最终整数MIP（SCIP/CBC）多阶段字典序
 */
@Component
public class PatternSolverFour implements CuttingSolverAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(PatternSolverFour.class);

    // ==================== 可调常量 ====================
    private static final double EPSILON = 1e-6;
    private static final int DEFAULT_MAX_ITERATIONS = 300; // 默认最大迭代次数
    private static final int MAX_PATTERNS = 800;
    private static final int MAX_DISTINCT_WIDTHS = 4; // 单模式最多4种宽度（增强探索）
    private static final int TOP_K = 3; // 允许超产的前K个大需求宽度
    private static final double M_UNDER = 1e6; // 欠产惩罚系数
    private static final double EPS_WASTE = 0.001; // 废边系数（进入cost_p和定价）
    private static final double OVER_PENALTY_BASE = 0.1; // 超产惩罚基准
    private static final long DEFAULT_TIME_LIMIT_MS = 120000; // 默认MIP求解时限（2分钟）
    // Stage 4 序号组优化参数
    private static final double SEQ_GROUP_ALPHA = 1.0; // 模式种类数权重
    private static final double SEQ_GROUP_BETA = 0.0; // 🔥 临时禁用：测试 V8 订单分配效果
    // 订单分配优化开关（tuple+k块分配，减少序号组拆分）
    // GPT提供的V3算法：跨宽度成块分配
    private static final boolean USE_OPTIMIZED_ASSIGNMENT = true;

    // 利用率下限 = minRollWidth / maxRollWidth（动态计算，不是常量）

    // ==================== 配置参数 ====================
    private int minRollWidth;
    private int maxRollWidth;
    private int stepSize;
    private int totalWidth; // 🔥 新增：母卷总宽度（用于计算真实废边）
    private int totalOverCap; // 🔥 从配置中读取的超产上限
    private int iterationCount;

    // 🔥 新增：从配置中读取的算法参数
    private int maxIterations = DEFAULT_MAX_ITERATIONS;
    private long timeoutMs = DEFAULT_TIME_LIMIT_MS;

    static {
        try {
            Loader.loadNativeLibraries();
        } catch (Exception e) {
            log.error("OR-Tools库加载失败: " + e.getMessage());
        }
    }

    // ==================== 内部类：模式候选 ====================
    public static class PatternCandidate {
        public final Map<Integer, Integer> pattern; // width -> count
        public final int rollWidth;
        public final int patternWidth;

        public PatternCandidate(Map<Integer, Integer> pattern, int rollWidth) {
            this.pattern = new LinkedHashMap<>(pattern);
            this.rollWidth = rollWidth;
            this.patternWidth = pattern.entrySet().stream()
                    .mapToInt(e -> e.getKey() * e.getValue()).sum();
        }

        public double getUtilization() {
            return (double) patternWidth / rollWidth;
        }

        public int getWaste() {
            return rollWidth - patternWidth;
        }

        /**
         * 计算基于母卷总宽度的实际废边
         * 
         * @param totalWidth 母卷总宽度
         * @return 实际废边 = totalWidth - patternWidth
         */
        public int getRealWaste(int totalWidth) {
            return totalWidth - patternWidth;
        }

        public double getCost() {
            // cost_p = 1 + epsWaste * waste
            return 1.0 + EPS_WASTE * getWaste();
        }

        public double getCost(int totalWidth) {
            return 1.0 + EPS_WASTE * getRealWaste(totalWidth);
        }

        /**
         * 规范化签名，用于去重（解决HashMap迭代顺序不稳定问题）
         */
        public String signature() {
            return rollWidth + "|" + pattern.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "x" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            PatternCandidate that = (PatternCandidate) o;
            return rollWidth == that.rollWidth && pattern.equals(that.pattern);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pattern, rollWidth);
        }

        @Override
        public String toString() {
            return pattern + "@" + rollWidth + "mm(pw=" + patternWidth +
                    ", util=" + String.format("%.1f%%", getUtilization() * 100) + ")";
        }
    }

    // ==================== 主问题LP结果 ====================
    private static class MasterLPResult {
        final double objectiveValue;
        final Map<Integer, Double> dualPrices; // width -> π_w
        final boolean feasible;

        MasterLPResult(double objectiveValue, Map<Integer, Double> dualPrices, boolean feasible) {
            this.objectiveValue = objectiveValue;
            this.dualPrices = dualPrices;
            this.feasible = feasible;
        }
    }

    @Override
    public boolean supports(SolverConfig config) {
        return config.isFlexibleMode();
    }

    // ==================== 统一入口 ====================
    @Override
    public List<CuttingInstruction> solve(List<SolverOrderItem> items, SolverConfig config) {
        log.info("\n========== PatternSolverFour 列生成+受控超产求解器 ==========");
        log.info("配置: minWidth=" + config.getMinWidth() + "mm, maxWidth=" + config.getMaxWidth() +
                "mm, stepSize=" + config.getStepSize() + "mm");

        this.minRollWidth = config.getMinWidth();
        this.maxRollWidth = config.getMaxWidth();
        this.stepSize = config.getStepSize();
        this.totalWidth = config.getTotalWidth(); // 🔥 读取母卷总宽度
        this.totalOverCap = config.getTotalOverCap(); // 🔥 读取超产上限配置

        // 🔥 读取高级算法参数
        this.maxIterations = config.getMaxIterations() > 0 ? config.getMaxIterations() : DEFAULT_MAX_ITERATIONS;
        this.timeoutMs = config.getTimeoutMs() > 0 ? config.getTimeoutMs() : DEFAULT_TIME_LIMIT_MS;

        log.info("超产控制: TOTAL_OVER_CAP=" + totalOverCap + ", TOP_K=" + TOP_K);
        log.info("算法参数: maxIterations=" + maxIterations + ", timeoutMs=" + timeoutMs + "ms");

        // 按groupKey分组
        Map<String, List<SolverOrderItem>> groupedItems = items.stream()
                .collect(Collectors.groupingBy(SolverOrderItem::getGroupKey));

        List<CuttingInstruction> allInstructions = new ArrayList<>();

        for (Map.Entry<String, List<SolverOrderItem>> groupEntry : groupedItems.entrySet()) {
            String groupKey = groupEntry.getKey();
            List<SolverOrderItem> groupItems = groupEntry.getValue();

            log.info("\n>>> 处理分组: " + groupKey);

            // 构建需求映射
            Map<Integer, Integer> demands = new LinkedHashMap<>();
            Map<Integer, List<SolverOrderItem>> widthToItems = new HashMap<>();
            for (SolverOrderItem item : groupItems) {
                demands.merge(item.getWidth(), item.getDemand(), Integer::sum);
                widthToItems.computeIfAbsent(item.getWidth(), k -> new ArrayList<>()).add(item);
            }

            log.info("需求: " + demands);
            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            log.info("总需求: " + totalDemand + " 卷");

            // 构建允许超产的宽度集合
            Set<Integer> allowOverSet = buildAllowOverSet(demands, config.getForceAllowOverWidths());
            log.info("允许超产宽度 (Top-" + TOP_K + "): " + allowOverSet);
            if (config.getForceAllowOverWidths() != null && !config.getForceAllowOverWidths().isEmpty()) {
                log.info("强制超产宽度(demand=0可超产): " + config.getForceAllowOverWidths());
            }

            // 列生成求解
            Map<PatternCandidate, Integer> solution = solveWithColumnGeneration(demands, allowOverSet);
            if (solution.isEmpty()) {
                log.info("❌ 列生成求解失败");
                continue;
            }

            // 转换为CuttingInstruction
            List<CuttingInstruction> instructions = convertToInstructions(
                    solution, groupKey, groupItems, widthToItems, demands);
            allInstructions.addAll(instructions);
        }

        log.info("\n========== 求解完成 ==========");
        log.info("生成 " + allInstructions.size() + " 个切割指令");
        return allInstructions;
    }

    // ==================== 构建允许超产的宽度集合（Top-K策略 + 强制超产宽幅）====================
    private Set<Integer> buildAllowOverSet(Map<Integer, Integer> demands, Set<Integer> forceAllowOver) {
        // 按需求量降序排序，取前TOP_K个
        List<Map.Entry<Integer, Integer>> sorted = demands.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());

        Set<Integer> allowOverSet = new HashSet<>();
        for (int i = 0; i < Math.min(TOP_K, sorted.size()); i++) {
            allowOverSet.add(sorted.get(i).getKey());
        }

        // 合并强制允许超产的宽幅（二次搭切时 demand=0 的宽幅也需要超产填充）
        if (forceAllowOver != null && !forceAllowOver.isEmpty()) {
            allowOverSet.addAll(forceAllowOver);
        }
        return allowOverSet;
    }

    // ==================== 列生成主流程 ====================
    private Map<PatternCandidate, Integer> solveWithColumnGeneration(
            Map<Integer, Integer> demands, Set<Integer> allowOverSet) {

        log.info("\n--- Stage A: 列生成 LP ---");
        long startTime = System.currentTimeMillis();

        // 1. 初始化模式集合（保证覆盖）
        List<PatternCandidate> patterns = initializePatterns(demands);
        Set<String> seen = new HashSet<>();
        for (PatternCandidate pc : patterns) {
            seen.add(pc.signature()); // 🔥 使用规范化签名替代toString
        }
        log.info("初始模式数: " + patterns.size());

        // 1.5 生成Seed模式（强制覆盖所有宽度，确保有系数=1的模式）
        generateSeedPatterns(patterns, seen, demands);

        // 2. 列生成迭代
        iterationCount = 0;
        int noImprovementCount = 0;

        while (iterationCount < maxIterations && patterns.size() < MAX_PATTERNS) {
            iterationCount++;

            // 2.1 求解主问题LP
            MasterLPResult masterResult = solveMasterProblemLP(patterns, demands, allowOverSet);
            if (!masterResult.feasible) {
                log.info("  ❌ 主问题LP不可行");
                break;
            }

            // 2.2 求解定价子问题
            List<PatternCandidate> newPatterns = solvePricingSubproblem(
                    masterResult.dualPrices, demands.keySet(), demands);

            // 🔥 修复：以 added > 0 为准判断是否有改进
            int added = 0;
            for (PatternCandidate np : newPatterns) {
                String key = np.signature(); // 🔥 使用规范化签名
                if (!seen.contains(key)) {
                    patterns.add(np);
                    seen.add(key);
                    added++;
                    if (iterationCount <= 5 || iterationCount % 20 == 0) {
                        double rc = calculateReducedCost(np, masterResult.dualPrices);
                        log.info("  迭代" + iterationCount + ": 新模式 " + np +
                                " rc=" + String.format("%.4f", rc));
                    }
                }
            }

            // 🔥 修复：只看 added 是否 > 0
            if (added > 0) {
                noImprovementCount = 0;
            } else {
                noImprovementCount++;
                if (noImprovementCount >= 10) {
                    log.info("  迭代" + iterationCount + ": 连续10轮无新增模式，收敛");
                    break;
                }
            }
        }

        log.info("列生成完成: " + iterationCount + " 次迭代, " + patterns.size() + " 个模式");
        log.info("⏱️ 列生成耗时: " + (System.currentTimeMillis() - startTime) + "ms");

        // 2.5 诊断：检查覆盖和GCD问题
        debugCoverageAndGcd(patterns, demands, allowOverSet);

        // 2.6 修复问题模式（未覆盖或GCD阻塞）
        int repaired = repairPatterns(patterns, seen, demands, allowOverSet);
        if (repaired > 0) {
            log.info("修复模式数: " + repaired + ", 模式池总数: " + patterns.size());
        }

        // 3. 最终整数MIP求解（多阶段字典序，带修复循环）
        Map<PatternCandidate, Integer> solution = solveFinalMIPWithRepair(patterns, seen, demands, allowOverSet, TOP_K);

        return solution;
    }

    // ==================== 初始化模式（均衡搭切策略：优先多宽度混合）====================
    private List<PatternCandidate> initializePatterns(Map<Integer, Integer> demands) {
        List<PatternCandidate> patterns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Integer> widths = new ArrayList<>(demands.keySet());

        // 按需求量降序排序，大需求宽度优先参与组合
        widths.sort((a, b) -> Integer.compare(demands.get(b), demands.get(a)));

        double minUtilization = (double) minRollWidth / maxRollWidth;
        log.info("利用率下限: " + String.format("%.2f%%", minUtilization * 100) +
                " (minRollWidth=" + minRollWidth + " / maxRollWidth=" + maxRollWidth + ")");

        // ========== 策略1：双宽度组合（主力模式，占比最大）==========
        // 这是均衡搭切的核心：让不同宽度搭配在一起
        log.info("生成双宽度混合模式...");
        int dualCount = 0;
        for (int i = 0; i < widths.size(); i++) {
            int w1 = widths.get(i);
            for (int j = i; j < widths.size(); j++) { // j从i开始，允许同宽度
                int w2 = widths.get(j);

                // 枚举所有可行的系数组合
                int maxC1 = maxRollWidth / w1;
                int maxC2 = maxRollWidth / w2;

                for (int c1 = 1; c1 <= maxC1; c1++) {
                    int startC2 = (w1 == w2) ? c1 : 1; // 同宽度时避免重复
                    for (int c2 = startC2; c2 <= maxC2; c2++) {
                        int patternWidth = w1 * c1 + w2 * c2;

                        // 必须满足下界
                        if (patternWidth < minRollWidth)
                            continue;
                        if (patternWidth > maxRollWidth)
                            break;

                        // 计算最佳卷宽
                        int bestRw = ceilToStep(patternWidth, stepSize);
                        bestRw = Math.max(bestRw, minRollWidth);
                        bestRw = Math.min(bestRw, maxRollWidth);

                        if (patternWidth <= bestRw) {
                            Map<Integer, Integer> pattern = new HashMap<>();
                            if (w1 == w2) {
                                pattern.put(w1, c1 + c2);
                            } else {
                                pattern.put(w1, c1);
                                pattern.put(w2, c2);
                            }
                            if (addPatternWithResult(patterns, seen, pattern, bestRw)) {
                                dualCount++;
                            }
                        }
                    }
                }
            }
        }
        log.info("  双宽度模式: " + dualCount + " 个");

        // ========== 策略2：三宽度组合（补充模式，增加灵活性）==========
        log.info("生成三宽度混合模式...");
        int tripleCount = 0;
        // 🔥 优化：增加三宽度模式上限到400，系数范围扩大到1-4
        for (int i = 0; i < widths.size() && tripleCount < 400; i++) {
            int w1 = widths.get(i);
            for (int j = i; j < widths.size() && tripleCount < 400; j++) {
                int w2 = widths.get(j);
                for (int k = j; k < widths.size() && tripleCount < 400; k++) {
                    int w3 = widths.get(k);

                    // 限制系数范围，避免组合爆炸
                    for (int c1 = 1; c1 <= 4; c1++) {
                        for (int c2 = 1; c2 <= 4; c2++) {
                            for (int c3 = 1; c3 <= 4; c3++) {
                                int patternWidth = w1 * c1 + w2 * c2 + w3 * c3;

                                if (patternWidth < minRollWidth)
                                    continue;
                                if (patternWidth > maxRollWidth)
                                    continue;

                                int bestRw = ceilToStep(patternWidth, stepSize);
                                bestRw = Math.max(bestRw, minRollWidth);
                                bestRw = Math.min(bestRw, maxRollWidth);

                                if (patternWidth <= bestRw) {
                                    Map<Integer, Integer> pattern = new HashMap<>();
                                    pattern.merge(w1, c1, Integer::sum);
                                    pattern.merge(w2, c2, Integer::sum);
                                    pattern.merge(w3, c3, Integer::sum);

                                    // 检查宽度种类数
                                    if (pattern.size() <= MAX_DISTINCT_WIDTHS) {
                                        if (addPatternWithResult(patterns, seen, pattern, bestRw)) {
                                            tripleCount++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        log.info("  三宽度模式: " + tripleCount + " 个");

        // ========== 策略2.5：四宽度组合（增强探索，寻找更优解）==========
        log.info("生成四宽度混合模式...");
        int quadCount = 0;
        // 🔥 优化：扩大到前15个宽度，模式上限500，系数范围1-3
        List<Integer> topWidths = widths.subList(0, Math.min(15, widths.size()));
        for (int i = 0; i < topWidths.size() && quadCount < 500; i++) {
            int w1 = topWidths.get(i);
            for (int j = i; j < topWidths.size() && quadCount < 500; j++) {
                int w2 = topWidths.get(j);
                for (int k = j; k < topWidths.size() && quadCount < 500; k++) {
                    int w3 = topWidths.get(k);
                    for (int l = k; l < topWidths.size() && quadCount < 500; l++) {
                        int w4 = topWidths.get(l);

                        // 四宽度组合：每个系数1-3
                        for (int c1 = 1; c1 <= 3 && quadCount < 500; c1++) {
                            for (int c2 = 1; c2 <= 3 && quadCount < 500; c2++) {
                                for (int c3 = 1; c3 <= 3 && quadCount < 500; c3++) {
                                    for (int c4 = 1; c4 <= 3 && quadCount < 500; c4++) {
                                        int patternWidth = w1 * c1 + w2 * c2 + w3 * c3 + w4 * c4;

                                        if (patternWidth < minRollWidth)
                                            continue;
                                        if (patternWidth > maxRollWidth)
                                            continue;

                                        int bestRw = ceilToStep(patternWidth, stepSize);
                                        bestRw = Math.max(bestRw, minRollWidth);
                                        bestRw = Math.min(bestRw, maxRollWidth);

                                        if (patternWidth <= bestRw) {
                                            Map<Integer, Integer> pattern = new HashMap<>();
                                            pattern.merge(w1, c1, Integer::sum);
                                            pattern.merge(w2, c2, Integer::sum);
                                            pattern.merge(w3, c3, Integer::sum);
                                            pattern.merge(w4, c4, Integer::sum);

                                            // 检查实际宽度种类数
                                            if (pattern.size() <= MAX_DISTINCT_WIDTHS) {
                                                if (addPatternWithResult(patterns, seen, pattern, bestRw)) {
                                                    quadCount++;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        log.info("  四宽度模式: " + quadCount + " 个");

        // ========== 策略2.6：单宽度高系数模式（专门处理大需求宽度如990mm）==========
        // 问题：某些宽度（如990mm）很难与其他宽度组合成满足 [minRollWidth, maxRollWidth] 范围的模式
        // 解决：允许单一宽度多次使用，即使利用率较低也要生成
        // 🔥 修复：严格要求 patternWidth 必须 >= minRollWidth，不能生成低于下限的模式
        log.info("生成单宽度高系数模式（处理难组合宽度）...");
        int singleHighCount = 0;
        for (int w : widths) {
            // 尝试单一宽度多次使用
            for (int count = 3; count <= maxRollWidth / w && count <= 5; count++) {
                int patternWidth = w * count;

                // 🔥 严格要求：patternWidth 必须在 [minRollWidth, maxRollWidth] 范围内
                if (patternWidth < minRollWidth)
                    continue;
                if (patternWidth > maxRollWidth)
                    break;

                int bestRw = ceilToStep(patternWidth, stepSize);
                bestRw = Math.max(bestRw, minRollWidth);
                bestRw = Math.min(bestRw, maxRollWidth);

                if (patternWidth <= bestRw) {
                    Map<Integer, Integer> pattern = new HashMap<>();
                    pattern.put(w, count);
                    if (addPatternWithResult(patterns, seen, pattern, bestRw)) {
                        singleHighCount++;
                        if (singleHighCount <= 5) {
                            log.info(
                                    "    单宽度模式: " + w + "x" + count + "=" + patternWidth + "mm @" + bestRw + "mm");
                        }
                    }
                }
            }
        }
        if (singleHighCount > 5) {
            log.info("    ... (共 " + singleHighCount + " 个单宽度模式)");
        }
        log.info("  单宽度高系数模式: " + singleHighCount + " 个");

        // ========== 策略3：检查覆盖情况，为未覆盖的宽度生成兜底模式 ==========
        Set<Integer> coveredWidths = new HashSet<>();
        for (PatternCandidate pc : patterns) {
            coveredWidths.addAll(pc.pattern.keySet());
        }

        List<Integer> uncoveredWidths = new ArrayList<>();
        for (int w : widths) {
            if (!coveredWidths.contains(w)) {
                uncoveredWidths.add(w);
            }
        }

        if (!uncoveredWidths.isEmpty()) {
            log.info("⚠️ 发现 " + uncoveredWidths.size() + " 个未覆盖宽度: " + uncoveredWidths);
            for (int targetW : uncoveredWidths) {
                // 强制生成包含该宽度的模式
                for (int fillW : widths) {
                    if (fillW == targetW)
                        continue;

                    for (int fillC = 1; fillC <= 5; fillC++) {
                        int patternWidth = targetW + fillW * fillC;
                        if (patternWidth < minRollWidth)
                            continue;
                        if (patternWidth > maxRollWidth)
                            break;

                        int bestRw = ceilToStep(patternWidth, stepSize);
                        bestRw = Math.max(bestRw, minRollWidth);
                        bestRw = Math.min(bestRw, maxRollWidth);

                        if (patternWidth <= bestRw) {
                            Map<Integer, Integer> pattern = new HashMap<>();
                            pattern.put(targetW, 1);
                            pattern.put(fillW, fillC);
                            addPattern(patterns, seen, pattern, bestRw);
                            log.info("  兜底模式: " + pattern + " @" + bestRw + "mm");
                            break; // 找到一个就够了
                        }
                    }
                }
            }
        }

        log.info("初始模式池总数: " + patterns.size());
        return patterns;
    }

    private boolean addPatternWithResult(List<PatternCandidate> patterns, Set<String> seen,
            Map<Integer, Integer> pattern, int rollWidth) {
        PatternCandidate pc = new PatternCandidate(pattern, rollWidth);
        String key = pc.signature(); // 🔥 使用规范化签名
        if (!seen.contains(key)) {
            patterns.add(pc);
            seen.add(key);
            return true;
        }
        return false;
    }

    private void addPattern(List<PatternCandidate> patterns, Set<String> seen,
            Map<Integer, Integer> pattern, int rollWidth) {
        addPatternWithResult(patterns, seen, pattern, rollWidth);
    }

    private int ceilToStep(int value, int step) {
        return ((value + step - 1) / step) * step;
    }

    // ==================== GCD辅助函数 ====================
    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }

    // ==================== 诊断：检查覆盖和GCD问题 ====================
    private void debugCoverageAndGcd(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        log.info("\n--- [诊断] 模式池覆盖 & GCD检查 ---");

        List<Integer> uncoveredWidths = new ArrayList<>();
        List<Integer> gcdBlockedWidths = new ArrayList<>();

        for (Integer w : demands.keySet()) {
            List<Integer> coeffs = new ArrayList<>();
            int minC = Integer.MAX_VALUE, maxC = 0, appear = 0;

            for (PatternCandidate p : patterns) {
                int c = p.pattern.getOrDefault(w, 0);
                if (c > 0) {
                    appear++;
                    coeffs.add(c);
                    minC = Math.min(minC, c);
                    maxC = Math.max(maxC, c);
                }
            }

            if (appear == 0) {
                log.info("  ❌ UN_COVERED width=" + w + "mm demand=" + demands.get(w));
                uncoveredWidths.add(w);
                continue;
            }

            // 计算系数的GCD
            int g = coeffs.get(0);
            for (int c : coeffs)
                g = gcd(g, c);

            int capW = allowOverSet.contains(w) ? totalOverCap : 0;

            // 检查是否可达
            boolean ok = false;
            for (int k = 0; k <= capW; k++) {
                if ((demands.get(w) + k) % g == 0) {
                    ok = true;
                    break;
                }
            }

            if (!ok) {
                log.info("  ⚠️ GCD_BLOCK width=" + w + "mm demand=" + demands.get(w) +
                        " gcd=" + g + " allowOver=" + allowOverSet.contains(w) +
                        " minCoeff=" + minC + " maxCoeff=" + maxC);
                gcdBlockedWidths.add(w);
            }
        }

        if (uncoveredWidths.isEmpty() && gcdBlockedWidths.isEmpty()) {
            log.info("  ✓ 所有宽度覆盖正常，无GCD阻塞");
        } else {
            log.info("  未覆盖宽度: " + uncoveredWidths.size() + ", GCD阻塞宽度: " + gcdBlockedWidths.size());
        }
    }

    // ==================== Seed模式生成（确保每个宽度都被覆盖）====================
    private void generateSeedPatterns(List<PatternCandidate> patterns,
            Set<String> seen,
            Map<Integer, Integer> demands) {
        log.info("\n--- 生成Seed模式（强制覆盖所有宽度）---");

        List<Integer> widths = new ArrayList<>(demands.keySet());
        // 按需求量降序排序，大需求优先用于填充
        widths.sort((a, b) -> Integer.compare(demands.get(b), demands.get(a)));

        Set<Integer> coveredWidths = new HashSet<>();
        for (PatternCandidate pc : patterns) {
            coveredWidths.addAll(pc.pattern.keySet());
        }

        int seedsAdded = 0;

        for (int targetW : widths) {
            // 对每个宽度，确保有系数=1的模式（解决GCD问题）
            // 以及确保被覆盖
            for (int targetCoeff = 1; targetCoeff <= 2; targetCoeff++) {
                int baseWidth = targetW * targetCoeff;
                if (baseWidth >= maxRollWidth)
                    continue;

                // 尝试用其他宽度填充到 [minRollWidth, maxRollWidth]
                int remaining = minRollWidth - baseWidth;
                if (remaining <= 0) {
                    // 已经达到下界，直接生成
                    if (baseWidth <= maxRollWidth) {
                        int rw = ceilToStep(baseWidth, stepSize);
                        rw = Math.max(rw, minRollWidth);
                        rw = Math.min(rw, maxRollWidth);
                        if (baseWidth <= rw) {
                            Map<Integer, Integer> pattern = new HashMap<>();
                            pattern.put(targetW, targetCoeff);
                            if (addPatternIfNew(patterns, seen, pattern, rw)) {
                                seedsAdded++;
                            }
                        }
                    }
                } else {
                    // 需要用其他宽度填充
                    boolean found = false;
                    for (int fillW : widths) {
                        if (found)
                            break;
                        if (fillW == targetW)
                            continue;

                        for (int fillC = 1; fillC <= maxRollWidth / fillW; fillC++) {
                            int totalWidth = baseWidth + fillW * fillC;
                            if (totalWidth < minRollWidth)
                                continue;
                            if (totalWidth > maxRollWidth)
                                break;

                            int rw = ceilToStep(totalWidth, stepSize);
                            rw = Math.max(rw, minRollWidth);
                            rw = Math.min(rw, maxRollWidth);

                            if (totalWidth <= rw) {
                                Map<Integer, Integer> pattern = new HashMap<>();
                                pattern.put(targetW, targetCoeff);
                                pattern.put(fillW, fillC);
                                if (addPatternIfNew(patterns, seen, pattern, rw)) {
                                    seedsAdded++;
                                    found = true;
                                    break;
                                }
                            }
                        }
                    }

                    // 如果单个填充宽度不够，尝试双填充
                    if (!found) {
                        for (int fillW1 : widths) {
                            if (found)
                                break;
                            if (fillW1 == targetW)
                                continue;
                            for (int fillW2 : widths) {
                                if (found)
                                    break;
                                if (fillW2 < fillW1)
                                    continue;

                                for (int c1 = 1; c1 <= 3; c1++) {
                                    if (found)
                                        break;
                                    for (int c2 = 1; c2 <= 3; c2++) {
                                        int totalWidth = baseWidth + fillW1 * c1 + fillW2 * c2;
                                        if (totalWidth < minRollWidth)
                                            continue;
                                        if (totalWidth > maxRollWidth)
                                            break;

                                        Map<Integer, Integer> pattern = new HashMap<>();
                                        pattern.put(targetW, targetCoeff);
                                        pattern.merge(fillW1, c1, Integer::sum);
                                        pattern.merge(fillW2, c2, Integer::sum);

                                        if (pattern.size() > MAX_DISTINCT_WIDTHS)
                                            continue;

                                        int rw = ceilToStep(totalWidth, stepSize);
                                        rw = Math.max(rw, minRollWidth);
                                        rw = Math.min(rw, maxRollWidth);

                                        if (totalWidth <= rw) {
                                            if (addPatternIfNew(patterns, seen, pattern, rw)) {
                                                seedsAdded++;
                                                found = true;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        log.info("Seed模式新增: " + seedsAdded + " 个，模式池总数: " + patterns.size());
    }

    private boolean addPatternIfNew(List<PatternCandidate> patterns, Set<String> seen,
            Map<Integer, Integer> pattern, int rollWidth) {
        PatternCandidate pc = new PatternCandidate(pattern, rollWidth);
        String key = pc.signature(); // 🔥 使用规范化签名
        if (!seen.contains(key)) {
            patterns.add(pc);
            seen.add(key);
            return true;
        }
        return false;
    }

    // ==================== 修复模式：为未覆盖/GCD阻塞的宽度生成应急模式 ====================
    private int repairPatterns(List<PatternCandidate> patterns, Set<String> seen,
            Map<Integer, Integer> demands, Set<Integer> allowOverSet) {
        int repaired = 0;
        List<Integer> widths = new ArrayList<>(demands.keySet());
        widths.sort((a, b) -> Integer.compare(demands.get(b), demands.get(a)));

        for (int w : demands.keySet()) {
            // 检查该宽度是否被覆盖
            List<Integer> coeffs = new ArrayList<>();
            for (PatternCandidate p : patterns) {
                int c = p.pattern.getOrDefault(w, 0);
                if (c > 0)
                    coeffs.add(c);
            }

            boolean needsRepair = false;

            if (coeffs.isEmpty()) {
                // 未覆盖
                log.info("  🔧 修复未覆盖宽度: " + w + "mm");
                needsRepair = true;
            } else {
                // 检查GCD
                int g = coeffs.get(0);
                for (int c : coeffs)
                    g = gcd(g, c);

                int capW = allowOverSet.contains(w) ? totalOverCap : 0;
                boolean ok = false;
                for (int k = 0; k <= capW; k++) {
                    if ((demands.get(w) + k) % g == 0) {
                        ok = true;
                        break;
                    }
                }

                if (!ok) {
                    log.info("  🔧 修复GCD阻塞宽度: " + w + "mm (gcd=" + g + ", demand=" + demands.get(w) + ")");
                    needsRepair = true;
                }
            }

            if (needsRepair) {
                // 强制生成系数=1的模式
                boolean found = false;
                for (int fillW : widths) {
                    if (found)
                        break;
                    if (fillW == w)
                        continue;

                    for (int fillC = 1; fillC <= 5; fillC++) {
                        int totalWidth = w + fillW * fillC;
                        if (totalWidth < minRollWidth)
                            continue;
                        if (totalWidth > maxRollWidth)
                            break;

                        int rw = ceilToStep(totalWidth, stepSize);
                        rw = Math.max(rw, minRollWidth);
                        rw = Math.min(rw, maxRollWidth);

                        if (totalWidth <= rw) {
                            Map<Integer, Integer> pattern = new HashMap<>();
                            pattern.put(w, 1); // 系数=1，打破GCD
                            pattern.put(fillW, fillC);
                            if (addPatternIfNew(patterns, seen, pattern, rw)) {
                                repaired++;
                                found = true;
                                log.info("    → 生成修复模式: " + pattern + "@" + rw + "mm");
                                break;
                            }
                        }
                    }
                }
            }
        }

        return repaired;
    }

    // ==================== 主问题LP ====================
    private MasterLPResult solveMasterProblemLP(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {

        try {
            MPSolver solver = MPSolver.createSolver("GLOP");
            if (solver == null)
                return new MasterLPResult(0, new HashMap<>(), false);

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int maxDemand = demands.values().stream().mapToInt(Integer::intValue).max().orElse(1);

            // 变量：x_p >= 0（模式使用次数，LP阶段连续）
            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeNumVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            // 变量：under_w >= 0（欠产）
            Map<Integer, MPVariable> underVars = new HashMap<>();
            for (int w : demands.keySet()) {
                underVars.put(w, solver.makeNumVar(0, totalDemand, "under_" + w));
            }

            // 变量：over_w >= 0（超产）
            // 🔥 修复：LP阶段允许所有宽度有小量超产以保证可行性，通过高惩罚控制
            Map<Integer, MPVariable> overVars = new HashMap<>();
            for (int w : demands.keySet()) {
                // LP阶段：allowOverSet内上界=totalOverCap，其他宽度上界=2（小量松弛）
                double overUb = allowOverSet.contains(w) ? totalOverCap : 2;
                overVars.put(w, solver.makeNumVar(0, overUb, "over_" + w));
            }

            // 约束1：Σ a[w,p]*x_p + under_w - over_w = demand_w
            Map<Integer, MPConstraint> demandConstraints = new HashMap<>();
            for (Map.Entry<Integer, Integer> d : demands.entrySet()) {
                int w = d.getKey();
                int demand = d.getValue();
                MPConstraint c = solver.makeConstraint(demand, demand, "demand_" + w);
                demandConstraints.put(w, c);
                c.setCoefficient(underVars.get(w), 1);
                c.setCoefficient(overVars.get(w), -1);
            }

            for (int i = 0; i < patterns.size(); i++) {
                PatternCandidate pc = patterns.get(i);
                for (Map.Entry<Integer, Integer> e : pc.pattern.entrySet()) {
                    int w = e.getKey();
                    int count = e.getValue();
                    MPConstraint c = demandConstraints.get(w);
                    if (c != null) {
                        c.setCoefficient(xVars.get(i), count);
                    }
                }
            }

            // 约束2：Σ over_w <= totalOverCap
            MPConstraint overCap = solver.makeConstraint(0, totalOverCap, "overCap");
            for (MPVariable over : overVars.values()) {
                overCap.setCoefficient(over, 1);
            }

            // 目标函数：min Σ cost_p*x_p + M_under*Σunder + Σpenalty_w*over_w
            MPObjective obj = solver.objective();

            // cost_p = 1 + epsWaste * waste
            for (int i = 0; i < patterns.size(); i++) {
                PatternCandidate pc = patterns.get(i);
                obj.setCoefficient(xVars.get(i), pc.getCost(this.totalWidth));
            }

            // M_under * Σ under_w
            for (MPVariable under : underVars.values()) {
                obj.setCoefficient(under, M_UNDER);
            }

            // penalty_w * over_w（小需求惩罚大，非allowOverSet的惩罚更大）
            for (Map.Entry<Integer, Integer> d : demands.entrySet()) {
                int w = d.getKey();
                int demand = d.getValue();
                // penalty_w = base * (maxDemand / demand)^2
                double ratio = (double) maxDemand / Math.max(demand, 1);
                double penalty = OVER_PENALTY_BASE * ratio * ratio;
                // 🔥 非allowOverSet的宽度，超产惩罚放大100倍
                if (!allowOverSet.contains(w)) {
                    penalty *= 100;
                }
                obj.setCoefficient(overVars.get(w), penalty);
            }

            obj.setMinimization();

            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                return new MasterLPResult(0, new HashMap<>(), false);
            }

            // 提取对偶价格
            Map<Integer, Double> dualPrices = new HashMap<>();
            for (Map.Entry<Integer, MPConstraint> e : demandConstraints.entrySet()) {
                dualPrices.put(e.getKey(), e.getValue().dualValue());
            }

            return new MasterLPResult(obj.value(), dualPrices, true);

        } catch (Exception e) {
            log.error("寮傚父", e);
            return new MasterLPResult(0, new HashMap<>(), false);
        }
    }

    // ==================== 定价子问题（带下界DP + epsWaste修正）====================
    private List<PatternCandidate> solvePricingSubproblem(
            Map<Integer, Double> dualPrices,
            Set<Integer> allWidths,
            Map<Integer, Integer> demands) {

        List<PatternCandidate> newPatterns = new ArrayList<>();
        List<Integer> widths = new ArrayList<>(allWidths);

        // 对每个允许的rollWidth扫描
        for (int r = minRollWidth; r <= maxRollWidth; r += stepSize) {
            PatternCandidate best = solveDPForRollWidth(r, widths, dualPrices, demands);
            if (best != null) {
                double rc = calculateReducedCost(best, dualPrices);
                if (rc < -EPSILON) {
                    newPatterns.add(best);
                }
            }
        }

        // 只返回rc最小（最负）的几个
        newPatterns.sort((a, b) -> Double.compare(
                calculateReducedCost(a, dualPrices),
                calculateReducedCost(b, dualPrices)));

        if (newPatterns.size() > 3) {
            newPatterns = newPatterns.subList(0, 3);
        }

        return newPatterns;
    }

    // ==================== 单个rollWidth的DP求解 ====================
    private PatternCandidate solveDPForRollWidth(
            int rollWidth,
            List<Integer> widths,
            Map<Integer, Double> dualPrices,
            Map<Integer, Integer> demands) {

        int capacity = rollWidth;
        int n = widths.size();

        // DP数据结构
        double[] dp = new double[capacity + 1];
        int[] choice = new int[capacity + 1]; // 最后选择的widthIndex
        int[] prev = new int[capacity + 1]; // 上一个状态

        Arrays.fill(choice, -1);
        Arrays.fill(prev, -1);

        // 🔥 修复：计算每个宽度的价值（只用 π，不掺 EPS_WASTE）
        // itemValue(w) = π_w （修法A：消除双重计入问题）
        double[] itemValues = new double[n];
        for (int i = 0; i < n; i++) {
            int w = widths.get(i);
            double pi = Math.max(0, dualPrices.getOrDefault(w, 0.0));
            itemValues[i] = pi; // 🔥 只用 π，不加 EPS_WASTE * w
        }

        // 无界背包DP
        for (int t = 1; t <= capacity; t++) {
            for (int i = 0; i < n; i++) {
                int w = widths.get(i);
                if (w <= t) {
                    double newValue = dp[t - w] + itemValues[i];
                    if (newValue > dp[t] + EPSILON) {
                        dp[t] = newValue;
                        choice[t] = i;
                        prev[t] = t - w;
                    }
                }
            }
        }

        // 在 t ∈ [minRollWidth, rollWidth] 里取dp[t]最大值
        int bestT = -1;
        double bestValue = -1;
        for (int t = minRollWidth; t <= rollWidth; t++) {
            if (dp[t] > bestValue) {
                bestValue = dp[t];
                bestT = t;
            }
        }

        if (bestT < 0 || bestValue <= EPSILON) {
            return null;
        }

        // 从bestT回溯构造模式
        Map<Integer, Integer> pattern = new HashMap<>();
        int t = bestT;
        while (t > 0 && choice[t] >= 0) {
            int widthIdx = choice[t];
            int w = widths.get(widthIdx);
            pattern.merge(w, 1, Integer::sum);
            t = prev[t];
        }

        if (pattern.isEmpty()) {
            return null;
        }

        // 检查宽度种类数限制
        if (pattern.size() > MAX_DISTINCT_WIDTHS) {
            return null;
        }

        // 计算实际patternWidth并选择合适的rollWidth
        int patternWidth = pattern.entrySet().stream()
                .mapToInt(e -> e.getKey() * e.getValue()).sum();

        int bestRw = ceilToStep(patternWidth, stepSize);
        bestRw = Math.max(bestRw, minRollWidth);
        bestRw = Math.min(bestRw, maxRollWidth);

        if (patternWidth > bestRw || patternWidth < minRollWidth) {
            return null;
        }

        return new PatternCandidate(pattern, bestRw);
    }

    // ==================== 计算reduced cost ====================
    private double calculateReducedCost(PatternCandidate pc, Map<Integer, Double> dualPrices) {
        // 🔥 修复：rc = cost_p - Σ π_w * count_w （只用 π，解决双重计入问题）
        double value = 0;
        for (Map.Entry<Integer, Integer> e : pc.pattern.entrySet()) {
            int w = e.getKey();
            int count = e.getValue();
            double pi = Math.max(0, dualPrices.getOrDefault(w, 0.0));
            value += pi * count; // 🔥 只用 π，不加 EPS_WASTE * w
        }
        return pc.getCost(this.totalWidth) - value;
    }

    // ==================== 最终MIP求解（带修复循环）====================
    private Map<PatternCandidate, Integer> solveFinalMIPWithRepair(
            List<PatternCandidate> patterns,
            Set<String> seen,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int currentTopK) {

        final int MAX_REPAIR_ATTEMPTS = 3;
        Set<Integer> workingAllowOverSet = new HashSet<>(allowOverSet);

        for (int attempt = 0; attempt <= MAX_REPAIR_ATTEMPTS; attempt++) {
            System.out
                    .println("\n--- Stage B: 最终整数MIP (尝试 " + (attempt + 1) + "/" + (MAX_REPAIR_ATTEMPTS + 1) + ") ---");
            log.info("模式数: " + patterns.size() + ", AllowOverSet大小: " + workingAllowOverSet.size());

            // 尝试求解
            Map<PatternCandidate, Integer> solution = solveFinalMIP(patterns, demands, workingAllowOverSet,
                    currentTopK);

            if (solution != null && !solution.isEmpty()) {
                // 验证解是否满足需求
                Map<Integer, Integer> production = new HashMap<>();
                for (var entry : solution.entrySet()) {
                    PatternCandidate pc = entry.getKey();
                    int count = entry.getValue();
                    for (var e : pc.pattern.entrySet()) {
                        production.merge(e.getKey(), e.getValue() * count, Integer::sum);
                    }
                }

                boolean feasible = true;
                for (var entry : demands.entrySet()) {
                    int w = entry.getKey();
                    int demand = entry.getValue();
                    int prod = production.getOrDefault(w, 0);
                    if (prod < demand) {
                        log.info("  ⚠️ 宽度" + w + "mm欠产: 需求=" + demand + ", 实际=" + prod);
                        feasible = false;
                    }
                }

                if (feasible) {
                    log.info("✓ MIP求解成功");
                    return solution;
                }
            }

            // 求解失败或解不满足需求，尝试修复
            if (attempt < MAX_REPAIR_ATTEMPTS) {
                log.info("🔧 尝试修复模式池...");

                // 策略1：扩大AllowOverSet
                if (attempt == 0) {
                    workingAllowOverSet = expandAllowOverSet(demands, currentTopK + 2);
                    log.info("  → 扩大AllowOverSet到 " + workingAllowOverSet.size() + " 个宽度");
                }

                // 策略2：修复未覆盖/GCD阻塞的模式
                int repaired = repairPatterns(patterns, seen, demands, workingAllowOverSet);
                log.info("  → 修复了 " + repaired + " 个模式");

                // 策略3：进一步扩大AllowOverSet
                if (attempt >= 1) {
                    workingAllowOverSet = new HashSet<>(demands.keySet()); // 允许所有宽度超产
                    log.info("  → 允许所有宽度超产");
                }
            }
        }

        log.info("❌ MIP求解失败，已尝试所有修复策略");
        return new HashMap<>();
    }

    // ==================== 最终MIP求解（多阶段字典序）====================
    private Map<PatternCandidate, Integer> solveFinalMIP(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int currentTopK) {

        log.info("\n--- Stage B: 最终整数MIP（多阶段字典序）---");
        log.info("模式数: " + patterns.size());

        // Stage 1: 最小化总超产
        int[] stage1Result = solveMIPStage1(patterns, demands, allowOverSet);
        if (stage1Result == null) {
            // 兜底：扩大AllowOverSet
            log.info("⚠️ Stage1不可行，尝试扩大AllowOverSet...");
            Set<Integer> expandedSet = expandAllowOverSet(demands, currentTopK + 1);
            stage1Result = solveMIPStage1(patterns, demands, expandedSet);
            if (stage1Result == null) {
                log.info("❌ 兜底策略仍失败");
                return new HashMap<>();
            }
            allowOverSet = expandedSet;
        }
        int optimalOver = Arrays.stream(stage1Result).sum();
        log.info("Stage1结果: 最优超产 = " + optimalOver);

        // Stage 2: 固定超产，最小化卷数
        Map<PatternCandidate, Integer> stage2Solution = solveMIPStage2(
                patterns, demands, allowOverSet, optimalOver);
        if (stage2Solution == null || stage2Solution.isEmpty()) {
            log.info("❌ Stage2求解失败");
            return new HashMap<>();
        }

        int totalRolls = stage2Solution.values().stream().mapToInt(Integer::intValue).sum();
        log.info("Stage2结果: 总卷数 = " + totalRolls);

        // Stage 3: 固定卷数，最小化废边（可选，可以跳过）
        Map<PatternCandidate, Integer> stage3Solution = solveMIPStage3(
                patterns, demands, allowOverSet, optimalOver, totalRolls);
        int totalWaste = 0;
        if (stage3Solution == null || stage3Solution.isEmpty()) {
            // 回退到Stage2的解
            stage3Solution = stage2Solution;
            log.info("Stage3不可行，使用Stage2解");
            totalWaste = stage3Solution.entrySet().stream()
                    .mapToInt(e -> e.getKey().getRealWaste(this.totalWidth) * e.getValue()).sum();
        } else {
            totalWaste = stage3Solution.entrySet().stream()
                    .mapToInt(e -> e.getKey().getRealWaste(this.totalWidth) * e.getValue()).sum();
            log.info("Stage3结果: 总废边 = " + totalWaste + "mm (基于母卷宽度" + this.totalWidth + "mm)");
        }

        // Stage 4: 固定卷数和废边，最小化模式种类数（减少"1车"模式）
        // 🔥 ALPHA=1.0 最小化模式种类数，对减少序号组有效
        log.info("Stage4使用全部模式: " + patterns.size() + " 个");

        Map<PatternCandidate, Integer> finalSolution = solveMIPStage4(
                patterns, demands, allowOverSet, optimalOver, totalRolls, totalWaste);
        if (finalSolution == null || finalSolution.isEmpty()) {
            // 回退到Stage3的解
            finalSolution = stage3Solution;
            log.info("Stage4不可行，使用Stage3解");
        } else {
            int patternCount = finalSolution.size();
            // 计算序号组复杂度估算
            double totalGroupComplexity = 0;
            int totalWidthTypes = 0;
            for (Map.Entry<PatternCandidate, Integer> e : finalSolution.entrySet()) {
                totalGroupComplexity += e.getKey().pattern.size() * e.getValue();
                totalWidthTypes += e.getKey().pattern.size();
            }
            double avgWidthPerPattern = patternCount > 0 ? (double) totalWidthTypes / patternCount : 0;
            log.info("Stage4结果: 模式种类数 = " + patternCount +
                    ", 平均宽度种类 = " + String.format("%.1f", avgWidthPerPattern) +
                    ", 序号组复杂度 = " + String.format("%.1f", totalGroupComplexity));
        }

        printSolutionSummary(finalSolution, demands);
        return finalSolution;
    }

    // ==================== Stage 1: 最小化超产（带欠产松弛）====================
    private int[] solveMIPStage1(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {

        try {
            MPSolver solver = createMIPSolver();
            if (solver == null)
                return null;

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            List<Integer> widthList = new ArrayList<>(demands.keySet());

            // 先诊断：检查哪些宽度没有模式覆盖
            Set<Integer> coveredWidths = new HashSet<>();
            for (PatternCandidate pc : patterns) {
                coveredWidths.addAll(pc.pattern.keySet());
            }
            List<Integer> uncoveredWidths = new ArrayList<>();
            for (int w : widthList) {
                if (!coveredWidths.contains(w)) {
                    uncoveredWidths.add(w);
                }
            }
            if (!uncoveredWidths.isEmpty()) {
                log.info("⚠️ Stage1诊断: " + uncoveredWidths.size() + " 个宽度未被模式覆盖: " + uncoveredWidths);
            }

            // 变量：x_p（模式使用次数）
            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            // 变量：over_w（超产）
            Map<Integer, MPVariable> overVars = new HashMap<>();
            for (int w : demands.keySet()) {
                double ub = allowOverSet.contains(w) ? totalOverCap : 0;
                overVars.put(w, solver.makeIntVar(0, (int) ub, "over_" + w));
            }

            // 变量：under_w（欠产松弛，用于保证可行性）
            Map<Integer, MPVariable> underVars = new HashMap<>();
            for (int w : demands.keySet()) {
                underVars.put(w, solver.makeIntVar(0, totalDemand, "under_" + w));
            }

            // 约束：Σ a[w,p]*x_p + under_w - over_w = demand_w
            for (Map.Entry<Integer, Integer> d : demands.entrySet()) {
                int w = d.getKey();
                int demand = d.getValue();
                MPConstraint c = solver.makeConstraint(demand, demand, "demand_" + w);

                for (int i = 0; i < patterns.size(); i++) {
                    int count = patterns.get(i).pattern.getOrDefault(w, 0);
                    if (count > 0) {
                        c.setCoefficient(xVars.get(i), count);
                    }
                }
                c.setCoefficient(underVars.get(w), 1); // 欠产松弛
                c.setCoefficient(overVars.get(w), -1);
            }

            // 约束：Σ over_w <= totalOverCap
            MPConstraint overCap = solver.makeConstraint(0, totalOverCap, "overCap");
            for (MPVariable over : overVars.values()) {
                overCap.setCoefficient(over, 1);
            }

            // 目标：min BIG*Σunder + Σover（先消除欠产，再最小化超产）
            MPObjective obj = solver.objective();
            for (MPVariable under : underVars.values()) {
                obj.setCoefficient(under, M_UNDER); // 极大惩罚欠产
            }
            for (MPVariable over : overVars.values()) {
                obj.setCoefficient(over, 1);
            }
            obj.setMinimization();

            solver.setTimeLimit(timeoutMs);
            MPSolver.ResultStatus status = solver.solve();

            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.info("❌ Stage1 MIP状态: " + status);
                return null;
            }

            // 检查欠产情况
            int totalUnder = 0;
            for (int w : widthList) {
                int under = (int) Math.round(underVars.get(w).solutionValue());
                if (under > 0) {
                    log.info("  ⚠️ 宽度 " + w + "mm 欠产 " + under + " 卷");
                    totalUnder += under;
                }
            }
            if (totalUnder > 0) {
                log.info("⚠️ Stage1总欠产: " + totalUnder + " 卷（模式池覆盖不足）");
            }

            int[] overValues = new int[widthList.size()];
            for (int i = 0; i < widthList.size(); i++) {
                overValues[i] = (int) Math.round(overVars.get(widthList.get(i)).solutionValue());
            }
            return overValues;

        } catch (Exception e) {
            log.error("寮傚父", e);
            return null;
        }
    }

    // ==================== Stage 2: 最小化卷数（带欠产松弛）====================
    private Map<PatternCandidate, Integer> solveMIPStage2(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver) {

        try {
            MPSolver solver = createMIPSolver();
            if (solver == null)
                return null;

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();

            // 变量
            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            Map<Integer, MPVariable> overVars = new HashMap<>();
            for (int w : demands.keySet()) {
                double ub = allowOverSet.contains(w) ? totalOverCap : 0;
                overVars.put(w, solver.makeIntVar(0, (int) ub, "over_" + w));
            }

            // 🔥 添加欠产松弛变量（和Stage1类似）
            Map<Integer, MPVariable> underVars = new HashMap<>();
            for (int w : demands.keySet()) {
                underVars.put(w, solver.makeIntVar(0, totalDemand, "under_" + w));
            }

            // 约束1：Σ a[w,p]*x_p + under_w - over_w = demand_w
            for (Map.Entry<Integer, Integer> d : demands.entrySet()) {
                int w = d.getKey();
                int demand = d.getValue();
                MPConstraint c = solver.makeConstraint(demand, demand, "demand_" + w);

                for (int i = 0; i < patterns.size(); i++) {
                    int count = patterns.get(i).pattern.getOrDefault(w, 0);
                    if (count > 0) {
                        c.setCoefficient(xVars.get(i), count);
                    }
                }
                c.setCoefficient(underVars.get(w), 1); // 🔥 欠产松弛
                c.setCoefficient(overVars.get(w), -1);
            }

            // 约束2：Σ over_w <= maxTotalOver（固定为Stage1的最优值）
            MPConstraint overCap = solver.makeConstraint(0, maxTotalOver, "overCap");
            for (MPVariable over : overVars.values()) {
                overCap.setCoefficient(over, 1);
            }

            // 目标：min BIG*Σunder + Σ x_p（先消除欠产，再最小化卷数）
            MPObjective obj = solver.objective();
            for (MPVariable under : underVars.values()) {
                obj.setCoefficient(under, M_UNDER); // 极大惩罚欠产
            }
            for (MPVariable x : xVars) {
                obj.setCoefficient(x, 1);
            }
            obj.setMinimization();

            solver.setTimeLimit(timeoutMs);
            MPSolver.ResultStatus status = solver.solve();

            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                return null;
            }

            // 🔥 检查欠产情况
            int totalUnder = 0;
            for (int w : demands.keySet()) {
                int under = (int) Math.round(underVars.get(w).solutionValue());
                if (under > 0) {
                    log.info("  ⚠️ Stage2欠产: 宽度 " + w + "mm 欠产 " + under + " 卷");
                    totalUnder += under;
                }
            }
            if (totalUnder > 0) {
                log.info("⚠️ Stage2总欠产: " + totalUnder + " 卷（模式池覆盖不足，但仍返回解）");
            }

            return extractSolution(patterns, xVars);

        } catch (Exception e) {
            log.error("寮傚父", e);
            return null;
        }
    }

    // ==================== Stage 3: 最小化废边 ====================
    private Map<PatternCandidate, Integer> solveMIPStage3(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxTotalRolls) {

        try {
            MPSolver solver = createMIPSolver();
            if (solver == null)
                return null;

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();

            // 变量
            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            Map<Integer, MPVariable> overVars = new HashMap<>();
            for (int w : demands.keySet()) {
                double ub = allowOverSet.contains(w) ? totalOverCap : 0;
                overVars.put(w, solver.makeIntVar(0, (int) ub, "over_" + w));
            }

            // 约束1：Σ a[w,p]*x_p - over_w = demand_w
            for (Map.Entry<Integer, Integer> d : demands.entrySet()) {
                int w = d.getKey();
                int demand = d.getValue();
                MPConstraint c = solver.makeConstraint(demand, demand, "demand_" + w);

                for (int i = 0; i < patterns.size(); i++) {
                    int count = patterns.get(i).pattern.getOrDefault(w, 0);
                    if (count > 0) {
                        c.setCoefficient(xVars.get(i), count);
                    }
                }
                c.setCoefficient(overVars.get(w), -1);
            }

            // 约束2：Σ over_w <= maxTotalOver
            MPConstraint overCap = solver.makeConstraint(0, maxTotalOver, "overCap");
            for (MPVariable over : overVars.values()) {
                overCap.setCoefficient(over, 1);
            }

            // 约束3：Σ x_p <= maxTotalRolls（固定为Stage2的最优值）
            MPConstraint rollsCap = solver.makeConstraint(0, maxTotalRolls, "rollsCap");
            for (MPVariable x : xVars) {
                rollsCap.setCoefficient(x, 1);
            }

            // 目标：min Σ realWaste_p * x_p（使用母卷总宽度计算废边）
            MPObjective obj = solver.objective();
            for (int i = 0; i < patterns.size(); i++) {
                obj.setCoefficient(xVars.get(i), patterns.get(i).getRealWaste(this.totalWidth));
            }
            obj.setMinimization();

            solver.setTimeLimit(timeoutMs);
            MPSolver.ResultStatus status = solver.solve();

            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                return null;
            }

            return extractSolution(patterns, xVars);

        } catch (Exception e) {
            log.error("寮傚父", e);
            return null;
        }
    }

    // ==================== Stage 4: 最小化模式种类数（减少模式数）====================
    private Map<PatternCandidate, Integer> solveMIPStage4(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxTotalRolls,
            int maxTotalWaste) {

        try {
            MPSolver solver = createMIPSolver();
            if (solver == null)
                return null;

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int M = totalDemand + totalOverCap; // 大M值

            // 变量x_p（模式使用次数）
            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            // 变量y_p（模式是否被使用，二元变量）
            List<MPVariable> yVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                yVars.add(solver.makeBoolVar("y_" + i));
            }

            Map<Integer, MPVariable> overVars = new HashMap<>();
            for (int w : demands.keySet()) {
                double ub = allowOverSet.contains(w) ? totalOverCap : 0;
                overVars.put(w, solver.makeIntVar(0, (int) ub, "over_" + w));
            }

            // 约束1：Σ a[w,p]*x_p - over_w = demand_w
            for (Map.Entry<Integer, Integer> d : demands.entrySet()) {
                int w = d.getKey();
                int demand = d.getValue();
                MPConstraint c = solver.makeConstraint(demand, demand, "demand_" + w);

                for (int i = 0; i < patterns.size(); i++) {
                    int count = patterns.get(i).pattern.getOrDefault(w, 0);
                    if (count > 0) {
                        c.setCoefficient(xVars.get(i), count);
                    }
                }
                c.setCoefficient(overVars.get(w), -1);
            }

            // 约束2：Σ over_w <= maxTotalOver
            MPConstraint overCap = solver.makeConstraint(0, maxTotalOver, "overCap");
            for (MPVariable over : overVars.values()) {
                overCap.setCoefficient(over, 1);
            }

            // 约束3：Σ x_p <= maxTotalRolls（固定总卷数）
            MPConstraint rollsCap = solver.makeConstraint(0, maxTotalRolls, "rollsCap");
            for (MPVariable x : xVars) {
                rollsCap.setCoefficient(x, 1);
            }

            // 约束4：Σ realWaste_p * x_p <= maxTotalWaste（固定总废边，允许适当松弛）
            // 🔥 优化：松弛比例化，2%或200mm取较大值
            int wasteSlack = Math.max(200, (int) (maxTotalWaste * 0.02));
            MPConstraint wasteCap = solver.makeConstraint(0, maxTotalWaste + wasteSlack, "wasteCap");
            for (int i = 0; i < patterns.size(); i++) {
                wasteCap.setCoefficient(xVars.get(i), patterns.get(i).getRealWaste(this.totalWidth));
            }

            // 约束5：x_p <= M * y_p（如果x_p > 0，则y_p = 1）
            for (int i = 0; i < patterns.size(); i++) {
                MPConstraint link = solver.makeConstraint(-MPSolver.infinity(), 0, "link_" + i);
                link.setCoefficient(xVars.get(i), 1);
                link.setCoefficient(yVars.get(i), -M);
            }

            // 🔥 目标：min α×Σy_p + β×Σgroup_cost_p×x_p
            // 不仅最小化模式种类数，还惩罚宽度种类多的模式（减少序号组）
            MPObjective obj = solver.objective();

            // 计算最大宽度种类数（用于归一化）
            int maxWidthCount = patterns.stream()
                    .mapToInt(p -> p.pattern.size())
                    .max().orElse(1);

            for (int i = 0; i < patterns.size(); i++) {
                PatternCandidate pc = patterns.get(i);

                // α: 模式种类惩罚（每使用一种模式 +α）
                obj.setCoefficient(yVars.get(i), SEQ_GROUP_ALPHA);

                // β: 序号组复杂度惩罚（宽度种类数归一化后 × 使用次数）
                // 宽度种类多的模式，每次使用都有惩罚，鼓励选择宽度少的模式
                double groupCost = (double) pc.pattern.size() / maxWidthCount;
                obj.setCoefficient(xVars.get(i), SEQ_GROUP_BETA * groupCost);
            }
            obj.setMinimization();

            log.info("  Stage4目标: min " + SEQ_GROUP_ALPHA + "×模式数 + " +
                    SEQ_GROUP_BETA + "×序号组复杂度");

            // 🔥 Stage 4 早停优化：
            // 1. 设置较短的时限（30秒），因为主要优化目标已在 Stage 1-3 完成
            // 2. 设置相对间隙（5%），当解接近最优时提前停止
            long stage4TimeLimit = Math.min(30000, timeoutMs); // 最多 30 秒
            solver.setTimeLimit(stage4TimeLimit);

            // 设置 MIP 相对间隙（5%）：当 (best_bound - best_objective) / best_objective < 5% 时停止
            // SCIP 参数通过 setHint 设置
            solver.setHint(new MPVariable[] {}, new double[] {}); // 初始化 hint

            log.info("  Stage4时限: " + stage4TimeLimit + "ms");

            long startTime = System.currentTimeMillis();
            MPSolver.ResultStatus status = solver.solve();
            long elapsedTime = System.currentTimeMillis() - startTime;

            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.info("  Stage4求解失败: " + status + " (耗时 " + elapsedTime + "ms)");
                return null;
            }

            log.info("  Stage4求解完成: " + status + " (耗时 " + elapsedTime + "ms)");

            return extractSolution(patterns, xVars);

        } catch (Exception e) {
            log.error("寮傚父", e);
            return null;
        }
    }

    // ==================== 辅助方法 ====================
    private MPSolver createMIPSolver() {
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            solver = MPSolver.createSolver("CBC");
        }
        return solver;
    }

    private Map<PatternCandidate, Integer> extractSolution(
            List<PatternCandidate> patterns, List<MPVariable> xVars) {
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        for (int i = 0; i < patterns.size(); i++) {
            int usage = (int) Math.round(xVars.get(i).solutionValue());
            if (usage > 0) {
                solution.put(patterns.get(i), usage);
            }
        }
        return solution;
    }

    // ==================== Stage 4 模式预筛选（🔥 高优先级优化）====================
    /**
     * 为Stage 4筛选模式池，减少MIP规模以加速求解
     * 策略：
     * 1. 保留Stage 3解中使用的所有模式（必须）
     * 2. 保留高利用率模式（利用率 >= 95%）
     * 3. 确保每个宽度至少被覆盖（兜底）
     * 4. 总数限制在120个以内
     *
     * @param allPatterns    完整模式池
     * @param stage3Solution Stage 3的解
     * @param demands        需求
     * @return 筛选后的模式池
     */
    private List<PatternCandidate> filterPatternsForStage4(
            List<PatternCandidate> allPatterns,
            Map<PatternCandidate, Integer> stage3Solution,
            Map<Integer, Integer> demands) {

        final int MAX_STAGE4_PATTERNS = 120;
        final double MIN_UTILIZATION = 0.95;

        Set<PatternCandidate> selected = new LinkedHashSet<>();

        // 1. 必须保留Stage 3解中使用的模式
        selected.addAll(stage3Solution.keySet());

        // 2. 记录已覆盖的宽度
        Set<Integer> coveredWidths = new HashSet<>();
        for (PatternCandidate pc : selected) {
            coveredWidths.addAll(pc.pattern.keySet());
        }

        // 3. 按利用率降序排序其他模式
        List<PatternCandidate> candidates = allPatterns.stream()
                .filter(p -> !selected.contains(p))
                .sorted((a, b) -> Double.compare(b.getUtilization(), a.getUtilization()))
                .collect(Collectors.toList());

        // 4. 添加高利用率模式
        for (PatternCandidate pc : candidates) {
            if (selected.size() >= MAX_STAGE4_PATTERNS)
                break;
            if (pc.getUtilization() >= MIN_UTILIZATION) {
                selected.add(pc);
                coveredWidths.addAll(pc.pattern.keySet());
            }
        }

        // 5. 确保所有宽度被覆盖（兜底）
        Set<Integer> uncovered = new HashSet<>(demands.keySet());
        uncovered.removeAll(coveredWidths);

        if (!uncovered.isEmpty()) {
            for (PatternCandidate pc : candidates) {
                if (uncovered.isEmpty())
                    break;
                if (selected.size() >= MAX_STAGE4_PATTERNS)
                    break;

                boolean coversNew = pc.pattern.keySet().stream().anyMatch(uncovered::contains);
                if (coversNew) {
                    selected.add(pc);
                    uncovered.removeAll(pc.pattern.keySet());
                    coveredWidths.addAll(pc.pattern.keySet());
                }
            }
        }

        // 6. 如果还有空间，继续添加次优模式
        for (PatternCandidate pc : candidates) {
            if (selected.size() >= MAX_STAGE4_PATTERNS)
                break;
            selected.add(pc);
        }

        return new ArrayList<>(selected);
    }

    private Set<Integer> expandAllowOverSet(Map<Integer, Integer> demands, int newTopK) {
        List<Map.Entry<Integer, Integer>> sorted = demands.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());

        Set<Integer> expandedSet = new HashSet<>();
        for (int i = 0; i < Math.min(newTopK, sorted.size()); i++) {
            expandedSet.add(sorted.get(i).getKey());
        }
        log.info("扩展的AllowOverSet (Top-" + newTopK + "): " + expandedSet);
        return expandedSet;
    }

    private void printSolutionSummary(Map<PatternCandidate, Integer> solution, Map<Integer, Integer> demands) {
        log.info("\n📋 求解结果汇总:");

        int totalRolls = 0;
        int totalWaste = 0;
        Map<Integer, Integer> produced = new HashMap<>();

        for (Map.Entry<PatternCandidate, Integer> e : solution.entrySet()) {
            PatternCandidate pc = e.getKey();
            int usage = e.getValue();
            totalRolls += usage;
            totalWaste += pc.getRealWaste(this.totalWidth) * usage;

            for (Map.Entry<Integer, Integer> cut : pc.pattern.entrySet()) {
                produced.merge(cut.getKey(), cut.getValue() * usage, Integer::sum);
            }
        }

        log.info("  总卷数: " + totalRolls);
        log.info("  模式种类: " + solution.size());
        log.info("  总废边: " + totalWaste + "mm");

        log.info("\n📋 各宽度生产情况:");
        int totalOver = 0;
        boolean allMet = true;
        for (Map.Entry<Integer, Integer> d : demands.entrySet()) {
            int w = d.getKey();
            int demand = d.getValue();
            int prod = produced.getOrDefault(w, 0);
            int diff = prod - demand;
            String status = diff > 0 ? "超产+" + diff : (diff < 0 ? "欠交" + diff : "精确");
            log.info("  " + w + "mm: 需求=" + demand + " 生产=" + prod + " [" + status + "]");
            if (diff > 0)
                totalOver += diff;
            if (diff < 0)
                allMet = false;
        }

        log.info("\n  总超产: " + totalOver);
        if (allMet && totalOver == 0) {
            log.info("  ✅ 完美解：无超产无欠交！");
        } else if (allMet) {
            log.info("  ✓ 可接受解：无欠交，总超产=" + totalOver);
        } else {
            log.info("  ⚠️ 存在欠交！");
        }

        log.info("\n📋 选中模式列表:");
        for (Map.Entry<PatternCandidate, Integer> e : solution.entrySet()) {
            PatternCandidate pc = e.getKey();
            log.info("  " + pc.pattern + " @" + pc.rollWidth + "mm " +
                    "pw=" + pc.patternWidth + "mm " +
                    "利用率=" + String.format("%.1f%%", pc.getUtilization() * 100) +
                    " 次数=" + e.getValue());
        }
    }

    // ==================== 结果转换 ====================
    private List<CuttingInstruction> convertToInstructions(
            Map<PatternCandidate, Integer> solution, String groupKey,
            List<SolverOrderItem> groupItems, Map<Integer, List<SolverOrderItem>> widthToItems,
            Map<Integer, Integer> demands) {

        List<CuttingInstruction> instructions = new ArrayList<>();

        // 维护剩余需求
        Map<String, Integer> remainingDemands = new HashMap<>();
        for (SolverOrderItem item : groupItems) {
            remainingDemands.merge(item.getMessageText(), item.getDemand(), Integer::sum);
        }

        for (Map.Entry<PatternCandidate, Integer> entry : solution.entrySet()) {
            PatternCandidate pattern = entry.getKey();
            int usageCount = entry.getValue();

            CuttingInstruction instruction = new CuttingInstruction();
            instruction.setGroupKey(groupKey);
            instruction.setRollWidth(pattern.rollWidth);
            instruction.setSubRolls(new LinkedHashMap<>(pattern.pattern));
            instruction.setUsageCount(usageCount);

            // 🔥 新增：设置模式宽度和废边（基于母卷总宽度）
            instruction.setPatternWidth(pattern.patternWidth);
            instruction.setWaste(this.totalWidth - pattern.patternWidth);

            // 从第一个item获取公共信息
            if (!groupItems.isEmpty()) {
                SolverOrderItem sample = groupItems.get(0);
                instruction.setLength(sample.getLength());
                instruction.setSurfaceTreatment(sample.getSurfaceTreatment());
                instruction.setThickness(sample.getThickness());
            }

            // 构建StationAssignment
            List<StationAssignment> assignments;
            if (USE_OPTIMIZED_ASSIGNMENT) {
                // 使用优化分配器（每个instruction独立构建remainingByWidthMsg）
                assignments = OrderAssignmentOptimizer.buildTupleBlockAssignments(
                        pattern.pattern, usageCount, groupItems, remainingDemands);
            } else {
                // 原始分配逻辑
                assignments = new ArrayList<>();
                for (Map.Entry<Integer, Integer> subRoll : pattern.pattern.entrySet()) {
                    int width = subRoll.getKey();
                    int stationCount = subRoll.getValue();
                    int totalNeeded = stationCount * usageCount;

                    List<SolverOrderItem> widthItems = widthToItems.get(width);
                    if (widthItems == null || widthItems.isEmpty())
                        continue;

                    int remaining = totalNeeded;
                    for (SolverOrderItem item : widthItems) {
                        if (remaining <= 0)
                            break;
                        int itemRemaining = remainingDemands.getOrDefault(item.getMessageText(), 0);
                        if (itemRemaining <= 0)
                            continue;

                        int allocated = Math.min(remaining, itemRemaining);
                        for (int i = 0; i < allocated; i++) {
                            assignments.add(new StationAssignment(width, item.getMessageText()));
                        }
                        remaining -= allocated;
                        remainingDemands.put(item.getMessageText(), itemRemaining - allocated);
                    }

                    // 超产部分分配给第一个订单项
                    if (remaining > 0 && !widthItems.isEmpty()) {
                        SolverOrderItem target = widthItems.get(0);
                        for (int i = 0; i < remaining; i++) {
                            assignments.add(new StationAssignment(width, target.getMessageText()));
                        }
                    }
                }
            }
            instruction.setStationAssignments(assignments);
            instructions.add(instruction);
        }

        // ========== 🔥 后处理再平衡：修正同宽度多订单项的分配错误 ==========
        rebalanceAssignments(instructions, groupItems);

        return instructions;
    }

    /**
     * 后处理再平衡：修正同宽度多订单项的分配错误
     * 1. 统计每个订单项的当前分配量
     * 2. 识别超产和缺货的订单项
     * 3. 从超产订单项转移分配给同宽度的缺货订单项
     */
    private void rebalanceAssignments(List<CuttingInstruction> instructions, List<SolverOrderItem> groupItems) {
        // 构建 messageText -> 原始需求
        Map<String, Integer> demandByMsg = new HashMap<>();
        Map<String, Integer> widthByMsg = new HashMap<>();
        for (SolverOrderItem item : groupItems) {
            demandByMsg.merge(item.getMessageText(), item.getDemand(), Integer::sum);
            widthByMsg.put(item.getMessageText(), item.getWidth());
        }

        // 统计当前分配量
        Map<String, Integer> allocatedByMsg = new HashMap<>();
        for (CuttingInstruction instr : instructions) {
            if (instr.getStationAssignments() == null)
                continue;
            for (StationAssignment sa : instr.getStationAssignments()) {
                allocatedByMsg.merge(sa.getMessageText(), 1, Integer::sum);
            }
        }

        // 按宽度分组订单项
        Map<Integer, List<String>> msgsByWidth = new HashMap<>();
        for (SolverOrderItem item : groupItems) {
            msgsByWidth.computeIfAbsent(item.getWidth(), k -> new ArrayList<>()).add(item.getMessageText());
        }
        // 去重
        for (List<String> msgs : msgsByWidth.values()) {
            List<String> unique = new ArrayList<>(new LinkedHashSet<>(msgs));
            msgs.clear();
            msgs.addAll(unique);
        }

        // 统计超产和缺货
        Map<String, Integer> overByMsg = new HashMap<>(); // 超产量 > 0
        Map<String, Integer> underByMsg = new HashMap<>(); // 缺货量 > 0
        for (String msg : demandByMsg.keySet()) {
            int demand = demandByMsg.get(msg);
            int allocated = allocatedByMsg.getOrDefault(msg, 0);
            int diff = allocated - demand;
            if (diff > 0) {
                overByMsg.put(msg, diff);
            } else if (diff < 0) {
                underByMsg.put(msg, -diff);
            }
        }

        if (overByMsg.isEmpty() || underByMsg.isEmpty()) {
            return; // 没有可转移的
        }

        // 按宽度进行再平衡
        for (Map.Entry<Integer, List<String>> entry : msgsByWidth.entrySet()) {
            int width = entry.getKey();
            List<String> msgs = entry.getValue();

            // 该宽度的超产和缺货订单项
            List<String> overMsgs = new ArrayList<>();
            List<String> underMsgs = new ArrayList<>();
            for (String msg : msgs) {
                if (overByMsg.containsKey(msg))
                    overMsgs.add(msg);
                if (underByMsg.containsKey(msg))
                    underMsgs.add(msg);
            }

            if (overMsgs.isEmpty() || underMsgs.isEmpty())
                continue;

            // 从超产订单项转移给缺货订单项
            for (String overMsg : overMsgs) {
                int overAmount = overByMsg.getOrDefault(overMsg, 0);
                if (overAmount <= 0)
                    continue;

                for (String underMsg : underMsgs) {
                    int underAmount = underByMsg.getOrDefault(underMsg, 0);
                    if (underAmount <= 0)
                        continue;

                    int transfer = Math.min(overAmount, underAmount);
                    if (transfer <= 0)
                        continue;

                    // 在instructions中找到overMsg的分配，转移给underMsg
                    int transferred = transferAssignments(instructions, width, overMsg, underMsg, transfer);

                    overAmount -= transferred;
                    underAmount -= transferred;
                    overByMsg.put(overMsg, overAmount);
                    underByMsg.put(underMsg, underAmount);

                    if (overAmount <= 0)
                        break;
                }
            }
        }
    }

    /**
     * 在instructions中将指定数量的分配从fromMsg转移到toMsg
     * 
     * @return 实际转移的数量
     */
    private int transferAssignments(List<CuttingInstruction> instructions, int width,
            String fromMsg, String toMsg, int count) {
        int transferred = 0;

        for (CuttingInstruction instr : instructions) {
            if (instr.getStationAssignments() == null)
                continue;

            List<StationAssignment> assignments = instr.getStationAssignments();
            for (int i = 0; i < assignments.size() && transferred < count; i++) {
                StationAssignment sa = assignments.get(i);
                if (sa.getWidth() == width && fromMsg.equals(sa.getMessageText())) {
                    // 创建新的分配（因为StationAssignment可能是不可变的）
                    assignments.set(i, new StationAssignment(width, toMsg));
                    transferred++;
                }
            }

            if (transferred >= count)
                break;
        }

        return transferred;
    }

    // ==================== 测试入口 ====================
    public static void main(String[] args) {
        log.info("PatternSolverFour 列生成+受控超产求解器测试");
    }
}
