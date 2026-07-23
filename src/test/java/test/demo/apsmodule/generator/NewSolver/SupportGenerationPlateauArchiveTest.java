package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportGenerationPlateauArchiveTest {

    private static final Set<String> BASELINE = Set.of("A", "B", "Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripKeepsStablePayloadAndRecomputesStatistics() throws Exception {
        List<SupportGenerationPlateauArchive.RunObservation> runs =
                empiricalRuns();
        SupportGenerationPlateauArchive.Report first = report(
                runs, Map.of("createdAt", "first"));
        List<SupportGenerationPlateauArchive.RunObservation> reversed =
                new ArrayList<>(runs);
        Collections.reverse(reversed);
        SupportGenerationPlateauArchive.Report second = report(
                reversed, Map.of("createdAt", "second"));

        assertEquals(first.payloadSha256(), second.payloadSha256());
        assertEquals(
                SupportGenerationPlateauArchive.PlateauStatus.EMPIRICAL_PLATEAU,
                first.plateauStatus());
        SupportGenerationPlateauArchive.Analysis analysis =
                SupportGenerationPlateauArchive.analyze(first);
        assertEquals(Set.of("C"), analysis.novel60Vs20());
        assertTrue(analysis.novel180VsLower().isEmpty());
        assertEquals(3,
                analysis.budgetSummaries().get(20_000L).repetitions());
        assertEquals(0.0,
                analysis.budgetSummaries().get(20_000L)
                        .supportStats().sampleStandardDeviation(), 1e-9);
        assertEquals(1.0,
                analysis.budgetSummaries().get(180_000L)
                        .pairwiseJaccard().min(), 1e-9);

        Path path = temporaryDirectory.resolve("plateau.tsv");
        SupportGenerationPlateauArchive.Report written =
                SupportGenerationPlateauArchive.write(
                        path,
                        first,
                        SupportGenerationPlateauArchive.WriteMode.CREATE,
                        validation());
        assertEquals(first.payloadSha256(), written.payloadSha256());
        assertEquals(first,
                SupportGenerationPlateauArchive.read(path, validation()));
        assertThrows(FileAlreadyExistsException.class, () ->
                SupportGenerationPlateauArchive.write(
                        path,
                        first,
                        SupportGenerationPlateauArchive.WriteMode.CREATE,
                        validation()));

        List<String> tampered = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int index = 0; index < tampered.size(); index++) {
            if (tampered.get(index).startsWith("SUPPORT\t")) {
                tampered.set(index, tampered.get(index) + "A");
                break;
            }
        }
        Path corrupt = temporaryDirectory.resolve("corrupt.tsv");
        Files.write(corrupt, tampered, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () ->
                SupportGenerationPlateauArchive.read(corrupt, validation()));
    }

    @Test
    void classifiesAllFourEvidenceOutcomes() throws Exception {
        assertEquals(
                SupportGenerationPlateauArchive.PlateauStatus.EMPIRICAL_PLATEAU,
                report(empiricalRuns(), Map.of("run", "empirical"))
                        .plateauStatus());

        List<SupportGenerationPlateauArchive.RunObservation> growing =
                runsByBudget(
                        Map.of(
                                20_000L, List.of(Set.of("A", "B"),
                                        Set.of("A", "B"), Set.of("A", "B")),
                                60_000L, List.of(Set.of("A", "B", "C"),
                                        Set.of("A", "B", "C")),
                                180_000L, List.of(Set.of("A", "B", "C", "D"),
                                        Set.of("A", "B", "C", "D"))),
                        SparseProductionNeutralMoveEnumerator.Status.TIMED_OUT);
        assertEquals(
                SupportGenerationPlateauArchive.PlateauStatus.STILL_GROWING,
                report(growing, Map.of("run", "growing")).plateauStatus());

        List<SupportGenerationPlateauArchive.RunObservation> exhausted =
                new ArrayList<>(empiricalRuns());
        exhausted.removeIf(run -> run.budgetMs() == 180_000L);
        exhausted.add(run(180_000L, 1, Set.of("A", "B", "C"),
                SparseProductionNeutralMoveEnumerator.Status.EXHAUSTED));
        exhausted.add(run(180_000L, 2, Set.of("A", "B", "C"),
                SparseProductionNeutralMoveEnumerator.Status.EXHAUSTED));
        assertEquals(
                SupportGenerationPlateauArchive.PlateauStatus.PROVEN_EXHAUSTED,
                report(exhausted, Map.of("run", "exhausted"))
                        .plateauStatus());

        List<SupportGenerationPlateauArchive.RunObservation> unstable =
                runsByBudget(
                        Map.of(
                                20_000L, List.of(Set.of("A", "B"),
                                        Set.of("A", "B"), Set.of("A", "B")),
                                60_000L, List.of(Set.of("A", "B", "C"),
                                        Set.of("X", "Y", "Z")),
                                180_000L, List.of(Set.of("A", "B", "C"),
                                        Set.of("A", "B", "C"))),
                        SparseProductionNeutralMoveEnumerator.Status.TIMED_OUT);
        assertEquals(
                SupportGenerationPlateauArchive.PlateauStatus.INCONCLUSIVE,
                report(unstable, Map.of("run", "unstable"))
                        .plateauStatus());
    }

    @Test
    void baselineOverlapUsesSetIdentityInsteadOfCounts() {
        SupportGenerationPlateauArchive.RunObservation run = run(
                20_000L,
                1,
                Set.of("A", "B", "C"),
                SparseProductionNeutralMoveEnumerator.Status.TIMED_OUT);

        SupportGenerationPlateauArchive.BaselineOverlap overlap =
                SupportGenerationPlateauArchive.baselineOverlap(run, BASELINE);

        assertEquals(2, overlap.intersection());
        assertEquals(1, overlap.onlyRun());
        assertEquals(1, overlap.onlyBaseline());
        assertEquals(0.5, overlap.jaccard(), 1e-9);
    }

    @Test
    void excludesWallClockContaminatedRunButKeepsCleanGrowthEvidence()
            throws Exception {
        List<SupportGenerationPlateauArchive.RunObservation> runs =
                runsByBudget(
                        Map.of(
                                20_000L, List.of(Set.of("A", "B"),
                                        Set.of("A", "B"), Set.of("A", "B")),
                                60_000L, List.of(Set.of("A", "B", "C"),
                                        Set.of("A", "B", "C")),
                                180_000L, List.of(Set.of("A", "B", "C", "D"))),
                        SparseProductionNeutralMoveEnumerator.Status.TIMED_OUT);
        runs.add(run(
                180_000L,
                2,
                Set.of("A", "B", "C", "E"),
                SparseProductionNeutralMoveEnumerator.Status.TIMED_OUT,
                540_001L));

        SupportGenerationPlateauArchive.Analysis analysis =
                SupportGenerationPlateauArchive.analyze(
                        report(runs, Map.of("run", "suspend-contaminated")));

        assertEquals(
                SupportGenerationPlateauArchive.PlateauStatus.STILL_GROWING,
                analysis.status());
        assertTrue(analysis.incompleteRun());
        assertEquals(1,
                analysis.budgetSummaries().get(180_000L).repetitions());
        assertEquals(Set.of("D"), analysis.novel180VsLower());
        assertFalse(analysis.union180().contains("E"));
    }

    private static List<SupportGenerationPlateauArchive.RunObservation>
            empiricalRuns() {
        return runsByBudget(
                Map.of(
                        20_000L, List.of(Set.of("A", "B"),
                                Set.of("A", "B"), Set.of("A", "B")),
                        60_000L, List.of(Set.of("A", "B", "C"),
                                Set.of("A", "B", "C")),
                        180_000L, List.of(Set.of("A", "B", "C"),
                                Set.of("A", "B", "C"))),
                SparseProductionNeutralMoveEnumerator.Status.TIMED_OUT);
    }

    private static List<SupportGenerationPlateauArchive.RunObservation>
            runsByBudget(
                    Map<Long, List<Set<String>>> supportsByBudget,
                    SparseProductionNeutralMoveEnumerator.Status status) {
        List<SupportGenerationPlateauArchive.RunObservation> runs =
                new ArrayList<>();
        supportsByBudget.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    for (int index = 0;
                            index < entry.getValue().size(); index++) {
                        runs.add(run(entry.getKey(), index + 1,
                                entry.getValue().get(index), status));
                    }
                });
        return runs;
    }

    private static SupportGenerationPlateauArchive.RunObservation run(
            long budget,
            int repetition,
            Set<String> supports,
            SparseProductionNeutralMoveEnumerator.Status status) {
        return run(budget, repetition, supports, status, budget);
    }

    private static SupportGenerationPlateauArchive.RunObservation run(
            long budget,
            int repetition,
            Set<String> supports,
            SparseProductionNeutralMoveEnumerator.Status status,
            long elapsedMs) {
        Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                SupportGenerationPlateauArchive.LayerObservation> layers =
                new EnumMap<>(
                        StructuredLocalPatternUniverseBuilder.UniverseScope.class);
        int patternCount = 121;
        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            layers.put(scope,
                    new SupportGenerationPlateauArchive.LayerObservation(
                            scope,
                            patternCount,
                            status,
                            1,
                            supports.size(),
                            supports.size(),
                            supports.size(),
                            10,
                            5,
                            status == SparseProductionNeutralMoveEnumerator.Status
                                    .EXHAUSTED,
                            true,
                            elapsedMs));
            patternCount += 249;
        }
        Set<String> states = new TreeSet<>();
        supports.forEach(support -> states.add(
                "state-" + budget + "-" + repetition + "-" + support));
        return new SupportGenerationPlateauArchive.RunObservation(
                budget,
                repetition,
                "b" + budget + "-r" + repetition,
                layers,
                supports,
                states);
    }

    private static SupportGenerationPlateauArchive.Report report(
            List<SupportGenerationPlateauArchive.RunObservation> runs,
            Map<String, String> producer) throws IOException {
        return SupportGenerationPlateauArchive.create(
                "DJX188",
                "snapshot-hash",
                identity(),
                producer,
                BASELINE,
                runs,
                validation());
    }

    private static Map<String, String> identity() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("budgetPlan", "20000x3,60000x2,180000x2");
        values.put("randomSeed", "0");
        values.put("probeConfigSha256",
                SupportCandidateSnapshotArchive.hashMetadata(values));
        return Map.copyOf(values);
    }

    private static SupportGenerationPlateauArchive.ValidationContext
            validation() {
        return new SupportGenerationPlateauArchive.ValidationContext(
                "DJX188",
                "snapshot-hash",
                BASELINE,
                SupportGenerationPlateauArchive.DEFAULT_PLAN);
    }
}
