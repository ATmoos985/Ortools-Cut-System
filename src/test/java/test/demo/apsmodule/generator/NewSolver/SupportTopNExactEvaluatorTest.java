package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportTopNExactEvaluatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void topThreeIsRankedPerSupportAndExactEvidenceResumesAtomically()
            throws Exception {
        SupportCandidateSnapshotArchive.Snapshot snapshot = snapshot();
        SupportTopNExactEvaluator.TopNPlan plan =
                SupportTopNExactEvaluator.plan(snapshot, 3);

        assertEquals(2, plan.totalSupports());
        assertEquals(2, plan.depth(1).cumulativeCandidates().size());
        assertEquals(4, plan.depth(2).cumulativeCandidates().size());
        assertEquals(5, plan.depth(3).cumulativeCandidates().size());
        assertEquals(List.of("A1", "A2", "A3"),
                plan.buckets().get("support-A").stream()
                        .map(SupportTopNExactEvaluator.RankedCandidate
                                ::stateSignature)
                        .toList());

        FakeSolver solver = new FakeSolver();
        Path exactPath = temporaryDirectory.resolve("exact.tsv");
        SupportTopNExactEvaluator.ExactConfig config = config();
        SupportTopNExactEvaluator.ExactRun run =
                SupportTopNExactEvaluator.evaluateAndPersist(
                        plan,
                        3,
                        exactPath,
                        config,
                        0,
                        solver,
                        null);

        assertEquals(5, run.newlyEvaluated());
        assertEquals(5, run.checkedUniqueStates());
        assertEquals(5, run.stateDenominator());
        assertEquals(5, run.exactResults().byState().size());
        assertEquals(5, solver.firstCalls.size());
        assertEquals(Set.of("A2", "A3", "B1"),
                Set.copyOf(solver.secondCalls));
        assertEquals(Set.of("A2", "A3", "B2"),
                Set.copyOf(solver.fullCalls));
        assertEquals(
                SupportTopNExactEvaluator.FinalClassification
                        .TARGET_FEASIBLE,
                run.exactResults().byState().get("B2").classification());
        assertEquals(
                SupportTopNExactEvaluator.FinalClassification
                        .PROVEN_NOT_TARGET,
                run.exactResults().byState().get("A2").classification());
        assertEquals(
                SupportTopNExactEvaluator.FinalClassification.UNKNOWN,
                run.exactResults().byState().get("A3").classification());

        SupportTopNExactEvaluator.ExactRun replay =
                SupportTopNExactEvaluator.evaluateAndPersist(
                        plan,
                        3,
                        exactPath,
                        config,
                        0,
                        new FailingSolver(),
                        null);
        assertEquals(0, replay.newlyEvaluated());
        assertEquals(run.exactResults().payloadSha256(),
                replay.exactResults().payloadSha256());
        assertTrue(java.nio.file.Files.exists(exactPath));
    }

    @Test
    void exactBudgetStopsAfterRequestedNumberWithoutChangingPlanDenominator()
            throws Exception {
        SupportCandidateSnapshotArchive.Snapshot snapshot = snapshot();
        SupportTopNExactEvaluator.TopNPlan plan =
                SupportTopNExactEvaluator.plan(snapshot, 3);
        SupportTopNExactEvaluator.ExactRun run =
                SupportTopNExactEvaluator.evaluateAndPersist(
                        plan,
                        3,
                        temporaryDirectory.resolve("limited.tsv"),
                        config(),
                        2,
                        new FakeSolver(),
                        null);

        assertEquals(2, run.newlyEvaluated());
        assertEquals(2, run.checkedUniqueStates());
        assertEquals(5, run.stateDenominator());
        assertEquals(2,
                run.depthSummaries().get(0).evaluatedStates(),
                "rank-one states are evaluated before deeper ranks");
        assertEquals(0, run.depthSummaries().get(1).evaluatedStates()
                - run.depthSummaries().get(0).evaluatedStates());
    }

    private static SupportTopNExactEvaluator.ExactConfig config() {
        return new SupportTopNExactEvaluator.ExactConfig(
                27, 1, 0, 60_000, 180_000,
                300_000, 60_000, 100_000, -1);
    }

    private static SupportCandidateSnapshotArchive.Snapshot snapshot() {
        List<SupportCandidateSnapshotArchive.PersistedCandidate> candidates =
                List.of(
                        persisted("A1", "support-A", 30, 1, 0),
                        persisted("A2", "support-A", 31, 1, 0),
                        persisted("A3", "support-A", 29, 3, 0),
                        persisted("B1", "support-B", 32, 1, 0),
                        persisted("B2", "support-B", 33, 1, 0));
        return new SupportCandidateSnapshotArchive.Snapshot(
                "TEST",
                Map.of("generationConfigSha256", "config"),
                Map.of("createdAtUtc", "now"),
                candidates,
                "snapshot-hash");
    }

    private static SupportCandidateSnapshotArchive.PersistedCandidate persisted(
            String stateSignature,
            String supportIdentity,
            int groups,
            int odd,
            int one) {
        PatternCandidate removed = pattern(
                400 + stateSignature.hashCode() % 10, 100, 1);
        PatternCandidate added = pattern(
                500 + stateSignature.hashCode() % 10, 100, 1);
        Map<PatternCandidate, Integer> delta = new LinkedHashMap<>();
        delta.put(removed, -1);
        delta.put(added, 1);
        SparseProductionNeutralMoveEnumerator.Candidate candidate =
                new SparseProductionNeutralMoveEnumerator.Candidate(
                        delta,
                        Map.of(added, 1),
                        stateSignature,
                        "local-" + stateSignature,
                        "coeff-" + stateSignature);
        SupportBucketedCandidateEvaluator.FrozenCandidate frozen =
                new SupportBucketedCandidateEvaluator.FrozenCandidate(
                        candidate,
                        stateSignature,
                        supportIdentity,
                        Set.of(StructuredLocalPatternUniverseBuilder.UniverseScope
                                .WITNESS_DIRECT),
                        List.of(new SupportBucketedCandidateEvaluator
                                .SourceOccurrence(
                                StructuredLocalPatternUniverseBuilder
                                        .UniverseScope.WITNESS_DIRECT,
                                "local-" + stateSignature,
                                OptionalDouble.of(-3_300))),
                        Set.of(-3_300.0));
        return new SupportCandidateSnapshotArchive.PersistedCandidate(
                frozen,
                new ProductionNeutralMoveSearch.FastUpperBound(
                        ProductionNeutralMoveSearch.FastStatus.FEASIBLE,
                        groups,
                        odd,
                        one,
                        1));
    }

    private static PatternCandidate pattern(int rollWidth, int... values) {
        Map<Integer, Integer> cuts = new TreeMap<>();
        for (int index = 0; index < values.length; index += 2) {
            cuts.put(values[index], values[index + 1]);
        }
        return new PatternCandidate(cuts, rollWidth);
    }

    private static final class FakeSolver
            implements SupportTopNExactEvaluator.ExactSolver {

        private final List<String> firstCalls = new ArrayList<>();
        private final List<String> secondCalls = new ArrayList<>();
        private final List<String> fullCalls = new ArrayList<>();

        @Override
        public SupportTopNExactEvaluator.ThresholdObservation checkThreshold(
                SupportTopNExactEvaluator.RankedCandidate candidate,
                long timeLimitMs) {
            boolean first = timeLimitMs == 60_000;
            (first ? firstCalls : secondCalls).add(candidate.stateSignature());
            OrderCompatibilityKernelAnalyzer.ThresholdStatus status = switch (
                    candidate.stateSignature()) {
                case "A1" -> OrderCompatibilityKernelAnalyzer.ThresholdStatus
                        .INFEASIBLE;
                case "B1" -> first
                        ? OrderCompatibilityKernelAnalyzer.ThresholdStatus.UNKNOWN
                        : OrderCompatibilityKernelAnalyzer.ThresholdStatus
                                .INFEASIBLE;
                case "B2" -> OrderCompatibilityKernelAnalyzer.ThresholdStatus
                        .FEASIBLE;
                default -> OrderCompatibilityKernelAnalyzer.ThresholdStatus
                        .UNKNOWN;
            };
            return threshold(status);
        }

        @Override
        public SupportTopNExactEvaluator.FullObservation analyze(
                SupportTopNExactEvaluator.RankedCandidate candidate,
                long groupTimeLimitMs,
                long shapeTimeLimitMs) {
            fullCalls.add(candidate.stateSignature());
            return switch (candidate.stateSignature()) {
                case "A2" -> full(true, true, 28, 28, 28, 1, 0);
                case "B2" -> full(true, true, 27, 27, 27, 1, 0);
                default -> full(false, false, 27, 27, -1, 3, 1);
            };
        }
    }

    private static final class FailingSolver
            implements SupportTopNExactEvaluator.ExactSolver {

        @Override
        public SupportTopNExactEvaluator.ThresholdObservation checkThreshold(
                SupportTopNExactEvaluator.RankedCandidate candidate,
                long timeLimitMs) {
            throw new AssertionError("persisted evidence should be reused");
        }

        @Override
        public SupportTopNExactEvaluator.FullObservation analyze(
                SupportTopNExactEvaluator.RankedCandidate candidate,
                long groupTimeLimitMs,
                long shapeTimeLimitMs) {
            throw new AssertionError("persisted evidence should be reused");
        }
    }

    private static SupportTopNExactEvaluator.ThresholdObservation threshold(
            OrderCompatibilityKernelAnalyzer.ThresholdStatus status) {
        return new SupportTopNExactEvaluator.ThresholdObservation(
                status,
                "SCIP",
                status.name(),
                status == OrderCompatibilityKernelAnalyzer.ThresholdStatus
                        .FEASIBLE ? 27 : -1,
                status == OrderCompatibilityKernelAnalyzer.ThresholdStatus
                        .FEASIBLE ? 1 : -1,
                status == OrderCompatibilityKernelAnalyzer.ThresholdStatus
                        .FEASIBLE ? 0 : -1,
                10,
                20,
                30,
                40,
                5,
                10,
                15);
    }

    private static SupportTopNExactEvaluator.FullObservation full(
            boolean groupOptimal,
            boolean shapeOptimal,
            int feasible,
            int lower,
            int exact,
            int odd,
            int one) {
        return new SupportTopNExactEvaluator.FullObservation(
                groupOptimal
                        ? OrderCompatibilityKernelAnalyzer.Status.OPTIMAL
                        : OrderCompatibilityKernelAnalyzer.Status.FEASIBLE,
                "SCIP",
                groupOptimal ? "OPTIMAL" : "FEASIBLE",
                shapeOptimal ? "OPTIMAL" : "NOT_SOLVED",
                shapeOptimal ? "OPTIMAL" : "NOT_SOLVED",
                groupOptimal,
                shapeOptimal,
                feasible,
                lower,
                exact,
                exact < 0 ? -1 : exact - 2,
                odd,
                one,
                10,
                20,
                30,
                40,
                5,
                10,
                15,
                30);
    }
}
