package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportCandidateSnapshotArchiveTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void snapshotRoundTripsWithStableSemanticHashAndExplicitReplace()
            throws Exception {
        Fixture fixture = fixture();
        SupportCandidateSnapshotArchive.Snapshot first = archive(
                fixture, Map.of(
                        "createdAtUtc", "2026-07-22T00:00:00Z",
                        "producerGitHead", "first",
                        "producerWorktreeDirty", "true"));
        SupportCandidateSnapshotArchive.Snapshot second = archive(
                fixture, Map.of(
                        "createdAtUtc", "2026-07-23T00:00:00Z",
                        "producerGitHead", "second",
                        "producerWorktreeDirty", "false"));

        assertEquals(first.payloadSha256(), second.payloadSha256(),
                "volatile producer metadata must not change payload identity");

        Path path = temporaryDirectory.resolve("snapshot.tsv");
        SupportCandidateSnapshotArchive.Snapshot written =
                SupportCandidateSnapshotArchive.write(
                        path,
                        first,
                        SupportCandidateSnapshotArchive.WriteMode.CREATE,
                        fixture.validation());
        assertEquals(first.payloadSha256(), written.payloadSha256());
        assertEquals(2, written.candidates().size());
        assertEquals(1, written.supportCount());
        assertEquals(first.candidates().stream()
                        .map(value -> value.state().stateSignature()).toList(),
                written.candidates().stream()
                        .map(value -> value.state().stateSignature()).toList());

        assertThrows(FileAlreadyExistsException.class, () ->
                SupportCandidateSnapshotArchive.write(
                        path,
                        second,
                        SupportCandidateSnapshotArchive.WriteMode.CREATE,
                        fixture.validation()));

        SupportCandidateSnapshotArchive.Snapshot replaced =
                SupportCandidateSnapshotArchive.write(
                        path,
                        second,
                        SupportCandidateSnapshotArchive.WriteMode.REPLACE,
                        fixture.validation());
        assertEquals("second",
                replaced.producerMetadata().get("producerGitHead"));
        assertEquals(first.payloadSha256(), replaced.payloadSha256());
    }

    @Test
    void readRejectsTamperedStateAndMismatchedDemand() throws Exception {
        Fixture fixture = fixture();
        Path path = temporaryDirectory.resolve("snapshot.tsv");
        SupportCandidateSnapshotArchive.write(
                path,
                archive(fixture, Map.of("createdAtUtc", "now")),
                SupportCandidateSnapshotArchive.WriteMode.CREATE,
                fixture.validation());

        List<String> lines = new ArrayList<>(Files.readAllLines(
                path, StandardCharsets.UTF_8));
        int stateIndex = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith("STATE\t")) {
                stateIndex = index;
                break;
            }
        }
        assertTrue(stateIndex >= 0);
        String[] fields = lines.get(stateIndex).split("\\t", -1);
        fields[7] = Integer.toString(Integer.parseInt(fields[7]) + 1);
        lines.set(stateIndex, String.join("\t", fields));
        Files.write(path, lines, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () ->
                SupportCandidateSnapshotArchive.read(
                        path, fixture.validation()));

        Path cleanPath = temporaryDirectory.resolve("clean-snapshot.tsv");
        SupportCandidateSnapshotArchive.write(
                cleanPath,
                archive(fixture, Map.of("createdAtUtc", "now")),
                SupportCandidateSnapshotArchive.WriteMode.CREATE,
                fixture.validation());

        Map<Integer, Integer> wrongDemand = new TreeMap<>(
                fixture.validation().demands());
        wrongDemand.put(100, wrongDemand.get(100) + 1);
        SupportCandidateSnapshotArchive.ValidationContext wrong =
                new SupportCandidateSnapshotArchive.ValidationContext(
                        wrongDemand,
                        fixture.validation().baseline(),
                        fixture.validation().expectedCars(),
                        fixture.validation().expectedWaste(),
                        fixture.validation().totalWidth());
        assertThrows(IOException.class, () ->
                SupportCandidateSnapshotArchive.read(cleanPath, wrong));
    }

    private static SupportCandidateSnapshotArchive.Snapshot archive(
            Fixture fixture,
            Map<String, String> producer) throws IOException {
        Map<String, String> identity = new TreeMap<>();
        identity.put("expectedCars", "8");
        identity.put("expectedWaste", "1600");
        identity.put("totalWidth", "500");
        identity.put("demandSha256",
                SupportCandidateSnapshotArchive.hashDemands(
                        fixture.validation().demands()));
        identity.put("baselineSha256",
                SupportCandidateSnapshotArchive.sha256OfLines(List.of(
                        SupportCandidateSnapshotArchive.stateSignature(
                                fixture.validation().baseline()))));
        identity.put("sparseTotalMs", "20000");
        identity.put("generationConfigSha256",
                SupportCandidateSnapshotArchive.hashMetadata(identity));
        return SupportCandidateSnapshotArchive.create(
                "TEST",
                identity,
                producer,
                fixture.snapshot(),
                fixture.phase2ByState(),
                fixture.validation());
    }

    private static Fixture fixture() {
        PatternCandidate p1 = pattern(500, 100, 2);
        PatternCandidate p2 = pattern(500, 200, 2);
        PatternCandidate p3 = pattern(500, 100, 1, 200, 1);
        PatternCandidate p4 = pattern(510, 100, 1, 200, 1);
        Map<PatternCandidate, Integer> baseline = new LinkedHashMap<>();
        baseline.put(p1, 4);
        baseline.put(p2, 4);

        SupportBucketedCandidateEvaluator.FrozenCandidate stateOne = state(
                baseline, Map.of(p1, 3, p2, 3, p3, 1, p4, 1), -3_300.0);
        SupportBucketedCandidateEvaluator.FrozenCandidate stateTwo = state(
                baseline, Map.of(p1, 2, p2, 2, p3, 2, p4, 2), -3_299.0);
        String support = stateOne.supportIdentity();
        Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                SupportBucketedCandidateEvaluator.LayerCoverage> coverage =
                new EnumMap<>(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.class);
        coverage.put(StructuredLocalPatternUniverseBuilder.UniverseScope
                        .WITNESS_DIRECT,
                new SupportBucketedCandidateEvaluator.LayerCoverage(2, 1));
        SupportBucketedCandidateEvaluator.Snapshot snapshot =
                new SupportBucketedCandidateEvaluator.Snapshot(
                        List.of(stateTwo, stateOne),
                        List.of(stateOne, stateTwo),
                        Map.of(support, List.of(stateOne, stateTwo)),
                        coverage,
                        Map.of(-3_300.0, 1, -3_299.0, 1),
                        2,
                        0,
                        0,
                        2,
                        SupportBucketedCandidateEvaluator.SnapshotStatus
                                .MATCHES_HISTORICAL_ANCHOR);
        Map<String, ProductionNeutralMoveSearch.FastUpperBound> phase2 = Map.of(
                stateOne.stateSignature(),
                new ProductionNeutralMoveSearch.FastUpperBound(
                        ProductionNeutralMoveSearch.FastStatus.FEASIBLE,
                        34, 1, 0, 10),
                stateTwo.stateSignature(),
                new ProductionNeutralMoveSearch.FastUpperBound(
                        ProductionNeutralMoveSearch.FastStatus.FEASIBLE,
                        33, 1, 0, 20));
        SupportCandidateSnapshotArchive.ValidationContext validation =
                new SupportCandidateSnapshotArchive.ValidationContext(
                        Map.of(100, 8, 200, 8),
                        baseline,
                        8,
                        1_600,
                        500);
        return new Fixture(snapshot, phase2, validation);
    }

    private static SupportBucketedCandidateEvaluator.FrozenCandidate state(
            Map<PatternCandidate, Integer> baseline,
            Map<PatternCandidate, Integer> values,
            double objective) {
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        java.util.Comparator.comparing(
                                PatternCandidate::signature)))
                .forEach(entry -> solution.put(
                        entry.getKey(), entry.getValue()));
        Map<PatternCandidate, Integer> delta = new LinkedHashMap<>();
        Set<PatternCandidate> patterns = new java.util.LinkedHashSet<>(
                baseline.keySet());
        patterns.addAll(solution.keySet());
        patterns.stream()
                .sorted(java.util.Comparator.comparing(
                        PatternCandidate::signature))
                .forEach(pattern -> {
                    int change = solution.getOrDefault(pattern, 0)
                            - baseline.getOrDefault(pattern, 0);
                    if (change != 0) {
                        delta.put(pattern, change);
                    }
                });
        String signature = SupportCandidateSnapshotArchive.stateSignature(
                solution);
        SparseProductionNeutralMoveEnumerator.Candidate candidate =
                new SparseProductionNeutralMoveEnumerator.Candidate(
                        delta,
                        solution,
                        signature,
                        "local-support",
                        "coefficients");
        String support = SupportBucketedCandidateEvaluator.supportIdentity(
                candidate);
        return new SupportBucketedCandidateEvaluator.FrozenCandidate(
                candidate,
                signature,
                support,
                Set.of(StructuredLocalPatternUniverseBuilder.UniverseScope
                        .WITNESS_DIRECT),
                List.of(new SupportBucketedCandidateEvaluator.SourceOccurrence(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .WITNESS_DIRECT,
                        "local-support",
                        OptionalDouble.of(objective))),
                Set.of(objective));
    }

    private static PatternCandidate pattern(int rollWidth, int... values) {
        Map<Integer, Integer> cuts = new TreeMap<>();
        for (int index = 0; index < values.length; index += 2) {
            cuts.put(values[index], values[index + 1]);
        }
        return new PatternCandidate(cuts, rollWidth);
    }

    private record Fixture(
            SupportBucketedCandidateEvaluator.Snapshot snapshot,
            Map<String, ProductionNeutralMoveSearch.FastUpperBound>
                    phase2ByState,
            SupportCandidateSnapshotArchive.ValidationContext validation) {
    }
}
