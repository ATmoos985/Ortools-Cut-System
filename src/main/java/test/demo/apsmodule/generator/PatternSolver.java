package test.demo.apsmodule.generator;

import org.acme.util.DataAnalyzer;
import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.service.*;

import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 切割模式求解器
 * 使用列生成算法 (Column Generation) 解决一维下料问题 (Cutting Stock Problem)。
 * 核心逻辑基于 Gilmore-Gomory 方法。
 */
import org.springframework.stereotype.Component;
import test.demo.apsmodule.solver.CuttingSolverAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 切割模式求解器 (固定宽度模式)
 * 使用列生成算法 (Column Generation) 解决一维下料问题。
 */
@Component
public class PatternSolver implements CuttingSolverAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(PatternSolver.class);

    /** 默认母卷宽度 (mm) */
    private static final int DEFAULT_MASTER_ROLL_WIDTH = 3380;
    /** 浮点数比较误差阈值 */
    private static final double EPSILON = 1e-6;
    /** 列生成最大迭代次数，防止死循环 */
    private static final int MAX_ITERATIONS = 200;
    /** Fixed-mode default minimum utilization */
    private static final double DEFAULT_MIN_UTILIZATION = 0.95;

    /** 当前母卷宽度 */
    private int masterRollWidth = DEFAULT_MASTER_ROLL_WIDTH;
    /** Minimum pattern width lower bound (defaults to 95% of masterRollWidth) */
    private int minPatternWidth = calculateDefaultMinPatternWidth(DEFAULT_MASTER_ROLL_WIDTH);

    // 统计信息
    private int iterationCount = 0;
    private double finalObjectiveValue = 0.0;
    private List<String> iterationLog = new ArrayList<>();

    public void setMasterRollWidth(int width) {
        this.masterRollWidth = width > 0 ? width : DEFAULT_MASTER_ROLL_WIDTH;
        // Keep a default lower bound so fixed-width mode avoids low-utilization patterns.
        this.minPatternWidth = calculateDefaultMinPatternWidth(this.masterRollWidth);
    }

    public int getMasterRollWidth() {
        return this.masterRollWidth;
    }

    public int getMinPatternWidth() {
        return minPatternWidth;
    }

    public void setMinPatternWidth(int minPatternWidth) {
        if (minPatternWidth <= 0) {
            this.minPatternWidth = calculateDefaultMinPatternWidth(masterRollWidth);
            return;
        }
        this.minPatternWidth = Math.min(minPatternWidth, masterRollWidth);
    }

    private int calculateDefaultMinPatternWidth(int rollWidth) {
        return Math.max(1, (int) Math.floor(rollWidth * DEFAULT_MIN_UTILIZATION));
    }

    // 静态代码块加载 OR-Tools 本地库
    static {
        try {
            Loader.loadNativeLibraries();
        } catch (Exception e) {
            log.error("警告: 加载 OR-Tools 库失败，求解功能将不可用: " + e.getMessage());
        }
    }

    /**
     * 生成最优切割模式
     * 
     * @param orderData 订单数据列表（包含子卷宽度和需求量）
     * @return 最优模式集合 Map<模式详情, 使用次数>
     *         模式详情: Map<子卷宽度, 单个模式中的数量>
     */
    public Map<Map<Integer, Integer>, Integer> generateOptimalPatterns(List<DataAnalyzer.SubRollData> orderData) {
        // 1. 整理需求数据：宽度 -> 总需求量
        Map<Integer, Integer> demands = new java.util.HashMap<>();
        for (DataAnalyzer.SubRollData data : orderData) {
            demands.put(data.width, demands.getOrDefault(data.width, 0) + data.demand);
        }

        // 重置统计数据
        iterationCount = 0;
        finalObjectiveValue = 0.0;
        iterationLog.clear();

        // 2. 初始化基础模式集合
        // 为每种宽度的子卷生成一个简单的模式：尽可能多地切该宽度的子卷
        List<Map<Integer, Integer>> currentPatterns = initializePatterns(demands);

        // 3. 列生成主循环
        while (iterationCount < MAX_ITERATIONS) {
            iterationCount++;

            // 3.1 求解主问题 (Master Problem) - 线性松弛问题 (LP Relaxation)
            // 目标：找到当前模式集合下的最优解（允许小数解）
            // 输出：影子价格 (Shadow Prices)，用于评估新模式的价值
            MasterProblemResult masterResult = solveMasterProblem(currentPatterns, demands);
            if (masterResult == null) {
                break; // 求解失败
            }

            finalObjectiveValue = masterResult.objectiveValue;

            // 3.2 求解子问题 (Subproblem) - 背包问题 (Knapsack Problem)
            // 目标：利用影子价格，找到一个Reduced Cost < 0的新切割模式
            // 如果新模式的总价值（基于影子价格） > 1.0，说明加入该模式能降低主问题的目标函数值
            Map<Integer, Integer> newPattern = solveSubproblem(masterResult.shadowPrices, demands.keySet());
            double newPatternValue = calculatePatternValue(newPattern, masterResult.shadowPrices);

            // 3.3 检查是否收敛
            // 如果新模式的价值不超过 1，说明找不到更好的模式了，当前LP解即为全局最优
            if (newPatternValue <= 1.0 + EPSILON) {
                break;
            }

            // 加入新模式进入下一轮迭代
            currentPatterns.add(newPattern);
        }

        // 4. 求解最终的整数规划问题 (Integer Programming Problem)
        // 使用生成的模式集合，求解整数解（因为实际生产中卷数必须是整数）
        Map<Map<Integer, Integer>, Integer> finalSolution = solveFinalIntegerProblem(currentPatterns, demands);

        // 打印统计信息
        printStatistics(finalSolution, demands);

        return finalSolution;
    }

    /**
     * 初始化模式集合
     * 为每个需求宽度生成一个"专一"模式：只切这一种宽度，尽可能切满
     */
    private List<Map<Integer, Integer>> initializePatterns(Map<Integer, Integer> demands) {
        List<Map<Integer, Integer>> patterns = new ArrayList<>();

        for (int width : demands.keySet()) {
            if (width > 0 && width <= masterRollWidth) {
                int maxCount = masterRollWidth / width;
                if (maxCount > 0) {
                    Map<Integer, Integer> pattern = new HashMap<>();
                    pattern.put(width, maxCount);
                    patterns.add(pattern);
                }
            }
        }

        return patterns;
    }

    /**
     * 主问题求解结果封装
     */
    private static class MasterProblemResult {
        final double objectiveValue;
        final Map<Integer, Double> shadowPrices; // 对偶变量值
        final Map<Map<Integer, Integer>, Double> patternUsage; // 模式使用量（浮点数）

        MasterProblemResult(double objectiveValue, Map<Integer, Double> shadowPrices,
                Map<Map<Integer, Integer>, Double> patternUsage) {
            this.objectiveValue = objectiveValue;
            this.shadowPrices = shadowPrices;
            this.patternUsage = patternUsage;
        }
    }

    /**
     * 求解主问题 (Master Problem)
     * 这是一个线性规划问题，目的是最小化母卷使用量
     */
    private MasterProblemResult solveMasterProblem(List<Map<Integer, Integer>> patterns,
            Map<Integer, Integer> demands) {
        try {
            // 使用 GLOP (Google Linear Optimization Package) 求解线性规划
            MPSolver solver = MPSolver.createSolver("GLOP");
            if (solver == null) {
                return null;
            }

            // 变量：每种模式的使用次数 (x_j)，非负实数
            List<MPVariable> patternVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                patternVars.add(solver.makeNumVar(0.0, Double.POSITIVE_INFINITY, "pattern_" + i));
            }

            // 约束：满足每种宽度的需求量
            // sum(a_ij * x_j) >= d_i
            Map<Integer, MPConstraint> constraints = new HashMap<>();
            for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
                int width = demandEntry.getKey();
                int demand = demandEntry.getValue();
                MPConstraint constraint = solver.makeConstraint(demand, Double.POSITIVE_INFINITY, "demand_" + width);
                constraints.put(width, constraint);
            }

            // 设置约束系数矩阵 a_ij
            for (int i = 0; i < patterns.size(); i++) {
                Map<Integer, Integer> pattern = patterns.get(i);
                for (Map.Entry<Integer, Integer> cut : pattern.entrySet()) {
                    int width = cut.getKey();
                    int count = cut.getValue();
                    MPConstraint constraint = constraints.get(width);
                    if (constraint != null) {
                        constraint.setCoefficient(patternVars.get(i), count);
                    }
                }
            }

            // 目标函数：最小化总使用卷数
            // min sum(x_j)
            MPObjective objective = solver.objective();
            for (MPVariable var : patternVars) {
                objective.setCoefficient(var, 1.0);
            }
            objective.setMinimization();

            // 求解
            MPSolver.ResultStatus resultStatus = solver.solve();
            if (resultStatus != MPSolver.ResultStatus.OPTIMAL) {
                return null;
            }

            // 获取影子价格 (Shadow Prices / Dual Values)
            // 影子价格代表了增加一个单位需求对目标函数值的影响
            // 在列生成中，它们衡量了该宽度子卷的"价值"
            Map<Integer, Double> shadowPrices = new HashMap<>();
            for (Map.Entry<Integer, MPConstraint> entry : constraints.entrySet()) {
                shadowPrices.put(entry.getKey(), entry.getValue().dualValue());
            }

            Map<Map<Integer, Integer>, Double> patternUsage = new HashMap<>();
            for (int i = 0; i < patterns.size(); i++) {
                double usage = patternVars.get(i).solutionValue();
                if (usage > EPSILON) {
                    patternUsage.put(patterns.get(i), usage);
                }
            }

            return new MasterProblemResult(objective.value(), shadowPrices, patternUsage);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 求解子问题 (Subproblem) - 背包问题
     * 目标：max sum(price_i * a_i)
     * 约束：sum(width_i * a_i) <= MasterRollWidth
     * 
     * 这里使用动态规划 (DP) 求解
     */
    private Map<Integer, Integer> solveSubproblem(Map<Integer, Double> shadowPrices, Set<Integer> allWidths) {
        List<Integer> widths = new ArrayList<>(allWidths);
        int lowerBound = Math.min(masterRollWidth, Math.max(1, minPatternWidth));

        // dp[w] 表示容量为 w 时的最大价值
        double[] dp = new double[masterRollWidth + 1];
        // solution[w][i] 记录构成 dp[w] 最优解时，第 i 种宽度的数量
        int[][] solution = new int[masterRollWidth + 1][widths.size()];

        for (int w = 1; w <= masterRollWidth; w++) {
            for (int i = 0; i < widths.size(); i++) {
                int width = widths.get(i);
                // 获取该宽度的影子价格作为价值
                double value = shadowPrices.getOrDefault(width, 0.0);

                if (width <= w) {
                    double newValue = dp[w - width] + value;
                    if (newValue > dp[w]) {
                        dp[w] = newValue;
                        // 更新方案：继承前面的方案并+1
                        System.arraycopy(solution[w - width], 0, solution[w], 0, widths.size());
                        solution[w][i]++;
                    }
                }
            }
        }

        // 提取最优模式
        int bestWidth = -1;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int w = lowerBound; w <= masterRollWidth; w++) {
            if (dp[w] > bestValue + EPSILON || (Math.abs(dp[w] - bestValue) <= EPSILON && w > bestWidth)) {
                bestValue = dp[w];
                bestWidth = w;
            }
        }
        if (bestWidth < 0) {
            return Collections.emptyMap();
        }

        Map<Integer, Integer> bestPattern = new HashMap<>();
        for (int i = 0; i < widths.size(); i++) {
            int count = solution[bestWidth][i];
            if (count > 0) {
                bestPattern.put(widths.get(i), count);
            }
        }

        return bestPattern;
    }

    /**
     * 计算模式的价值 (Reduced Cost 的一部分)
     * Value = sum(ShadowPrice_i * Count_i)
     */
    private double calculatePatternValue(Map<Integer, Integer> pattern, Map<Integer, Double> shadowPrices) {
        return pattern.entrySet().stream()
                .mapToDouble(entry -> {
                    int width = entry.getKey();
                    int count = entry.getValue();
                    double price = shadowPrices.getOrDefault(width, 0.0);
                    return price * count;
                })
                .sum();
    }

    /**
     * 求解最终的整数规划问题
     * 使用生成的模式集合，将变量限制为整数，求解最终方案
     */
    private Map<Map<Integer, Integer>, Integer> solveFinalIntegerProblem(List<Map<Integer, Integer>> patterns,
            Map<Integer, Integer> demands) {
        try {
            // 优先使用 SCIP，如果不可用则尝试 CBC，最后是默认
            MPSolver solver = MPSolver.createSolver("SCIP");
            if (solver == null) {
                solver = MPSolver.createSolver("CBC");
                if (solver == null) {
                    return new HashMap<>();
                }
            }

            // 变量：x_j 为整数，表示模式 j 的使用次数
            List<MPVariable> patternVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                // 上限设为10000或更高，视具体业务规模而定
                patternVars.add(solver.makeIntVar(0, 10000, "pattern_" + i));
            }

            // 约束：sum(a_ij * x_j) >= d_i
            Map<Integer, MPConstraint> constraints = new HashMap<>();
            for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
                int width = demandEntry.getKey();
                int demand = demandEntry.getValue();
                MPConstraint constraint = solver.makeConstraint(demand, Double.POSITIVE_INFINITY, "demand_" + width);
                constraints.put(width, constraint);
            }

            // 设置系数
            for (int i = 0; i < patterns.size(); i++) {
                Map<Integer, Integer> pattern = patterns.get(i);
                for (Map.Entry<Integer, Integer> cut : pattern.entrySet()) {
                    int width = cut.getKey();
                    int count = cut.getValue();
                    MPConstraint constraint = constraints.get(width);
                    if (constraint != null) {
                        constraint.setCoefficient(patternVars.get(i), count);
                    }
                }
            }

            // 目标：min sum(x_j)
            MPObjective objective = solver.objective();
            for (MPVariable var : patternVars) {
                objective.setCoefficient(var, 1.0);
            }
            objective.setMinimization();

            // 求解整数规划
            MPSolver.ResultStatus resultStatus = solver.solve();
            if (resultStatus != MPSolver.ResultStatus.OPTIMAL && resultStatus != MPSolver.ResultStatus.FEASIBLE) {
                return new HashMap<>();
            }

            // 提取结果
            Map<Map<Integer, Integer>, Integer> finalSolution = new HashMap<>();
            for (int i = 0; i < patterns.size(); i++) {
                int usage = (int) Math.round(patternVars.get(i).solutionValue());
                if (usage > 0) {
                    finalSolution.put(patterns.get(i), usage);
                }
            }

            finalObjectiveValue = objective.value();
            return finalSolution;

        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public int getIterationCount() {
        return iterationCount;
    }

    public double getFinalObjectiveValue() {
        return finalObjectiveValue;
    }

    /**
     * 打印统计信息
     */
    private void printStatistics(Map<Map<Integer, Integer>, Integer> solution, Map<Integer, Integer> demands) {
        Map<Integer, Integer> totalProduction = new HashMap<>();
        int totalRolls = 0;

        for (Map.Entry<Map<Integer, Integer>, Integer> entry : solution.entrySet()) {
            Map<Integer, Integer> pattern = entry.getKey();
            int usage = entry.getValue();
            totalRolls += usage;

            for (Map.Entry<Integer, Integer> cut : pattern.entrySet()) {
                int width = cut.getKey();
                int count = cut.getValue();
                totalProduction.put(width, totalProduction.getOrDefault(width, 0) + count * usage);
            }
        }

        // 这里仅保留了统计逻辑，原始的打印可能被简化，视需求而定
        boolean allDemandsMet = true;
        int totalOverproduction = 0;

        for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
            int width = demandEntry.getKey();
            int demand = demandEntry.getValue();
            int production = totalProduction.getOrDefault(width, 0);

            if (production < demand) {
                allDemandsMet = false;
            } else {
                totalOverproduction += (production - demand);
            }
        }

        // ... (省略部分详细的日志打印，保持核心逻辑清晰)
    }

    /**
     * 新的统一求解方法：接收SolverOrderItem列表，返回CuttingInstruction列表
     * 
     * @param items  规范化的求解器输入
     * @param config 求解器配置
     * @return 切割指令列表
     */
    @Override
    public boolean supports(SolverConfig config) {
        return config.isFixedMode();
    }

    /**
     * @param items  规范化的求解器输入
     * @param config 求解器配置
     * @return 切割指令列表
     */
    @Override
    public List<CuttingInstruction> solve(List<SolverOrderItem> items, SolverConfig config) {
        Map<String, List<SolverOrderItem>> groupedItems = items.stream()
                .collect(Collectors.groupingBy(SolverOrderItem::getGroupKey));

        List<CuttingInstruction> allInstructions = new ArrayList<>();

        for (Map.Entry<String, List<SolverOrderItem>> groupEntry : groupedItems.entrySet()) {
            String groupKey = groupEntry.getKey();
            List<SolverOrderItem> groupItems = groupEntry.getValue();

            // 构建需求映射
            Map<Integer, Integer> demands = new HashMap<>();
            Map<Integer, List<SolverOrderItem>> widthToItems = new HashMap<>();

            for (SolverOrderItem item : groupItems) {
                demands.put(item.getWidth(), demands.getOrDefault(item.getWidth(), 0) + item.getDemand());
                widthToItems.computeIfAbsent(item.getWidth(), k -> new ArrayList<>()).add(item);
            }

            // 转换为DataAnalyzer.SubRollData格式（兼容现有代码）
            List<DataAnalyzer.SubRollData> orderData = new ArrayList<>();
            for (SolverOrderItem item : groupItems) {
                orderData
                        .add(new DataAnalyzer.SubRollData(0, item.getWidth(), item.getDemand(), item.getMessageText()));
            }

            // 设置母卷宽度
            this.setMasterRollWidth(config.getMaxWidth());
            // Default lower bound is fixedWidth * 95%.
            // If caller explicitly provides a smaller minWidth, use it.
            int configuredMin = config.getMinWidth();
            if (configuredMin > 0 && configuredMin < config.getMaxWidth()) {
                this.setMinPatternWidth(configuredMin);
            }

            // 调用现有求解方法
            Map<Map<Integer, Integer>, Integer> patterns = generateOptimalPatterns(orderData);

            // 转换为CuttingInstruction
            // ... (转换逻辑)

            // 🔥 修复：remainingDemands应该在pattern循环外部初始化一次
            // 这样所有pattern共享同一个需求池，避免重复分配
            Map<String, Integer> remainingDemands = new HashMap<>();
            for (SolverOrderItem item : groupItems) {
                remainingDemands.put(item.getMessageText(),
                        remainingDemands.getOrDefault(item.getMessageText(), 0) + item.getDemand());
            }

            for (Map.Entry<Map<Integer, Integer>, Integer> patternEntry : patterns.entrySet()) {
                Map<Integer, Integer> pattern = patternEntry.getKey();
                int usageCount = patternEntry.getValue();

                CuttingInstruction instruction = new CuttingInstruction();
                instruction.setGroupKey(groupKey);
                instruction.setRollWidth(config.getMaxWidth());

                // 从第一个item获取公共信息
                if (!groupItems.isEmpty()) {
                    SolverOrderItem sample = groupItems.get(0);
                    instruction.setLength(sample.getLength());
                    instruction.setSurfaceTreatment(sample.getSurfaceTreatment());
                    instruction.setThickness(sample.getThickness());
                }

                instruction.setSubRolls(new LinkedHashMap<>(pattern));
                instruction.setUsageCount(usageCount);

                // 构建StationAssignment列表
                List<StationAssignment> stationAssignments = new ArrayList<>();

                // 为每个pattern的每个宽度分配订单项
                for (Map.Entry<Integer, Integer> subRollEntry : pattern.entrySet()) {
                    int width = subRollEntry.getKey();
                    int stationCount = subRollEntry.getValue();

                    // 获取该宽度的所有订单项
                    List<SolverOrderItem> widthItems = widthToItems.get(width);
                    if (widthItems == null || widthItems.isEmpty()) {
                        continue;
                    }

                    // 分配订单项到工位
                    int remainingForPattern = stationCount * usageCount;
                    for (SolverOrderItem item : widthItems) {
                        if (remainingForPattern <= 0)
                            break;

                        // 获取该订单项的剩余需求
                        int itemRemaining = remainingDemands.getOrDefault(item.getMessageText(), 0);
                        if (itemRemaining <= 0)
                            continue;

                        int allocated = Math.min(remainingForPattern, itemRemaining);
                        if (allocated > 0) {
                            // 创建StationAssignment，保存messageText用于后续匹配OrderItem
                            for (int i = 0; i < allocated; i++) {
                                StationAssignment assignment = new StationAssignment(width, item.getMessageText());
                                stationAssignments.add(assignment);
                            }
                            remainingForPattern -= allocated;
                            // 🔧 扣减该订单项的剩余需求
                            remainingDemands.put(item.getMessageText(), itemRemaining - allocated);
                        }
                    }

                    // 🔥 关键修复：如果还有剩余（超产），分配给第一个订单项
                    // 这样OrderValidationService就能检测到超产
                    if (remainingForPattern > 0 && !widthItems.isEmpty()) {
                        SolverOrderItem targetItem = widthItems.get(0);
                        for (int i = 0; i < remainingForPattern; i++) {
                            StationAssignment assignment = new StationAssignment(width, targetItem.getMessageText());
                            stationAssignments.add(assignment);
                        }
                    }
                }

                instruction.setStationAssignments(stationAssignments);
                allInstructions.add(instruction);
            }
        }

        return allInstructions;
    }
}
