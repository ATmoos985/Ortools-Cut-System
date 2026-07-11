package test.demo.apsmodule.service;

import org.springframework.stereotype.Service;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.rest.dto.OptimizationRequest;

import java.util.HashMap;
import java.util.Map;

@Service
public class OptimizationExecutionService {

    public record ExecutionResult(
            OptimizationRequest request,
            SolverConfig config,
            CuttingOptimizationResult result) {
    }

    private final CuttingOptimizationService optimizationService;
    private final SolverConfigFactory solverConfigFactory;

    public OptimizationExecutionService(
            CuttingOptimizationService optimizationService,
            SolverConfigFactory solverConfigFactory) {
        this.optimizationService = optimizationService;
        this.solverConfigFactory = solverConfigFactory;
    }

    public ExecutionResult execute(OptimizationRequest request) {
        SolverConfig config = solverConfigFactory.fromOptimizationRequest(request);
        CuttingOptimizationResult result = executeOptimization(request, config);
        return new ExecutionResult(request, config, result);
    }

    private CuttingOptimizationResult executeOptimization(OptimizationRequest request, SolverConfig config) {
        if (!request.isLnsEnabled() && !request.isQualityMode()) {
            return runOptimization(request, config);
        }
        Map<String, String> solverProperties = new HashMap<>();
        solverProperties.put("cutting.lns.enabled", "true");
        solverProperties.put("cutting.lns.enrichPatterns", Boolean.toString(request.isLnsEnrichPatterns()));
        if (request.isQualityMode()) {
            solverProperties.put("cutting.quality", "true");
            // Exact solve is one request-scoped profile for both the UI and APS callers.
            solverProperties.put("cutting.spr.reverseTiePass", "true");
            solverProperties.put("cutting.spr.reverseTieMaxIterations", "1");
            solverProperties.put("cutting.spr.portfolioPass", "false");
        }
        return SolverRuntimeProperties.withOverrides(
                solverProperties,
                () -> runOptimization(request, config));
    }

    private CuttingOptimizationResult runOptimization(OptimizationRequest request, SolverConfig config) {
        CuttingOptimizationResult result = optimizationService.optimizeUnified(
                ProductionOrderMapper.toProductionOrders(request.getOrderItems()),
                config);
        return result;
    }
}
