package test.demo.apsmodule.solver.kernel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.solver.CuttingSolverAlgorithm;

/**
 * Adapter that exposes the current OR-Tools NewSolver through the stable whole-kernel contract.
 */
@Component("newSolverKernel")
public final class CurrentNewSolverKernelAdapter implements SolverKernel {

    public static final String KERNEL_ID = "newsolver-ortools-9.10";

    private final CuttingSolverAlgorithm delegate;

    public CurrentNewSolverKernelAdapter(
            @Qualifier("cuttingSolver") CuttingSolverAlgorithm delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return KERNEL_ID;
    }

    @Override
    public SolverKernelCapabilities capabilities() {
        return SolverKernelCapabilities.currentNewSolver();
    }

    @Override
    public boolean supports(SolverKernelSettings settings) {
        return settings.useNewSolver();
    }

    @Override
    public SolverKernelResult solve(SolverKernelRequest request) {
        long startedAt = System.currentTimeMillis();
        List<SolverOrderItem> items = request.toSolverOrderItems();
        List<CuttingInstruction> instructions = SolverRuntimeProperties.withContext(
                request.executionContext(),
                () -> delegate.solve(items, request.settings().toSolverConfig()));
        PlanQuality quality = calculateQuality(items, instructions);
        return new SolverKernelResult(
                id(),
                SolverKernelStatus.SUCCEEDED,
                instructions,
                quality,
                System.currentTimeMillis() - startedAt,
                Map.of("adapter", getClass().getSimpleName()));
    }

    private static PlanQuality calculateQuality(List<SolverOrderItem> items,
            List<CuttingInstruction> instructions) {
        int totalRolls = instructions.stream().mapToInt(CuttingInstruction::getUsageCount).sum();
        int totalWaste = instructions.stream()
                .mapToInt(instruction -> instruction.getWaste() * instruction.getUsageCount())
                .sum();
        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);
        Map<Integer, Integer> demandByWidth = new HashMap<>();
        for (SolverOrderItem item : items) {
            demandByWidth.merge(item.getWidth(), item.getDemand(), Integer::sum);
        }
        Map<Integer, Integer> producedByWidth = new HashMap<>();
        for (CuttingInstruction instruction : instructions) {
            for (Map.Entry<Integer, Integer> entry : instruction.getSubRolls().entrySet()) {
                producedByWidth.merge(
                        entry.getKey(),
                        entry.getValue() * instruction.getUsageCount(),
                        Integer::sum);
            }
        }
        int totalOver = producedByWidth.entrySet().stream()
                .mapToInt(entry -> Math.max(0,
                        entry.getValue() - demandByWidth.getOrDefault(entry.getKey(), 0)))
                .sum();
        return new PlanQuality(
                totalOver,
                totalRolls,
                stats.groups(),
                stats.oddCarGroups(),
                stats.oneCarGroups(),
                stats.smallCarGroups(),
                instructions.size(),
                totalWaste,
                0);
    }
}
