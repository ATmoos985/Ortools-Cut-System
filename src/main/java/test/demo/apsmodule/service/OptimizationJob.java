package test.demo.apsmodule.service;

import test.demo.apsmodule.service.excel.ExcelImportService;

import java.time.Instant;
import java.util.List;

public class OptimizationJob {

    private String jobId;
    private String planId;
    private int revisionNumber;
    private OptimizationJobStatus status = OptimizationJobStatus.PENDING;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private String requestId;
    private String idempotencyKey;
    private String errorMessage;
    private CuttingOptimizationResult optimizationResult;
    private List<ExcelImportService.OrderItem> orderItems;
    private int totalWidth = 4600;
    private SolverConfig solverConfig;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
        touch();
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
        touch();
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public void setRevisionNumber(int revisionNumber) {
        this.revisionNumber = revisionNumber;
        touch();
    }

    public OptimizationJobStatus getStatus() {
        return status;
    }

    public void setStatus(OptimizationJobStatus status) {
        this.status = status;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
        touch();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
        touch();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        touch();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        touch();
    }

    public CuttingOptimizationResult getOptimizationResult() {
        return optimizationResult;
    }

    public void setOptimizationResult(CuttingOptimizationResult optimizationResult) {
        this.optimizationResult = optimizationResult;
        touch();
    }

    public List<ExcelImportService.OrderItem> getOrderItems() {
        return orderItems;
    }

    public void setOrderItems(List<ExcelImportService.OrderItem> orderItems) {
        this.orderItems = orderItems;
        touch();
    }

    public int getTotalWidth() {
        return totalWidth;
    }

    public void setTotalWidth(int totalWidth) {
        this.totalWidth = totalWidth;
        touch();
    }

    public SolverConfig getSolverConfig() {
        return solverConfig;
    }

    public void setSolverConfig(SolverConfig solverConfig) {
        this.solverConfig = solverConfig;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
