package test.demo.apsmodule.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import test.demo.apsmodule.api.dto.ApsOptimizationModels;
import test.demo.rest.context.OptimizationContext;
import test.demo.rest.dto.OptimizationRequest;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.InMemoryOptimizationJobRepository;
import test.demo.apsmodule.service.OptimizationExecutionService;
import test.demo.apsmodule.service.OptimizationJob;
import test.demo.apsmodule.service.OptimizationJobStatus;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.excel.ExcelImportService;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApsOptimizationJobServiceTest {

    @Mock
    private ApsOptimizationRequestMapper requestMapper;

    @Mock
    private OptimizationExecutionService optimizationExecutionService;

    @Test
    void submitJobCompletesSuccessfullyWhenExecutorRunsInline() {
        OptimizationContext context = new OptimizationContext(new InMemoryOptimizationJobRepository());
        Executor directExecutor = Runnable::run;
        ApsOptimizationJobService service = new ApsOptimizationJobService(
                requestMapper,
                optimizationExecutionService,
                context,
                directExecutor);

        ApsOptimizationModels.CreateJobRequest request = new ApsOptimizationModels.CreateJobRequest(
                "REQ-1",
                "IDEMP-1",
                List.of(new ApsOptimizationModels.Order("SO-1", 1200, 3, 1000, "T1", "Alice", null, null)),
                null);

        ExcelImportService.OrderItem orderItem = new ExcelImportService.OrderItem();
        orderItem.setMessageText("SO-1");
        orderItem.setWidth(1200);
        orderItem.setQuantity(3);
        orderItem.setLength(1000);
        orderItem.setSurfaceTreatment("T1");

        OptimizationRequest mapped = new OptimizationRequest();
        mapped.setOrderItems(List.of(orderItem));

        SolverConfig config = new SolverConfig();
        config.setTotalWidth(4600);

        CuttingOptimizationResult result = new CuttingOptimizationResult();
        result.setTotalWidth(4600);

        when(requestMapper.toOptimizationRequest(any())).thenReturn(mapped);
        when(optimizationExecutionService.execute(any())).thenReturn(
                new OptimizationExecutionService.ExecutionResult(mapped, config, result));

        OptimizationJob job = service.submitJob(request);

        assertNotNull(job);
        OptimizationJob storedJob = context.getJob(job.getJobId());
        assertNotNull(storedJob);
        assertEquals(OptimizationJobStatus.SUCCEEDED, storedJob.getStatus());
        assertEquals(result, storedJob.getOptimizationResult());
        assertEquals("REQ-1", storedJob.getRequestId());
        assertEquals("IDEMP-1", storedJob.getIdempotencyKey());
    }

    @Test
    void submitJobMarksFailureWhenOptimizationThrows() {
        OptimizationContext context = new OptimizationContext(new InMemoryOptimizationJobRepository());
        Executor directExecutor = Runnable::run;
        ApsOptimizationJobService service = new ApsOptimizationJobService(
                requestMapper,
                optimizationExecutionService,
                context,
                directExecutor);

        ApsOptimizationModels.CreateJobRequest request = new ApsOptimizationModels.CreateJobRequest(
                "REQ-1",
                "IDEMP-1",
                List.of(new ApsOptimizationModels.Order("SO-1", 1200, 3, 1000, "T1", "Alice", null, null)),
                null);

        OptimizationRequest mapped = new OptimizationRequest();
        mapped.setOrderItems(List.of());

        when(requestMapper.toOptimizationRequest(any())).thenReturn(mapped);
        when(optimizationExecutionService.execute(any())).thenThrow(new IllegalStateException("boom"));

        OptimizationJob job = service.submitJob(request);

        OptimizationJob storedJob = context.getJob(job.getJobId());
        assertNotNull(storedJob);
        assertEquals(OptimizationJobStatus.FAILED, storedJob.getStatus());
        assertEquals("boom", storedJob.getErrorMessage());
    }

    @Test
    void submitJobReusesExistingJobWhenIdempotencyKeyMatches() {
        OptimizationContext context = new OptimizationContext(new InMemoryOptimizationJobRepository());
        Executor directExecutor = Runnable::run;
        ApsOptimizationJobService service = new ApsOptimizationJobService(
                requestMapper,
                optimizationExecutionService,
                context,
                directExecutor);

        ApsOptimizationModels.CreateJobRequest request = new ApsOptimizationModels.CreateJobRequest(
                "REQ-1",
                "IDEMP-1",
                List.of(new ApsOptimizationModels.Order("SO-1", 1200, 3, 1000, "T1", "Alice", null, null)),
                null);

        OptimizationRequest mapped = new OptimizationRequest();
        mapped.setOrderItems(List.of());

        SolverConfig config = new SolverConfig();
        CuttingOptimizationResult result = new CuttingOptimizationResult();

        when(requestMapper.toOptimizationRequest(any())).thenReturn(mapped);
        when(optimizationExecutionService.execute(any())).thenReturn(
                new OptimizationExecutionService.ExecutionResult(mapped, config, result));

        OptimizationJob first = service.submitJob(request);
        OptimizationJob second = service.submitJob(request);

        assertEquals(first.getJobId(), second.getJobId());
        verify(optimizationExecutionService, times(1)).execute(any());
    }

    @Test
    void optimizeDirectReturnsOptimizationResult() {
        OptimizationContext context = new OptimizationContext(new InMemoryOptimizationJobRepository());
        Executor directExecutor = Runnable::run;
        ApsOptimizationJobService service = new ApsOptimizationJobService(
                requestMapper,
                optimizationExecutionService,
                context,
                directExecutor);

        ApsOptimizationModels.CreateJobRequest request = new ApsOptimizationModels.CreateJobRequest(
                "REQ-1",
                "IDEMP-1",
                List.of(new ApsOptimizationModels.Order("SO-1", 1200, 3, 1000, "T1", "Alice", null, null)),
                null);

        OptimizationRequest mapped = new OptimizationRequest();
        mapped.setOrderItems(List.of());

        SolverConfig config = new SolverConfig();
        CuttingOptimizationResult result = new CuttingOptimizationResult();

        when(requestMapper.toOptimizationRequest(any())).thenReturn(mapped);
        when(optimizationExecutionService.execute(any())).thenReturn(
                new OptimizationExecutionService.ExecutionResult(mapped, config, result));

        CuttingOptimizationResult actual = service.optimizeDirect(request);

        assertEquals(result, actual);
    }
}
