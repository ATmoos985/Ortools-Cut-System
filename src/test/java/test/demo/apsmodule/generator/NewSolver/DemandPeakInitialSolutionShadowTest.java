package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakColumnPoolBuilder;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakInitialSolutionSolver;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in real-data check for the demand-peak constructive path.
 *
 * <p>The frozen candidate remains in the pool only as a feasibility boundary;
 * it is deliberately not passed as a MIP warm start. Therefore the result is
 * built by one peak-guided pool and one MIP, rather than by an LNS/SPR repair.
 * The test is disabled by default because it is a research run, not a fast
 * regression test.</p>
 */
class DemandPeakInitialSolutionShadowTest {

    private static final int TOTAL_WIDTH = 4600;

    @BeforeAll
    static void loadOrTools() {
        Loader.loadNativeLibraries();
    }

    @Test
    void constructsFromSixianFrozenArchive() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("cutting.test.demandPeakShadow"));
        run("sixian",
                "solver-experiments/sixian-candidate-42-2-12-81a1bb45.csv",
                "solver-experiments/sixian-columns.csv");
    }

    @Test
    void constructsFromT9FrozenArchive() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("cutting.test.demandPeakShadow"));
        run("t9",
                "solver-experiments/t9est188-candidate-66-8-26-1114da49.csv",
                "solver-experiments/t9est188-columns.csv");
    }

    private void run(String dataset, String snapshotName, String archiveName) throws Exception {
        Path snapshotPath = Path.of(snapshotName);
        Path archivePath = Path.of(archiveName);
        assertTrue(Files.exists(snapshotPath), "snapshot missing: " + snapshotPath);
        assertTrue(Files.exists(archivePath), "archive missing: " + archivePath);

        SolverExperimentSnapshot.Snapshot snapshot = SolverExperimentSnapshot.read(snapshotPath);
        Map<String, Integer> demand = SolverExperimentSnapshot.producedBy(snapshot.uses());
        List<UnifiedSetPartitionSolver.Column> protectedColumns = snapshot.uses().stream()
                .map(UnifiedSetPartitionSolver.ColumnUse::column)
                .toList();
        Map<String, UnifiedSetPartitionSolver.Column> generated = new LinkedHashMap<>();
        SolverExperimentSnapshot.readColumnArchive(archivePath).columns()
                .forEach(column -> generated.putIfAbsent(column.signature(), column));
        protectedColumns.forEach(column -> generated.putIfAbsent(column.signature(), column));

        DemandPeakColumnPoolBuilder.Config config = new DemandPeakColumnPoolBuilder.Config(
                3, 12, 96,
                Integer.getInteger("cutting.test.demandPeakBandLimit", 48),
                Integer.getInteger("cutting.test.demandPeakCount", 2),
                Integer.getInteger("cutting.test.demandPeakSeparationSteps", 3),
                10);
        DemandPeakInitialSolutionSolver.InitialSolution initial =
                new DemandPeakInitialSolutionSolver().solve(
                        generated.values(), demand, protectedColumns, config,
                        snapshot.metrics().cars(), snapshot.metrics().waste(), TOTAL_WIDTH,
                        Long.getLong("cutting.test.demandPeakTimeMs", 60_000L));

        assertTrue(initial.feasible(), dataset + " did not construct a feasible initial solution");
        assertEquals(demand, SolverExperimentSnapshot.producedBy(initial.solution().uses()));
        assertEquals(snapshot.metrics().cars(), initial.solution().cars());
        assertTrue(initial.solution().waste() <= snapshot.metrics().waste());
        System.out.printf("DEMAND-PEAK shadow: dataset=%s baseline=%d/%d/%d pool=%d "
                        + "peaks=%s result=%d/%d/one%d/%d status=%s nodes=%d elapsedMs=%d%n",
                dataset, snapshot.metrics().groups(), snapshot.metrics().oddGroups(),
                snapshot.metrics().smallGroups(), initial.pool().columns().size(),
                initial.pool().peaks(), initial.solution().groups(), initial.solution().oddBlocks(),
                initial.solution().oneCarBlocks(), initial.solution().smallBlocks(),
                initial.solution().status(), initial.solution().nodes(), initial.solution().elapsedMs());
    }
}
