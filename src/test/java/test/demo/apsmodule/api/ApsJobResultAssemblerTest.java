package test.demo.apsmodule.api;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.api.dto.ApsOptimizationModels;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.OptimizationJob;
import test.demo.apsmodule.service.OptimizationJobStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApsJobResultAssemblerTest {

    private final ApsJobResultAssembler assembler = new ApsJobResultAssembler();

    @Test
    void buildsResultPayloadFromOptimizationJob() {
        CuttingOptimizationResult.CuttingInstruction instruction = new CuttingOptimizationResult.CuttingInstruction();
        instruction.setGroupKey("1000m+T1");
        instruction.setRollWidth(4600);
        instruction.setLength(1000);
        instruction.setSurfaceTreatment("T1");
        instruction.setUsageCount(2);
        instruction.setSubRolls(Map.of(1200, 2));

        CuttingOptimizationResult result = new CuttingOptimizationResult();
        result.setTotalRollsUsed(2);
        result.setTotalWaste(50);
        result.setUtilizationRate(98.2);
        result.setExecutionTimeMs(1200);
        result.setTotalWidth(4600);
        result.setCuttingInstructions(List.of(instruction));

        OptimizationJob job = new OptimizationJob();
        job.setJobId("job-1");
        job.setStatus(OptimizationJobStatus.SUCCEEDED);
        job.setRequestId("REQ-1");
        job.setIdempotencyKey("IDEMP-1");
        job.setOptimizationResult(result);

        ApsOptimizationModels.JobResultData payload = assembler.toJobResultData(job);

        assertEquals("job-1", payload.jobId());
        assertEquals("SUCCEEDED", payload.status());
        assertEquals("REQ-1", payload.requestId());
        assertEquals("IDEMP-1", payload.idempotencyKey());
        assertEquals(2, payload.summary().totalRollsUsed());
        assertEquals(1, payload.plans().size());
        assertEquals("1000m+T1", payload.plans().get(0).groupKey());
    }

    @Test
    void buildsDirectOptimizationPayload() {
        CuttingOptimizationResult.CuttingInstruction instruction = new CuttingOptimizationResult.CuttingInstruction();
        instruction.setGroupKey("1000m+T1");
        instruction.setRollWidth(4600);
        instruction.setLength(1000);
        instruction.setSurfaceTreatment("T1");
        instruction.setUsageCount(2);
        instruction.setSubRolls(Map.of(1200, 2));

        CuttingOptimizationResult result = new CuttingOptimizationResult();
        result.setTotalRollsUsed(2);
        result.setTotalWaste(50);
        result.setUtilizationRate(98.2);
        result.setExecutionTimeMs(1200);
        result.setTotalWidth(4600);
        result.setCuttingInstructions(List.of(instruction));

        ApsOptimizationModels.OptimizationResultData payload = assembler.toOptimizationResultData(result);

        assertEquals(2, payload.summary().totalRollsUsed());
        assertEquals(1, payload.plans().size());
        assertEquals("1000m+T1", payload.plans().get(0).groupKey());
    }
}
