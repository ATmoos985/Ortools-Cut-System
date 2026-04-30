package test.demo.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.OptimizationExecutionService;
import test.demo.apsmodule.service.OptimizationResultAssembler;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.rest.context.OptimizationContext;
import test.demo.rest.dto.OptimizationRequest;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/cutting")
@CrossOrigin(origins = "*")
public class CuttingOptimizationController {

    private static final Logger log = LoggerFactory.getLogger(CuttingOptimizationController.class);

    private final OptimizationContext optimizationContext;
    private final OptimizationExecutionService optimizationExecutionService;
    private final OptimizationResultAssembler optimizationResultAssembler;

    public CuttingOptimizationController(
            OptimizationContext optimizationContext,
            OptimizationExecutionService optimizationExecutionService,
            OptimizationResultAssembler optimizationResultAssembler) {
        this.optimizationContext = optimizationContext;
        this.optimizationExecutionService = optimizationExecutionService;
        this.optimizationResultAssembler = optimizationResultAssembler;
    }

    @PostMapping("/optimize")
    public ResponseEntity<Map<String, Object>> optimize(@RequestBody OptimizationRequest request) {
        log.info("========== /api/cutting/optimize ==========");
        return optimizeV2(request);
    }

    @PostMapping("/v2/optimize")
    public ResponseEntity<Map<String, Object>> optimizeV2(@RequestBody OptimizationRequest request) {
        String jobId = optimizationContext.createJob();
        try {
            log.info("========== /api/cutting/v2/optimize ==========");
            log.info("Order items: {}", request.getOrderItems().size());
            log.info("Mode: {}", request.isFlexibleWidth() ? "flexible" : "fixed");

            OptimizationExecutionService.ExecutionResult execution = optimizationExecutionService.execute(request);
            SolverConfig config = execution.config();
            CuttingOptimizationResult result = execution.result();

            log.info(
                    "Solver config: maxIterations={}, timeoutMs={}, useNewSolver={}",
                    config.getMaxIterations(),
                    config.getTimeoutMs(),
                    config.isUseNewSolver());

            optimizationContext.completeJob(
                    jobId,
                    result,
                    execution.request().getOrderItems(),
                    config.getTotalWidth(),
                    config);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Cutting optimization completed");
            response.put("jobId", jobId);
            response.put("result", optimizationResultAssembler.toOptimizationResponseV2(result, request.getOrderItems()));
            response.put("cacheReady", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            optimizationContext.failJob(jobId, e.getMessage());
            log.error("Cutting optimization failed", e);

            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "Cutting optimization failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResult);
        }
    }
}
