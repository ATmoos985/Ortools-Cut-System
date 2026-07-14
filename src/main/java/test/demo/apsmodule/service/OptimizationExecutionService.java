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
        boolean qualityProfile = request.isUseNewSolver() || request.isQualityMode();
        if (!request.isLnsEnabled() && !qualityProfile) {
            return runOptimization(request, config);
        }
        Map<String, String> solverProperties = new HashMap<>();
        solverProperties.put("cutting.lns.enabled", "true");
        solverProperties.put("cutting.lns.enrichPatterns", Boolean.toString(request.isLnsEnrichPatterns()));
        if (qualityProfile) {
            solverProperties.put("cutting.quality", "true");
            // NewSolver exposes one request-scoped quality profile to both UI and APS callers.
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
