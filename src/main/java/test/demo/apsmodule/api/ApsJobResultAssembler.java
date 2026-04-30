package test.demo.apsmodule.api;

import org.springframework.stereotype.Component;
import test.demo.apsmodule.api.dto.ApsOptimizationModels;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.OptimizationJob;

import java.util.ArrayList;
import java.util.List;

@Component
public class ApsJobResultAssembler {

    public ApsOptimizationModels.JobStatusData toJobStatusData(OptimizationJob job) {
        return new ApsOptimizationModels.JobStatusData(
                job.getJobId(),
                job.getStatus().name(),
                job.getCreatedAt().toString(),
                job.getUpdatedAt().toString(),
                job.getRequestId(),
                job.getIdempotencyKey(),
                job.getErrorMessage());
    }

    public ApsOptimizationModels.OptimizationResultData toOptimizationResultData(CuttingOptimizationResult result) {
        return new ApsOptimizationModels.OptimizationResultData(
                toSummary(result),
                toPlans(result));
    }

    public ApsOptimizationModels.JobResultData toJobResultData(OptimizationJob job) {
        CuttingOptimizationResult result = job.getOptimizationResult();
        return new ApsOptimizationModels.JobResultData(
                job.getJobId(),
                job.getStatus().name(),
                job.getRequestId(),
                job.getIdempotencyKey(),
                toSummary(result),
                toPlans(result));
    }

    private ApsOptimizationModels.ResultSummary toSummary(CuttingOptimizationResult result) {
        return new ApsOptimizationModels.ResultSummary(
                result.getTotalRollsUsed(),
                result.getTotalWaste(),
                result.getUtilizationRate(),
                result.getExecutionTimeMs(),
                result.getTotalWidth());
    }

    private List<ApsOptimizationModels.Plan> toPlans(CuttingOptimizationResult result) {
        List<ApsOptimizationModels.Plan> plans = new ArrayList<>();
        if (result.getCuttingInstructions() == null) {
            return plans;
        }

        for (CuttingOptimizationResult.CuttingInstruction instruction : result.getCuttingInstructions()) {
            plans.add(new ApsOptimizationModels.Plan(
                    instruction.getGroupKey(),
                    instruction.getRollWidth(),
                    instruction.getLength(),
                    instruction.getSurfaceTreatment(),
                    instruction.getUsageCount(),
                    instruction.getSubRolls(),
                    toAssignments(instruction)));
        }

        return plans;
    }

    private List<ApsOptimizationModels.Assignment> toAssignments(CuttingOptimizationResult.CuttingInstruction instruction) {
        List<ApsOptimizationModels.Assignment> assignments = new ArrayList<>();
        if (instruction.getStationAssignments() == null) {
            return assignments;
        }

        for (CuttingOptimizationResult.StationAssignment assignment : instruction.getStationAssignments()) {
            assignments.add(new ApsOptimizationModels.Assignment(
                    assignment.getWidth(),
                    assignment.getMessageText(),
                    assignment.getOrderItem() != null ? assignment.getOrderItem().getSalesperson() : null));
        }
        return assignments;
    }
}
