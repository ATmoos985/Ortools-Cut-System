package test.demo.apsmodule.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import test.demo.apsmodule.api.dto.ApsOptimizationModels;
import test.demo.rest.context.OptimizationContext;
import test.demo.rest.dto.OptimizationRequest;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.OptimizationExecutionService;
import test.demo.apsmodule.service.OptimizationJob;

import java.util.concurrent.Executor;

@Service
public class ApsOptimizationJobService {

    private static final Logger log = LoggerFactory.getLogger(ApsOptimizationJobService.class);

    private final ApsOptimizationRequestMapper requestMapper;
    private final OptimizationExecutionService optimizationExecutionService;
    private final OptimizationContext optimizationContext;
    private final Executor apsJobExecutor;

    public ApsOptimizationJobService(
            ApsOptimizationRequestMapper requestMapper,
            OptimizationExecutionService optimizationExecutionService,
            OptimizationContext optimizationContext,
            @Qualifier("apsJobExecutor") Executor apsJobExecutor) {
        this.requestMapper = requestMapper;
        this.optimizationExecutionService = optimizationExecutionService;
        this.optimizationContext = optimizationContext;
        this.apsJobExecutor = apsJobExecutor;
    }

    public OptimizationJob submitJob(ApsOptimizationModels.CreateJobRequest request) {
        OptimizationContext.JobCreationResult creation = optimizationContext.createJob(
                request.requestId(),
                request.idempotencyKey());

        if (creation.created()) {
            apsJobExecutor.execute(() -> runOptimization(creation.job().getJobId(), request));
        } else {
            log.info("APS job {} reused for idempotencyKey {}", creation.job().getJobId(), request.idempotencyKey());
        }

        return creation.job();
    }

    public OptimizationJob getJob(String jobId) {
        return optimizationContext.getJob(jobId);
    }

    public CuttingOptimizationResult optimizeDirect(ApsOptimizationModels.CreateJobRequest request) {
        OptimizationRequest mappedRequest = requestMapper.toOptimizationRequest(request);
        return optimizationExecutionService.execute(mappedRequest).result();
    }

    private void runOptimization(String jobId, ApsOptimizationModels.CreateJobRequest request) {
        try {
            OptimizationRequest mappedRequest = requestMapper.toOptimizationRequest(request);
            OptimizationExecutionService.ExecutionResult execution = optimizationExecutionService.execute(mappedRequest);
            optimizationContext.completeJob(
                    jobId,
                    execution.result(),
                    execution.request().getOrderItems(),
                    execution.config().getTotalWidth(),
                    execution.config());
            log.info("APS job {} completed", jobId);
        } catch (Exception e) {
            optimizationContext.failJob(jobId, e.getMessage());
            log.error("APS job {} failed", jobId, e);
        }
    }
}
