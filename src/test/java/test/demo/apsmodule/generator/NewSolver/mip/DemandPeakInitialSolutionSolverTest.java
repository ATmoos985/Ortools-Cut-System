package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakColumnPoolBuilder.Config;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemandPeakInitialSolutionSolverTest {

    @BeforeAll
    static void loadOrTools() {
        com.google.ortools.Loader.loadNativeLibraries();
    }

    @Test
    void buildsAndSolvesOnePeakGuidedPoolUnderFixedCarsAndWaste() {
        Column together = column(List.of("A", "B"));
        Column onlyA = column(List.of("A", "A"));
        Column onlyB = column(List.of("B", "B"));

        DemandPeakInitialSolutionSolver.InitialSolution result =
                new DemandPeakInitialSolutionSolver().solve(
                        List.of(together, onlyA, onlyB),
                        Map.of("1000|A", 4, "1000|B", 4),
                        List.of(),
                        new Config(2, 4, 8, 8, 1, 0, 10),
                        4, 0, 2000, 10_000L);

        assertTrue(result.feasible());
        assertEquals(4, result.solution().cars());
        assertEquals(0, result.solution().waste());
        assertEquals(1, result.solution().groups());
        assertEquals(2000, result.pool().peaks().get(0).patternWidth());
    }

    private static Column column(List<String> messages) {
        return Column.of(Map.of(1000, messages.size()), Map.of(1000, messages));
    }
}
