package test.demo.rest.context;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.InMemoryOptimizationJobRepository;
import test.demo.apsmodule.service.OptimizationJob;
import test.demo.apsmodule.service.OptimizationJobStatus;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.excel.ExcelImportService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class OptimizationContextTest {

    @Test
    void createsAndCompletesJobWhilePreservingLastAccessors() {
        OptimizationContext context = new OptimizationContext(new InMemoryOptimizationJobRepository());
        String jobId = context.createJob();

        ExcelImportService.OrderItem orderItem = new ExcelImportService.OrderItem();
        orderItem.setMessageText("MSG-1");
        orderItem.setWidth(1200);
        orderItem.setQuantity(2);

        SolverConfig solverConfig = new SolverConfig();
        solverConfig.setTotalWidth(4600);

        CuttingOptimizationResult result = new CuttingOptimizationResult();
        result.setTotalWidth(4600);

        context.completeJob(jobId, result, List.of(orderItem), 4600, solverConfig);

        OptimizationJob job = context.getJob(jobId);
        assertNotNull(job);
        assertEquals(OptimizationJobStatus.SUCCEEDED, job.getStatus());
        assertEquals(jobId, context.getLastJobId());
        assertEquals(jobId, context.getCurrentPlanId());
        assertEquals("rev-1", context.getCurrentRevisionId());
        assertEquals(result, context.getLastOptimizationResult());
        assertEquals(4600, context.getLastTotalWidth());
        assertEquals(solverConfig, context.getLastSolverConfig());
        assertEquals(1, context.getLastOrderItems().size());
    }

    @Test
    void createJobReusesExistingJobForSameIdempotencyKey() {
        OptimizationContext context = new OptimizationContext(new InMemoryOptimizationJobRepository());

        OptimizationContext.JobCreationResult first = context.createJob("REQ-1", "IDEMP-1");
        OptimizationContext.JobCreationResult second = context.createJob("REQ-2", "IDEMP-1");

        assertNotNull(first.job());
        assertEquals(first.job().getJobId(), second.job().getJobId());
        assertSame(first.job(), second.job());
        assertEquals("REQ-1", second.job().getRequestId());
        assertEquals("IDEMP-1", context.getJobByIdempotencyKey("IDEMP-1").getIdempotencyKey());
    }

    @Test
    void advanceRevisionIncrementsCurrentRevisionId() {
        OptimizationContext context = new OptimizationContext(new InMemoryOptimizationJobRepository());
        String jobId = context.createJob();

        CuttingOptimizationResult result = new CuttingOptimizationResult();
        SolverConfig solverConfig = new SolverConfig();
        context.completeJob(jobId, result, List.of(), 4600, solverConfig);

        assertEquals("rev-1", context.getCurrentRevisionId());
        assertEquals("rev-2", context.advanceRevision());
        assertEquals("rev-2", context.getCurrentRevisionId());
    }
}
