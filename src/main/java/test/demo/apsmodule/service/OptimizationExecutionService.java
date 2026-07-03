package test.demo.apsmodule.service;

import org.springframework.stereotype.Service;
import test.demo.apsmodule.generator.NewSolver.output.LocalNeighborhoodSequenceOptimizer;
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
        Map<String, String> lnsProperties = new HashMap<>();
        lnsProperties.put("cutting.lns.enabled", "true");
        lnsProperties.put("cutting.lns.enrichPatterns", Boolean.toString(request.isLnsEnrichPatterns()));
        if (request.isQualityMode()) {
            // 质量模式：A层 parity{0,0.1} 扫描 + B层双 LNS 邻域变体，全候选经
            // isBetterPlan（车数→组→odd→small）评优——结构上永不劣于快路径，耗时约×2
            lnsProperties.put("cutting.quality", "true");
        }
        return LocalNeighborhoodSequenceOptimizer.withPropertyOverrides(
                lnsProperties,
                () -> runOptimization(request, config));
    }

    private CuttingOptimizationResult runOptimization(OptimizationRequest request, SolverConfig config) {
        CuttingOptimizationResult result = optimizationService.optimizeUnified(
                ProductionOrderMapper.toProductionOrders(request.getOrderItems()),
                config);
        return result;
    }
}
