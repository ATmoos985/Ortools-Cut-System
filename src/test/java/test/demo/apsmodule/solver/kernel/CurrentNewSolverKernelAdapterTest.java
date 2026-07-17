package test.demo.apsmodule.solver.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.solver.CuttingSolverAlgorithm;
import test.demo.apsmodule.solver.kernel.execution.SolverExecutionContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentNewSolverKernelAdapterTest {

    @Test
    void delegatesWholeKernelRequestAndInstallsExecutionContext() {
        RecordingSolver delegate = new RecordingSolver();
        CurrentNewSolverKernelAdapter adapter = new CurrentNewSolverKernelAdapter(delegate);
        SolverConfig config = new SolverConfig();
        config.setUseNewSolver(true);
        SolverKernelRequest request = SolverKernelRequest.from(
                List.of(),
                config,
                SolverExecutionContext.of(Map.of("cutting.quality", "true")));

        SolverKernelResult result = adapter.solve(request);

        assertEquals(1, delegate.calls);
        assertTrue(delegate.qualityModeSeen);
        assertEquals(CurrentNewSolverKernelAdapter.KERNEL_ID, result.kernelId());
        assertEquals(SolverKernelStatus.SUCCEEDED, result.status());
    }

    @Test
    void reportsProductionQualityFromPatternMultiplicityAndUsageCount() {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setSubRolls(new LinkedHashMap<>(Map.of(1000, 2)));
        instruction.setUsageCount(2);
        instruction.setWaste(50);
        RecordingSolver delegate = new RecordingSolver();
        delegate.result = List.of(instruction);
        SolverOrderItem item = new SolverOrderItem();
        item.setWidth(1000);
        item.setDemand(3);
        SolverConfig config = new SolverConfig();
        config.setUseNewSolver(true);

        SolverKernelResult result = new CurrentNewSolverKernelAdapter(delegate).solve(
                SolverKernelRequest.from(List.of(item), config, SolverExecutionContext.empty()));

        assertEquals(1, result.quality().totalOverProduction());
        assertEquals(2, result.quality().totalRolls());
        assertEquals(100, result.quality().totalWaste());
        assertEquals(1, result.quality().patternCount());
    }

    private static final class RecordingSolver implements CuttingSolverAlgorithm {
        private int calls;
        private boolean qualityModeSeen;
        private List<CuttingInstruction> result = List.of();

        @Override
        public boolean supports(SolverConfig config) {
            return config.isUseNewSolver();
        }

        @Override
        public List<CuttingInstruction> solve(List<SolverOrderItem> items, SolverConfig config) {
            calls++;
            qualityModeSeen = SolverRuntimeProperties.getBoolean("cutting.quality", false);
            return result;
        }
    }
}
