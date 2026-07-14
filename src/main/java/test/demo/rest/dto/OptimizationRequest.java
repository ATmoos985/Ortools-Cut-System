package test.demo.rest.dto;

import test.demo.apsmodule.service.excel.ExcelImportService;
import java.util.List;

public class OptimizationRequest {
    public enum SolverProfile {
        FAST,
        QUALITY
    }

    private String orderId;
    private String orderName;
    private String customerName;
    private String description;
    private boolean flexibleWidth;
    private int fixedWidth;
    private int totalWidth; // 总宽度，用于Excel导出公式计算
    private int minWidth;
    private int maxWidth;
    private int stepSize;
    private int totalOverCap = 30; // 超产上限，默认30
    private int maxIterations = 200; // 最大迭代次数，默认200
    private long timeoutMs = 60000; // 超时时间(毫秒)，默认60秒
    // 🚀 NewSolver 专用参数
    private boolean useNewSolver = false; // 是否使用新求解器
    private int newSolverTopK = 3; // 允许超产的前K个大需求宽度
    private int newSolverMaxPatterns = 800;
    private int newSolverMaxDistinctWidths = 4;
    private long newSolverStage4TimeLimit = 30000;
    private double newSolverSeqGroupAlpha = 1.0;
    private double newSolverSeqGroupBeta = 0.0;
    private boolean newSolverUseOptimizedAssignment = true;
    private double newSolverUnderPenalty = 1e6;
    private boolean lnsEnabled = true;
    private boolean lnsEnrichPatterns = false;
    private SolverProfile solverProfile;
    /** 质量模式：A层 parity{0,0.1} × B层双LNS邻域 多候选评优（isBetterPlan 拣优，永不劣于快路径；耗时约×2）。 */
    private boolean qualityMode = false;
    private List<ExcelImportService.OrderItem> orderItems;

    // Getters and Setters
    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderName() {
        return orderName;
    }

    public void setOrderName(String orderName) {
        this.orderName = orderName;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isFlexibleWidth() {
        return flexibleWidth;
    }

    public void setFlexibleWidth(boolean flexibleWidth) {
        this.flexibleWidth = flexibleWidth;
    }

    public int getFixedWidth() {
        return fixedWidth;
    }

    public void setFixedWidth(int fixedWidth) {
        this.fixedWidth = fixedWidth;
    }

    public int getTotalWidth() {
        return totalWidth;
    }

    public void setTotalWidth(int totalWidth) {
        this.totalWidth = totalWidth;
    }

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

    public int getTotalOverCap() {
        return totalOverCap;
    }

    public void setTotalOverCap(int totalOverCap) {
        this.totalOverCap = totalOverCap;
    }

    public List<ExcelImportService.OrderItem> getOrderItems() {
        return orderItems;
    }

    public void setOrderItems(List<ExcelImportService.OrderItem> orderItems) {
        this.orderItems = orderItems;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
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
        this.newSolverTopK = newSolverTopK;
    }

    public int getNewSolverMaxPatterns() {
        return newSolverMaxPatterns;
    }

    public void setNewSolverMaxPatterns(int newSolverMaxPatterns) {
        this.newSolverMaxPatterns = newSolverMaxPatterns;
    }

    public int getNewSolverMaxDistinctWidths() {
        return newSolverMaxDistinctWidths;
    }

    public void setNewSolverMaxDistinctWidths(int newSolverMaxDistinctWidths) {
        this.newSolverMaxDistinctWidths = newSolverMaxDistinctWidths;
    }

    public long getNewSolverStage4TimeLimit() {
        return newSolverStage4TimeLimit;
    }

    public void setNewSolverStage4TimeLimit(long newSolverStage4TimeLimit) {
        this.newSolverStage4TimeLimit = newSolverStage4TimeLimit;
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
        this.newSolverUnderPenalty = newSolverUnderPenalty;
    }

    public boolean isLnsEnabled() {
        return lnsEnabled;
    }

    public void setLnsEnabled(boolean lnsEnabled) {
        this.lnsEnabled = lnsEnabled;
    }

    public boolean isLnsEnrichPatterns() {
        return lnsEnrichPatterns;
    }

    public boolean isQualityMode() {
        return qualityMode;
    }

    public void setQualityMode(boolean qualityMode) {
        this.qualityMode = qualityMode;
    }

    public void setLnsEnrichPatterns(boolean lnsEnrichPatterns) {
        this.lnsEnrichPatterns = lnsEnrichPatterns;
    }

    public SolverProfile getSolverProfile() {
        return solverProfile;
    }

    public void setSolverProfile(SolverProfile solverProfile) {
        this.solverProfile = solverProfile;
    }
}
