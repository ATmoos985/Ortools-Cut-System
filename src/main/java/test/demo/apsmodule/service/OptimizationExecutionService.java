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
        OptimizationRequest.SolverProfile profile = resolveProfile(request);
        if (profile == null && !request.isLnsEnabled()) {
            return runOptimization(request, config);
        }
        Map<String, String> solverProperties = new HashMap<>();
        solverProperties.put("cutting.lns.enabled", "true");
        if (profile == null) {
            solverProperties.put(
                    "cutting.lns.enrichPatterns",
                    Boolean.toString(request.isLnsEnrichPatterns()));
        } else {
            solverProperties.put("cutting.lns.enrichPatterns", "false");
        }
        if (profile == OptimizationRequest.SolverProfile.FAST) {
            solverProperties.put("cutting.quality", "false");
            solverProperties.put("cutting.aLayerParityPenalties", "0");
            solverProperties.put("cutting.demandPeak.enabled", "true");
            solverProperties.put("cutting.demandPeak.smallPolish.enabled", "true");
            solverProperties.put("cutting.spr.enabled", "false");
        } else if (profile == OptimizationRequest.SolverProfile.QUALITY) {
            solverProperties.put("cutting.quality", "true");
            solverProperties.put("cutting.aLayerParityPenalties", "0,0.1");
            solverProperties.put("cutting.demandPeak.enabled", "false");
            solverProperties.put("cutting.spr.enabled", "true");
            solverProperties.put("cutting.spr.reverseTiePass", "true");
            solverProperties.put("cutting.spr.reverseTieMaxIterations", "1");
            solverProperties.put("cutting.spr.portfolioPass", "false");
        }
        return SolverRuntimeProperties.withOverrides(
                solverProperties,
                () -> runOptimization(request, config));
    }

    private OptimizationRequest.SolverProfile resolveProfile(OptimizationRequest request) {
        if (request.getSolverProfile() != null) {
            return request.getSolverProfile();
        }
        if (request.isUseNewSolver() || request.isQualityMode()) {
            return OptimizationRequest.SolverProfile.QUALITY;
        }
        return null;
    }

    private CuttingOptimizationResult runOptimization(OptimizationRequest request, SolverConfig config) {
        CuttingOptimizationResult result = optimizationService.optimizeUnified(
                ProductionOrderMapper.toProductionOrders(request.getOrderItems()),
                config);
        return result;
    }
}
