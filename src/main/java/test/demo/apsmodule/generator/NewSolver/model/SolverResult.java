package test.demo.apsmodule.generator.NewSolver.model;

import java.util.*;

/**
 * 求解结果
 * 
 * 封装 MIP 求解的输出结果
 */
public class SolverResult {

    /** 选中的模式及使用次数 */
    private final Map<PatternCandidate, Integer> solution;
    /** 总卷数 */
    private final int totalRolls;
    /** 总废边 (mm) */
    private final int totalWaste;
    /** 模式种类数 */
    private final int patternCount;
    /** 总超产量 */
    private final int totalOverProduction;
    /** 是否求解成功 */
    private final boolean success;
    /** 求解耗时 (ms) */
    private final long solveTimeMs;

    public SolverResult(Map<PatternCandidate, Integer> solution,
            int totalRolls, int totalWaste,
            int totalOverProduction, long solveTimeMs) {
        this.solution = solution != null ? new LinkedHashMap<>(solution) : new LinkedHashMap<>();
        this.totalRolls = totalRolls;
        this.totalWaste = totalWaste;
        this.patternCount = this.solution.size();
        this.totalOverProduction = totalOverProduction;
        this.success = !this.solution.isEmpty();
        this.solveTimeMs = solveTimeMs;
    }

    /**
     * 创建失败结果
     */
    public static SolverResult failure(long solveTimeMs) {
        return new SolverResult(null, 0, 0, 0, solveTimeMs);
    }

    // ==================== Getters ====================

    public Map<PatternCandidate, Integer> getSolution() {
        return Collections.unmodifiableMap(solution);
    }

    public int getTotalRolls() {
        return totalRolls;
    }

    public int getTotalWaste() {
        return totalWaste;
    }

    public int getPatternCount() {
        return patternCount;
    }

    public int getTotalOverProduction() {
        return totalOverProduction;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getSolveTimeMs() {
        return solveTimeMs;
    }

    /**
     * 计算材料利用率
     */
    public double getUtilization(int totalWidth) {
        if (totalRolls == 0)
            return 0;
        int totalUsed = solution.entrySet().stream()
                .mapToInt(e -> e.getKey().getPatternWidth() * e.getValue())
                .sum();
        int totalMaterial = totalRolls * totalWidth;
        return (double) totalUsed / totalMaterial;
    }

    @Override
    public String toString() {
        return "SolverResult{" +
                "success=" + success +
                ", totalRolls=" + totalRolls +
                ", patternCount=" + patternCount +
                ", totalWaste=" + totalWaste +
                ", totalOverProduction=" + totalOverProduction +
                ", solveTimeMs=" + solveTimeMs +
                '}';
    }
}
