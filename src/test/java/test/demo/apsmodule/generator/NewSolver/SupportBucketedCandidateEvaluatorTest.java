package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportBucketedCandidateEvaluatorTest {

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void freezesStatesAcrossLayersAndUsesGlobalSignedSupportIdentity() {
        Fixture fixture = fixture();

        SupportBucketedCandidateEvaluator.Snapshot snapshot =
                SupportBucketedCandidateEvaluator.freeze(
                        fixture.runs(), 7);

        assertEquals(
                SupportBucketedCandidateEvaluator.SnapshotStatus
                        .MATCHES_HISTORICAL_ANCHOR,
                snapshot.status());
        assertEquals(8, snapshot.rawCandidateOccurrences());
        assertEquals(7, snapshot.staticOrder().size());
        assertEquals(1, snapshot.crossLayerDuplicateOccurrences());
        assertEquals(5, snapshot.supportDenominator());
        assertEquals(0, snapshot.unmappedObjectiveOccurrences());

        SupportBucketedCandidateEvaluator.FrozenCandidate stateOne =
                state(snapshot, "s1");
        assertEquals(Set.of(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .WITNESS_DIRECT,
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .NEUTRAL_CLOSURE),
                stateOne.scopes());
        assertEquals(2, stateOne.occurrences().size());
        assertEquals(Set.of(-3_300.0, -2_200.0),
                stateOne.masterObjectives());

        assertEquals(
                SupportBucketedCandidateEvaluator.supportIdentity(
                        fixture.stateOne()),
                SupportBucketedCandidateEvaluator.supportIdentity(
                        fixture.stateTwo()));
        assertEquals(
                SupportBucketedCandidateEvaluator.supportIdentity(
                        fixture.stateOne()),
                SupportBucketedCandidateEvaluator.supportIdentity(
                        fixture.stateOneDuplicate()));
        assertFalse(
                SupportBucketedCandidateEvaluator.supportIdentity(
                        fixture.stateOne()).equals(
                        SupportBucketedCandidateEvaluator.supportIdentity(
                                fixture.reversedState())));

        assertEquals(
                new SupportBucketedCandidateEvaluator.LayerCoverage(3, 1),
                snapshot.layerCoverage().get(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .WITNESS_DIRECT));
        assertEquals(
                new SupportBucketedCandidateEvaluator.LayerCoverage(2, 2),
                snapshot.layerCoverage().get(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .NEUTRAL_CLOSURE));
        assertEquals(
                new SupportBucketedCandidateEvaluator.LayerCoverage(3, 3),
                snapshot.layerCoverage().get(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .TRANSFER_BRIDGE_1_HOP));
    }

    @Test
    void selectsBestCoefficientPerSupportAndSpreadsExactBudget() {
        Fixture fixture = fixture();
        SupportBucketedCandidateEvaluator.Snapshot snapshot =
                SupportBucketedCandidateEvaluator.freeze(
                        fixture.runs(), 307);

        SupportBucketedCandidateEvaluator.Evaluation evaluation =
                SupportBucketedCandidateEvaluator.evaluate(
                        snapshot, fixture.scores(), 2, 2);

        assertEquals(
                SupportBucketedCandidateEvaluator.SnapshotStatus
                        .CANDIDATE_SNAPSHOT_DRIFT,
                snapshot.status());
        assertEquals(List.of("s2", "s4"),
                stateSignatures(evaluation.globalStatePlan().finalists()));
        assertEquals(1,
                evaluation.globalStatePlan().distinctSupports());
        assertEquals(List.of("s2", "s3"),
                stateSignatures(evaluation.supportBucketPlan().finalists()));
        assertEquals(2,
                evaluation.supportBucketPlan().distinctSupports());
        assertEquals(2,
                evaluation.globalStatePlan().finalists().size());
        assertEquals(2,
                evaluation.supportBucketPlan().finalists().size());
        assertEquals(snapshot.supportDenominator(),
                evaluation.globalStatePlan().supportDenominator());
        assertEquals(snapshot.supportDenominator(),
                evaluation.supportBucketPlan().supportDenominator());

        SupportBucketedCandidateEvaluator.SupportBucket firstBucket =
                evaluation.supportBuckets().get(
                        SupportBucketedCandidateEvaluator.supportIdentity(
                                fixture.stateOne()));
        assertEquals("s1", firstBucket.staticFirst().state().stateSignature());
        assertEquals("s2",
                firstBucket.representative().state().stateSignature());
        assertFalse(firstBucket.representativeInHistoricalPrefix());

        assertEquals(5, evaluation.bucketSummary().buckets());
        assertEquals(1, evaluation.bucketSummary().multiStateBuckets());
        assertEquals(1,
                evaluation.bucketSummary().representativeChangedBuckets());
        assertEquals(1,
                evaluation.bucketSummary().targetShapeImprovedBuckets());
        assertEquals(5,
                evaluation.bucketSummary().maxGroupImprovement());
        assertEquals(2,
                evaluation.historicalSummary().distinctSupports());
        assertEquals(5,
                evaluation.historicalSummary().supportDenominator());
        assertEquals(33,
                evaluation.historicalSummary().bestTargetGroups());
    }

    @Test
    void capsExactBudgetAtAvailableSupportRepresentatives() {
        Fixture fixture = fixture();
        SupportBucketedCandidateEvaluator.Snapshot snapshot =
                SupportBucketedCandidateEvaluator.freeze(
                        fixture.runs(), 7);

        SupportBucketedCandidateEvaluator.Evaluation evaluation =
                SupportBucketedCandidateEvaluator.evaluate(
                        snapshot,
                        fixture.scores(),
                        2,
                        24);

        assertEquals(5, snapshot.supportDenominator());
        assertEquals(5,
                evaluation.globalStatePlan().finalists().size());
        assertEquals(5,
                evaluation.supportBucketPlan().finalists().size());
        assertEquals(5,
                evaluation.supportBucketPlan().distinctSupports());
    }

    @Test
    void ranksAllFeasibleResultsBeforeInvalidOrMissingSolutions() {
        Fixture fixture = fixture();
        SupportBucketedCandidateEvaluator.Evaluation evaluation =
                SupportBucketedCandidateEvaluator.evaluate(
                        SupportBucketedCandidateEvaluator.freeze(
                                fixture.runs(), 7),
                        fixture.scores(),
                        2,
                        7);

        assertEquals(List.of("s2", "s4", "s3", "s1", "s5", "s6", "s7"),
                stateSignatures(evaluation.globalOrder()));
        assertEquals(4,
                evaluation.phase2Summary().statusCounts().get(
                        ProductionNeutralMoveSearch.FastStatus.FEASIBLE));
        assertEquals(1,
                evaluation.phase2Summary().statusCounts().get(
                        ProductionNeutralMoveSearch.FastStatus.INVALID));
        assertEquals(1,
                evaluation.phase2Summary().statusCounts().get(
                        ProductionNeutralMoveSearch.FastStatus.NO_SOLUTION));
        assertEquals(1,
                evaluation.phase2Summary().statusCounts().get(
                        ProductionNeutralMoveSearch.FastStatus.ERROR));
        assertEquals(3, evaluation.phase2Summary().targetShapeStates());
        assertEquals(2, evaluation.phase2Summary().targetShapeSupports());
        assertEquals(31, evaluation.phase2Summary().bestTargetGroups());
        assertEquals(31, evaluation.phase2Summary().bestFeasibleGroups());
        assertEquals(28L, evaluation.phase2Summary().totalElapsedMs());
        assertEquals(4.0, evaluation.phase2Summary().medianElapsedMs());

        Map<String, ProductionNeutralMoveSearch.FastUpperBound> incomplete =
                new LinkedHashMap<>(fixture.scores());
        incomplete.remove("s7");
        assertThrows(IllegalArgumentException.class, () ->
                SupportBucketedCandidateEvaluator.evaluate(
                        SupportBucketedCandidateEvaluator.freeze(
                                fixture.runs(), 7),
                        incomplete,
                        2,
                        2));
    }

    @Test
    void crossesMasterObjectiveBucketsWithUniquePhase2States() {
        Fixture fixture = fixture();
        SupportBucketedCandidateEvaluator.Evaluation evaluation =
                SupportBucketedCandidateEvaluator.evaluate(
                        SupportBucketedCandidateEvaluator.freeze(
                                fixture.runs(), 7),
                        fixture.scores(),
                        2,
                        2);
        SupportBucketedCandidateEvaluator.ObjectiveDiagnostics diagnostics =
                evaluation.objectiveDiagnostics();

        assertEquals(2, diagnostics.masterIterationFrequency().get(-3_300.0));
        assertEquals(2, diagnostics.largestMasterIterationBucket());
        assertEquals(1, diagnostics.ambiguousObjectiveStates());
        assertEquals(0, diagnostics.statesWithoutObjective());

        SupportBucketedCandidateEvaluator.ObjectiveCrossBucket bucket =
                diagnostics.crossBuckets().stream()
                        .filter(value -> value.objective() == -3_300.0)
                        .findFirst()
                        .orElseThrow();
        assertEquals(4, bucket.states());
        assertEquals(2, bucket.supports());
        assertEquals(3, bucket.feasibleStates());
        assertEquals(2, bucket.targetShapeStates());
        assertEquals(31, bucket.minimumGroups());
        assertEquals(32.0, bucket.medianGroups());
        assertEquals(36, bucket.maximumGroups());
        assertEquals(Map.of(31, 1, 32, 1, 36, 1),
                bucket.groupFrequency());
    }

    @Test
    void normalizesSolverNoiseBeforeGroupingMasterObjectives() {
        Fixture fixture = fixture();
        Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                SparseProductionNeutralMoveEnumerator.Result> runs =
                new EnumMap<>(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.class);
        runs.put(
                StructuredLocalPatternUniverseBuilder.UniverseScope
                        .WITNESS_DIRECT,
                result(
                        List.of(fixture.stateOne()),
                        List.of(new SupportObjective(
                                "local-A", -3_300.0, 1))));
        runs.put(
                StructuredLocalPatternUniverseBuilder.UniverseScope
                        .NEUTRAL_CLOSURE,
                result(
                        List.of(fixture.stateOneDuplicate()),
                        List.of(new SupportObjective(
                                "different-local-index-A",
                                -3_299.9999999999995,
                                1))));

        SupportBucketedCandidateEvaluator.Snapshot snapshot =
                SupportBucketedCandidateEvaluator.freeze(runs, 1);

        assertEquals(Map.of(-3_300.0, 2),
                snapshot.masterObjectiveFrequency());
        assertEquals(Set.of(-3_300.0),
                snapshot.staticOrder().get(0).masterObjectives());
    }

    private static Fixture fixture() {
        PatternCandidate p1 = pattern(10);
        PatternCandidate p2 = pattern(20);
        PatternCandidate p3 = pattern(30);
        PatternCandidate p4 = pattern(40);
        PatternCandidate p5 = pattern(50);
        PatternCandidate p6 = pattern(60);
        PatternCandidate p7 = pattern(70);
        PatternCandidate p8 = pattern(80);
        PatternCandidate p9 = pattern(90);
        PatternCandidate p10 = pattern(95);

        SparseProductionNeutralMoveEnumerator.Candidate stateOne = candidate(
                "s1", "local-A", p1, -1, p2, 1);
        SparseProductionNeutralMoveEnumerator.Candidate stateTwo = candidate(
                "s2", "local-A", p1, -2, p2, 2);
        SparseProductionNeutralMoveEnumerator.Candidate stateFour = candidate(
                "s4", "local-A", p1, -3, p2, 3);
        SparseProductionNeutralMoveEnumerator.Candidate stateOneDuplicate =
                candidate("s1", "different-local-index-A", p1, -1, p2, 1);
        SparseProductionNeutralMoveEnumerator.Candidate stateThree = candidate(
                "s3", "local-B", p3, -1, p4, 1);
        SparseProductionNeutralMoveEnumerator.Candidate stateFive = candidate(
                "s5", "local-C", p5, -1, p6, 1);
        SparseProductionNeutralMoveEnumerator.Candidate stateSix = candidate(
                "s6", "local-D", p7, -1, p8, 1);
        SparseProductionNeutralMoveEnumerator.Candidate stateSeven = candidate(
                "s7", "local-E", p9, -1, p10, 1);
        SparseProductionNeutralMoveEnumerator.Candidate reversed = candidate(
                "reverse", "reverse-local", p1, 1, p2, -1);

        Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                SparseProductionNeutralMoveEnumerator.Result> runs =
                new EnumMap<>(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.class);
        runs.put(
                StructuredLocalPatternUniverseBuilder.UniverseScope
                        .WITNESS_DIRECT,
                result(
                        List.of(stateOne, stateTwo, stateFour),
                        List.of(new SupportObjective("local-A", -3_300.0, 3))));
        runs.put(
                StructuredLocalPatternUniverseBuilder.UniverseScope
                        .NEUTRAL_CLOSURE,
                result(
                        List.of(stateOneDuplicate, stateThree),
                        List.of(
                                new SupportObjective(
                                        "different-local-index-A", -2_200.0, 1),
                                new SupportObjective("local-B", -1_100.0, 1))));
        runs.put(
                StructuredLocalPatternUniverseBuilder.UniverseScope
                        .TRANSFER_BRIDGE_1_HOP,
                result(
                        List.of(stateFive, stateSix, stateSeven),
                        List.of(
                                new SupportObjective("local-C", -3_300.0, 1),
                                new SupportObjective("local-D", -3_200.0, 1),
                                new SupportObjective("local-E", -3_100.0, 1))));

        Map<String, ProductionNeutralMoveSearch.FastUpperBound> scores =
                new LinkedHashMap<>();
        scores.put("s1", fast(
                ProductionNeutralMoveSearch.FastStatus.FEASIBLE,
                36, 3, 0, 1));
        scores.put("s2", fast(
                ProductionNeutralMoveSearch.FastStatus.FEASIBLE,
                31, 1, 0, 2));
        scores.put("s3", fast(
                ProductionNeutralMoveSearch.FastStatus.FEASIBLE,
                33, 1, 0, 3));
        scores.put("s4", fast(
                ProductionNeutralMoveSearch.FastStatus.FEASIBLE,
                32, 1, 0, 4));
        scores.put("s5", fast(
                ProductionNeutralMoveSearch.FastStatus.INVALID,
                20, 1, 0, 5));
        scores.put("s6", fast(
                ProductionNeutralMoveSearch.FastStatus.NO_SOLUTION,
                -1, -1, -1, 6));
        scores.put("s7", fast(
                ProductionNeutralMoveSearch.FastStatus.ERROR,
                -1, -1, -1, 7));
        return new Fixture(
                runs,
                scores,
                stateOne,
                stateTwo,
                stateFour,
                stateOneDuplicate,
                reversed);
    }

    private static SparseProductionNeutralMoveEnumerator.Result result(
            List<SparseProductionNeutralMoveEnumerator.Candidate> candidates,
            List<SupportObjective> supports) {
        List<SparseProductionNeutralMoveEnumerator.MasterIteration> iterations =
                new ArrayList<>();
        List<SparseProductionNeutralMoveEnumerator.SupportRun> supportRuns =
                new ArrayList<>();
        long nodes = 0L;
        int coefficientSolutions = 0;
        for (int index = 0; index < supports.size(); index++) {
            SupportObjective support = supports.get(index);
            iterations.add(new SparseProductionNeutralMoveEnumerator
                    .MasterIteration(
                    index + 1,
                    MPSolver.ResultStatus.OPTIMAL,
                    1L,
                    index + 1L,
                    index,
                    support.objective(),
                    support.objective()));
            supportRuns.add(new SparseProductionNeutralMoveEnumerator.SupportRun(
                    support.localSignature(),
                    MPSolver.ResultStatus.OPTIMAL,
                    support.coefficientSolutions(),
                    support.coefficientSolutions(),
                    support.coefficientSolutions(),
                    true,
                    1L,
                    index + 1L));
            nodes += index + 1L;
            coefficientSolutions += support.coefficientSolutions();
        }
        SparseProductionNeutralMoveEnumerator.Metrics metrics =
                new SparseProductionNeutralMoveEnumerator.Metrics(
                        20,
                        4,
                        supports.size(),
                        supports.size(),
                        0,
                        supports.size(),
                        coefficientSolutions,
                        coefficientSolutions,
                        0,
                        0,
                        candidates.size(),
                        nodes,
                        nodes,
                        false,
                        true,
                        iterations,
                        supportRuns,
                        supports.size());
        return new SparseProductionNeutralMoveEnumerator.Result(
                SparseProductionNeutralMoveEnumerator.Status.TIMED_OUT,
                candidates,
                metrics);
    }

    private static SparseProductionNeutralMoveEnumerator.Candidate candidate(
            String stateSignature,
            String localSupportSignature,
            PatternCandidate first,
            int firstDelta,
            PatternCandidate second,
            int secondDelta) {
        Map<PatternCandidate, Integer> delta = new LinkedHashMap<>();
        delta.put(first, firstDelta);
        delta.put(second, secondDelta);
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        solution.put(first, Math.max(0, 5 + firstDelta));
        solution.put(second, Math.max(0, 5 + secondDelta));
        return new SparseProductionNeutralMoveEnumerator.Candidate(
                delta,
                solution,
                stateSignature,
                localSupportSignature,
                "coefficient-" + stateSignature);
    }

    private static PatternCandidate pattern(int width) {
        return new PatternCandidate(Map.of(width, 1), 100);
    }

    private static ProductionNeutralMoveSearch.FastUpperBound fast(
            ProductionNeutralMoveSearch.FastStatus status,
            int groups,
            int odd,
            int one,
            long elapsedMs) {
        return new ProductionNeutralMoveSearch.FastUpperBound(
                status, groups, odd, one, elapsedMs);
    }

    private static SupportBucketedCandidateEvaluator.FrozenCandidate state(
            SupportBucketedCandidateEvaluator.Snapshot snapshot,
            String stateSignature) {
        return snapshot.staticOrder().stream()
                .filter(value -> value.stateSignature().equals(stateSignature))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> stateSignatures(
            List<SupportBucketedCandidateEvaluator.ScoredCandidate> candidates) {
        return candidates.stream()
                .map(value -> value.state().stateSignature())
                .toList();
    }

    private record SupportObjective(
            String localSignature,
            double objective,
            int coefficientSolutions) {
    }

    private record Fixture(
            Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                    SparseProductionNeutralMoveEnumerator.Result> runs,
            Map<String, ProductionNeutralMoveSearch.FastUpperBound> scores,
            SparseProductionNeutralMoveEnumerator.Candidate stateOne,
            SparseProductionNeutralMoveEnumerator.Candidate stateTwo,
            SparseProductionNeutralMoveEnumerator.Candidate stateFour,
            SparseProductionNeutralMoveEnumerator.Candidate stateOneDuplicate,
            SparseProductionNeutralMoveEnumerator.Candidate reversedState) {
    }
}
