package test.demo.apsmodule.service;

import java.util.HashSet;
import java.util.Set;

/**
 * 求解器配置类
 * 统一管理求解器配置和模式判断，避免在多个地方判断模式
 */
public class SolverConfig {

    private String mode; // "fixed" 或 "variable"
    private int minWidth;
    private int maxWidth;
    private int stepSize;
    private boolean flexibleWidth;
    private int totalWidth; // 用于Excel公式计算的标准宽度
    private int totalOverCap = 30; // 超产上限，默认30
    private Set<Integer> forceAllowOverWidths = new HashSet<>(); // 强制允许超产的宽幅（二次搭切时 demand=0 也可超产）

    // 高级算法参数
    private int maxIterations = 200; // 最大迭代次数，默认200
    private long timeoutMs = 60000; // 超时时间(毫秒)，默认60秒

    // 🚀 NewSolver 专用参数
    private boolean useNewSolver = false;
    private int newSolverTopK = 3;
    private int newSolverMaxPatterns = 800;
    private int newSolverMaxDistinctWidths = 4;
    private long newSolverStage4TimeLimit = 30000;
    private double newSolverSeqGroupAlpha = 1.0;
    private double newSolverSeqGroupBeta = 0.0;
    private boolean newSolverUseOptimizedAssignment = true;
    private double newSolverUnderPenalty = 1e6;

    /**
     * 无参构造函数
     */
    public SolverConfig() {
        this.mode = "fixed";
        this.flexibleWidth = false;
    }

    /**
     * 固定模式构造函数
     */
    public SolverConfig(int width, int totalWidth) {
        this.mode = "fixed";
        this.minWidth = width;
        this.maxWidth = width;
        this.stepSize = 1;
        this.flexibleWidth = false;
        this.totalWidth = totalWidth;
    }

    /**
     * 可变模式构造函数
     */
    public SolverConfig(int minWidth, int maxWidth, int stepSize, int totalWidth) {
        this.mode = "variable";
        this.minWidth = minWidth;
        this.maxWidth = maxWidth;
        this.stepSize = stepSize;
        this.flexibleWidth = true;
        this.totalWidth = totalWidth;
    }

    /**
     * 判断是否为固定模式
     */
    public boolean isFixedMode() {
        return !flexibleWidth;
    }

    /**
     * 判断是否为可变模式
     */
    public boolean isFlexibleMode() {
        return flexibleWidth;
    }

    // Getters and Setters
    public int getMinWidth() {
        return minWidth;
    }

    public void setMinWidth(int minWidth) {
        this.minWidth = minWidth;
    }

    public int getMaxWidth() {
        return maxWidth;
    }

    public void setMaxWidth(int maxWidth) {
        this.maxWidth = maxWidth;
    }

    public int getStepSize() {
        return stepSize;
    }

    public void setStepSize(int stepSize) {
        this.stepSize = stepSize;
    }

    public boolean isFlexibleWidth() {
        return flexibleWidth;
    }

    public void setFlexibleWidth(boolean flexibleWidth) {
        this.flexibleWidth = flexibleWidth;
    }

    public int getTotalWidth() {
        return totalWidth;
    }

    public void setTotalWidth(int totalWidth) {
        this.totalWidth = totalWidth;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
        this.flexibleWidth = "variable".equalsIgnoreCase(mode);
    }

    public int getTotalOverCap() {
        return totalOverCap;
    }

    public void setTotalOverCap(int totalOverCap) {
        this.totalOverCap = totalOverCap;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations > 0 ? maxIterations : 200;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 60000;
    }

    // NewSolver getters/setters
    public boolean isUseNewSolver() {
        return useNewSolver;
    }

    public void setUseNewSolver(boolean useNewSolver) {
        this.useNewSolver = useNewSolver;
    }

    public int getNewSolverTopK() {
        return newSolverTopK;
    }

    public void setNewSolverTopK(int newSolverTopK) {
        this.newSolverTopK = newSolverTopK > 0 ? newSolverTopK : 3;
    }

    public int getNewSolverMaxPatterns() {
        return newSolverMaxPatterns;
    }

    public void setNewSolverMaxPatterns(int newSolverMaxPatterns) {
        this.newSolverMaxPatterns = newSolverMaxPatterns > 0 ? newSolverMaxPatterns : 800;
    }

    public int getNewSolverMaxDistinctWidths() {
        return newSolverMaxDistinctWidths;
    }

    public void setNewSolverMaxDistinctWidths(int newSolverMaxDistinctWidths) {
        this.newSolverMaxDistinctWidths = newSolverMaxDistinctWidths > 0 ? newSolverMaxDistinctWidths : 4;
    }

    public long getNewSolverStage4TimeLimit() {
        return newSolverStage4TimeLimit;
    }

    public void setNewSolverStage4TimeLimit(long newSolverStage4TimeLimit) {
        this.newSolverStage4TimeLimit = newSolverStage4TimeLimit > 0 ? newSolverStage4TimeLimit : 30000;
    }

    public double getNewSolverSeqGroupAlpha() {
        return newSolverSeqGroupAlpha;
    }

    public void setNewSolverSeqGroupAlpha(double newSolverSeqGroupAlpha) {
        this.newSolverSeqGroupAlpha = newSolverSeqGroupAlpha;
    }

    public double getNewSolverSeqGroupBeta() {
        return newSolverSeqGroupBeta;
    }

    public void setNewSolverSeqGroupBeta(double newSolverSeqGroupBeta) {
        this.newSolverSeqGroupBeta = newSolverSeqGroupBeta;
    }

    public boolean isNewSolverUseOptimizedAssignment() {
        return newSolverUseOptimizedAssignment;
    }

    public void setNewSolverUseOptimizedAssignment(boolean newSolverUseOptimizedAssignment) {
        this.newSolverUseOptimizedAssignment = newSolverUseOptimizedAssignment;
    }

    public double getNewSolverUnderPenalty() {
        return newSolverUnderPenalty;
    }

    public void setNewSolverUnderPenalty(double newSolverUnderPenalty) {
        this.newSolverUnderPenalty = newSolverUnderPenalty > 0 ? newSolverUnderPenalty : 1e6;
    }

    public Set<Integer> getForceAllowOverWidths() {
        return forceAllowOverWidths;
    }

    public void setForceAllowOverWidths(Set<Integer> forceAllowOverWidths) {
        this.forceAllowOverWidths = forceAllowOverWidths != null ? forceAllowOverWidths : new HashSet<>();
    }
}
