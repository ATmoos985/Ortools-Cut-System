package test.demo.apsmodule.solver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.solver.kernel.PlanQuality;
import test.demo.apsmodule.solver.kernel.SolverKernel;
import test.demo.apsmodule.solver.kernel.SolverKernelCapabilities;
import test.demo.apsmodule.solver.kernel.SolverKernelRequest;
import test.demo.apsmodule.solver.kernel.SolverKernelResult;
import test.demo.apsmodule.solver.kernel.SolverKernelSettings;
import test.demo.apsmodule.solver.kernel.SolverKernelStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnifiedPatternSolverTest {

    @Test
    void usesNewSolverWhenFlagIsEnabled() {
        RecordingKernel newSolver = new RecordingKernel();
        RecordingSolver fixedSolver = new RecordingSolver("fixed");
        RecordingSolver flexibleSolver = new RecordingSolver("flexible");
        UnifiedPatternSolver solver = new UnifiedPatternSolver(newSolver, fixedSolver, flexibleSolver);

        SolverConfig config = new SolverConfig();
        config.setUseNewSolver(true);
        config.setMode("fixed");

        solver.solve(Collections.emptyList(), config, Collections.emptyList());

        assertEquals(1, newSolver.calls);
        assertEquals(0, fixedSolver.calls);
        assertEquals(0, flexibleSolver.calls);
    }

    @Test
    void usesFixedSolverWhenNewSolverIsDisabledInFixedMode() {
        RecordingKernel newSolver = new RecordingKernel();
        RecordingSolver fixedSolver = new RecordingSolver("fixed");
        RecordingSolver flexibleSolver = new RecordingSolver("flexible");
        UnifiedPatternSolver solver = new UnifiedPatternSolver(newSolver, fixedSolver, flexibleSolver);

        SolverConfig config = new SolverConfig();
        config.setUseNewSolver(false);
        config.setMode("fixed");

        solver.solve(Collections.emptyList(), config, Collections.emptyList());

        assertEquals(0, newSolver.calls);
        assertEquals(1, fixedSolver.calls);
        assertEquals(0, flexibleSolver.calls);
    }

    @Test
    void usesFlexibleSolverWhenNewSolverIsDisabledInVariableMode() {
        RecordingKernel newSolver = new RecordingKernel();
        RecordingSolver fixedSolver = new RecordingSolver("fixed");
        RecordingSolver flexibleSolver = new RecordingSolver("flexible");
        UnifiedPatternSolver solver = new UnifiedPatternSolver(newSolver, fixedSolver, flexibleSolver);

        SolverConfig config = new SolverConfig();
        config.setUseNewSolver(false);
        config.setMode("variable");

        solver.solve(Collections.emptyList(), config, Collections.emptyList());

        assertEquals(0, newSolver.calls);
        assertEquals(0, fixedSolver.calls);
        assertEquals(1, flexibleSolver.calls);
    }

    private static final class RecordingSolver implements CuttingSolverAlgorithm {
        private final List<CuttingInstruction> result = List.of();
        private final String name;
        private int calls;

        private RecordingSolver(String name) {
            this.name = name;
        }

        @Override
        public boolean supports(SolverConfig config) {
            return true;
        }

        @Override
        public List<CuttingInstruction> solve(List<SolverOrderItem> items, SolverConfig config) {
            calls++;
            return result;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class RecordingKernel implements SolverKernel {
        private int calls;

        @Override
        public String id() {
            return "recording";
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
            calls++;
            return new SolverKernelResult(
                    id(),
                    SolverKernelStatus.SUCCEEDED,
                    List.of(),
                    new PlanQuality(0, 0, 0, 0, 0, 0, 0, 0, 0),
                    0,
                    Map.of());
        }
    }
}
