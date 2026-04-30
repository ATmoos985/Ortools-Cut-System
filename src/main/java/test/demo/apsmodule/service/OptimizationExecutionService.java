package test.demo.apsmodule.service;

import org.springframework.stereotype.Service;
import test.demo.rest.dto.OptimizationRequest;

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
        CuttingOptimizationResult result = optimizationService.optimizeUnified(
                ProductionOrderMapper.toProductionOrders(request.getOrderItems()),
                config);
        return new ExecutionResult(request, config, result);
    }
}
