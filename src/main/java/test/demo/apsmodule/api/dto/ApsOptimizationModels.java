package test.demo.apsmodule.api.dto;

import java.util.List;
import java.util.Map;

public final class ApsOptimizationModels {

    private ApsOptimizationModels() {
    }

    public record CreateJobRequest(
            String requestId,
            String idempotencyKey,
            List<Order> orders,
            OptimizationConfig config) {
    }

    public record OptimizationConfig(
            Boolean flexibleWidth,
            Integer fixedWidth,
            Integer totalWidth,
            Integer minWidth,
            Integer maxWidth,
            Integer stepSize,
            Integer totalOverCap,
            Integer maxIterations,
            Long timeoutMs,
            Boolean useNewSolver,
            Integer newSolverTopK,
            Integer newSolverMaxPatterns,
            Integer newSolverMaxDistinctWidths,
            Long newSolverStage4TimeLimit,
            Double newSolverSeqGroupAlpha,
            Double newSolverSeqGroupBeta,
            Boolean newSolverUseOptimizedAssignment,
            Double newSolverUnderPenalty) {
    }

    public record Order(
            String orderId,
            Integer width,
            Integer quantity,
            Integer length,
            String surfaceTreatment,
            String salesperson,
            String description,
            Integer thickness) {
    }

    public record JobStatusData(
            String jobId,
            String status,
            String createdAt,
            String updatedAt,
            String requestId,
            String idempotencyKey,
            String errorMessage) {
    }

    public record ResultSummary(
            int totalRollsUsed,
            int totalWaste,
            double utilizationRate,
            long executionTimeMs,
            int totalWidth) {
    }

    public record OptimizationResultData(
            ResultSummary summary,
            List<Plan> plans) {
    }

    public record Assignment(
            Integer width,
            String orderId,
            String salesperson) {
    }

    public record Plan(
            String groupKey,
            Integer rollWidth,
            Integer length,
            String surfaceTreatment,
            Integer usageCount,
            Map<Integer, Integer> subRolls,
            List<Assignment> assignments) {
    }

    public record JobResultData(
            String jobId,
            String status,
            String requestId,
            String idempotencyKey,
            ResultSummary summary,
            List<Plan> plans) {
    }
}
