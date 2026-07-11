package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.output.SetPartitionRefiner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetPartitionNeighborhoodExperimentTest {

    @BeforeAll
    static void loadOrTools() {
        com.google.ortools.Loader.loadNativeLibraries();
    }

    @Test
    void continuesFrozenSolutionWithAlternateDonorNeighborhood() throws Exception {
        String snapshotName = System.getProperty("cutting.test.refineSnapshot", "").trim();
        Assumptions.assumeTrue(!snapshotName.isEmpty(),
                "set cutting.test.refineSnapshot to run the neighborhood experiment");
        Path snapshotPath = Path.of(snapshotName);
        Assumptions.assumeTrue(Files.exists(snapshotPath),
                "snapshot does not exist: " + snapshotPath);

        Path lockPath = Path.of(System.getProperty(
                "cutting.test.experimentLock", "solver-experiments/solver.lock"));
        try (SolverExperimentGuard ignored = SolverExperimentGuard.acquire(lockPath)) {
            SolverExperimentSnapshot.Snapshot snapshot =
                    SolverExperimentSnapshot.read(snapshotPath);
            int totalWidth = Integer.getInteger("cutting.test.refineTotalWidth", 4600);
            int donorCount = Integer.getInteger("cutting.test.refineDonors", 4);
            String donorVariant = System.getProperty(
                    "cutting.test.refineDonorVariant", "DEFAULT");
            SetPartitionRefiner.ColumnRefineResult result =
                    SetPartitionRefiner.refineColumnUses(
                            snapshot.uses(), totalWidth, donorCount,
                            Long.getLong("cutting.test.refineSubproblemMs", 40_000L),
                            Long.getLong("cutting.test.refineTotalMs", 180_000L),
                            Integer.getInteger("cutting.test.refineMaxIterations", 20),
                            donorVariant);

            SolverExperimentSnapshot.Metrics before = snapshot.metrics();
            SolverExperimentSnapshot.Metrics after =
                    SolverExperimentSnapshot.metrics(result.uses(), totalWidth);
            assertEquals(SolverExperimentSnapshot.producedBy(snapshot.uses()),
                    SolverExperimentSnapshot.producedBy(result.uses()));
            assertEquals(before.cars(), after.cars());
            assertTrue(after.waste() <= before.waste());
            assertTrue(lexNotWorse(after, before));

            Path outputDir = Path.of(System.getProperty(
                    "cutting.test.candidateOutputDir", "solver-experiments"));
            String hash = Integer.toUnsignedString(result.uses().stream()
                    .map(use -> use.column().signature() + "#" + use.count())
                    .sorted()
                    .collect(Collectors.joining("|")).hashCode(), 16);
            Path candidatePath = outputDir.resolve(snapshot.dataset() + "-donor"
                    + donorCount + "-" + donorVariant.toLowerCase() + "-"
                    + after.groups() + "-" + after.oddGroups()
                    + "-" + after.smallGroups() + "-" + hash + ".csv");
            SolverExperimentSnapshot.write(candidatePath,
                    snapshot.dataset() + "-donor" + donorCount,
                    result.uses(), totalWidth);
            List<Column> archiveColumns = new ArrayList<>(result.discoveredColumns());
            archiveColumns.addAll(result.uses().stream().map(ColumnUse::column).toList());
            String archiveName = System.getProperty("cutting.test.refineArchive",
                    "solver-experiments/" + baseDataset(snapshot.dataset()) + "-columns.csv");
            SolverExperimentSnapshot.ColumnArchive archive =
                    SolverExperimentSnapshot.mergeColumnArchive(
                            Path.of(archiveName), baseDataset(snapshot.dataset()), archiveColumns);

            System.out.printf("SPR NEIGHBORHOOD: snapshot=%s donors=%d variant=%s before=%d/%d/%d "
                            + "after=%d/%d/%d elapsedMs=%d accepted=%d solverCalls=%d "
                            + "archiveColumns=%d candidate=%s%n",
                    snapshotPath, donorCount, donorVariant,
                    before.groups(), before.oddGroups(), before.smallGroups(),
                    after.groups(), after.oddGroups(), after.smallGroups(),
                    result.elapsedMs(), result.acceptedMoves(), result.solverCalls(),
                    archive.columns().size(), candidatePath);
        }
    }

    @Test
    void repairsFrozenSingleCarNeighborhood() throws Exception {
        String snapshotName = System.getProperty("cutting.test.oneCarSnapshot", "").trim();
        Assumptions.assumeTrue(!snapshotName.isEmpty(),
                "set cutting.test.oneCarSnapshot to run the one-car experiment");
        SolverExperimentSnapshot.Snapshot snapshot =
                SolverExperimentSnapshot.read(Path.of(snapshotName));
        int totalWidth = Integer.getInteger("cutting.test.oneCarTotalWidth", 4600);

        long startedAt = System.currentTimeMillis();
        List<ColumnUse> result = SetPartitionRefiner.polishOneCarNeighborhoodForResearch(
                snapshot.uses(), totalWidth,
                Integer.getInteger("cutting.test.oneCarDonors", 7),
                Long.getLong("cutting.test.oneCarTimeMs", 60_000L));
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertEquals(SolverExperimentSnapshot.producedBy(snapshot.uses()),
                SolverExperimentSnapshot.producedBy(result));
        SolverExperimentSnapshot.Metrics after =
                SolverExperimentSnapshot.metrics(result, totalWidth);
        long beforeOne = snapshot.uses().stream().filter(use -> use.count() == 1).count();
        long afterOne = result.stream().filter(use -> use.count() == 1).count();
        assertTrue(after.groups() <= snapshot.metrics().groups());
        assertTrue(after.oddGroups() <= snapshot.metrics().oddGroups());
        System.out.printf("ONE-CAR NEIGHBORHOOD: donors=%d before=%d/%d/one%d/%d "
                        + "after=%d/%d/one%d/%d elapsedMs=%d%n",
                Integer.getInteger("cutting.test.oneCarDonors", 7),
                snapshot.metrics().groups(), snapshot.metrics().oddGroups(), beforeOne,
                snapshot.metrics().smallGroups(), after.groups(), after.oddGroups(), afterOne,
                after.smallGroups(), elapsedMs);
    }

    private static boolean lexNotWorse(SolverExperimentSnapshot.Metrics candidate,
            SolverExperimentSnapshot.Metrics baseline) {
        return candidate.groups() < baseline.groups()
                || (candidate.groups() == baseline.groups()
                        && (candidate.oddGroups() < baseline.oddGroups()
                                || (candidate.oddGroups() == baseline.oddGroups()
                                        && candidate.smallGroups() <= baseline.smallGroups())));
    }

    private static String baseDataset(String dataset) {
        int marker = dataset.indexOf("-candidate");
        return marker > 0 ? dataset.substring(0, marker) : dataset;
    }
}
