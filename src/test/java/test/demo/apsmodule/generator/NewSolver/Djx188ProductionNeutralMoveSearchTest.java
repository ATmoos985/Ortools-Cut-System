package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPSolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Djx188ProductionNeutralMoveSearchTest {

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void completeUnitTwoForTwoNeighborhoodIsMeasuredWithoutManualSignatures()
            throws Exception {
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        SolverParameters parameters = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe = new CompletePatternEnumerator(parameters, 5)
                .generate(demands);
        Map<PatternCandidate, Integer> baseline =
                Djx188ProductionNeutralFixture.loadAutomatic22();
        Map<PatternCandidate, Integer> upper =
                Djx188ProductionNeutralFixture.upperBounds(
                        universe, demands, 169);

        assertEquals(7_717, universe.size());
        assertEquals(22, baseline.size());
        assertEquals(demands,
                Djx188ProductionNeutralFixture.production(baseline));
        assertEquals(169,
                Djx188ProductionNeutralFixture.totalCars(baseline));
        assertEquals(36_870,
                Djx188ProductionNeutralFixture.totalWaste(baseline, 4600));

        UnitTwoForTwoMoveEnumerator.Result result =
                UnitTwoForTwoMoveEnumerator.enumerate(
                        universe, baseline, upper);

        assertTrue(result.exhausted());
        long parityCompatible = result.candidates().stream()
                .filter(candidate -> Djx188ProductionNeutralFixture
                        .oddUsages(candidate.solution()) == 1)
                .filter(candidate -> Djx188ProductionNeutralFixture
                        .oneUsages(candidate.solution()) == 0)
                .count();
        for (UnitTwoForTwoMoveEnumerator.Candidate candidate
                : result.candidates()) {
            assertEquals(demands,
                    Djx188ProductionNeutralFixture.production(candidate.solution()));
            assertEquals(169,
                    Djx188ProductionNeutralFixture.totalCars(candidate.solution()));
            assertEquals(36_870,
                    Djx188ProductionNeutralFixture.totalWaste(
                            candidate.solution(), 4600));
        }

        UnitTwoForTwoMoveEnumerator.Metrics metrics = result.metrics();
        System.out.printf(
                "DJX188 UNIT-2X2 universe=%d support=%d pairs=%d feasiblePairs=%d "
                        + "scans=%d hashHits=%d exactMatches=%d zero=%d "
                        + "boundRejected=%d moves=%d duplicateMoves=%d "
                        + "expanded=%d states=%d duplicateStates=%d "
                        + "parityCompatible=%d tMax=%s elapsedMs=%d%n",
                metrics.universeSize(),
                metrics.supportSize(),
                metrics.supportPairCount(),
                metrics.feasibleSourcePairs(),
                metrics.complementScans(),
                metrics.complementHashHits(),
                metrics.exactPairMatches(),
                metrics.zeroMoves(),
                metrics.boundRejectedMoves(),
                metrics.normalizedMoves(),
                metrics.duplicateMoves(),
                metrics.expandedScaleCandidates(),
                metrics.uniqueStates(),
                metrics.duplicateStates(),
                parityCompatible,
                metrics.maxScaleDistribution(),
                metrics.elapsedMs());
    }

    @Test
    void closedLoopSearchReportsExactEvidenceWithoutManualSignatures()
            throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("cutting.test.neutralClosedLoop"),
                "research-only closed-loop experiment is opt-in");
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        SolverParameters parameters = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe = new CompletePatternEnumerator(parameters, 5)
                .generate(demands);
        Map<PatternCandidate, Integer> baseline =
                Djx188ProductionNeutralFixture.loadAutomatic22();
        Map<PatternCandidate, Integer> upper =
                Djx188ProductionNeutralFixture.upperBounds(
                        universe, demands, 169);

        SparseProductionNeutralMoveEnumerator.Options sparseOptions =
                new SparseProductionNeutralMoveEnumerator.Options(
                        Long.getLong("cutting.test.neutral.sparseTotalMs", 300_000L),
                        Long.getLong("cutting.test.neutral.sparseMasterMs", 60_000L),
                        Long.getLong("cutting.test.neutral.sparseSupportMs", 30_000L),
                        Long.getLong("cutting.test.neutral.sparseNodeLimit", 200_000L),
                        Integer.getInteger("cutting.test.neutral.sparseMaxSupports", 500),
                        Integer.getInteger("cutting.test.neutral.sparseMaxCoefficients", 5_000),
                        Integer.getInteger("cutting.test.neutral.sparseMaxStates", 2_000),
                        java.util.Set.of(),
                        java.util.Set.of());
        ProductionNeutralMoveSearch.Options options =
                new ProductionNeutralMoveSearch.Options(
                        Integer.getInteger("cutting.test.neutral.phase2Candidates", 30),
                        Integer.getInteger("cutting.test.neutral.exactCandidates", 12),
                        Integer.getInteger("cutting.test.neutral.platformCandidates", 2),
                        Integer.getInteger("cutting.test.neutral.platformCapacity", 3),
                        Integer.getInteger("cutting.test.neutral.maxExpandedStates", 3),
                        Integer.getInteger("cutting.test.neutral.maxDepth", 2),
                        Boolean.parseBoolean(System.getProperty(
                                "cutting.test.neutral.enableSparse", "true")),
                        Long.getLong("cutting.test.neutral.thresholdMs", 90_000L),
                        Long.getLong("cutting.test.neutral.fullGroupMs", 180_000L),
                        Long.getLong("cutting.test.neutral.fullShapeMs", 60_000L),
                        100_000L,
                        -1L,
                        sparseOptions);

        System.out.printf(
                "DJX188 NEUTRAL-OPTIONS phase2=%d exact=%d platform=%d/%d "
                        + "expanded=%d depth=%d sparse=%s thresholdMs=%d "
                        + "fullMs=%d/%d configs=%d nodes=%d "
                        + "sparseMs=%d/%d/%d sparseCaps=%d/%d/%d sparseNodes=%d%n",
                options.phase2CandidateLimit(),
                options.exactCandidateLimit(),
                options.platformCandidateLimit(),
                options.platformCapacity(),
                options.maxExpandedStates(),
                options.maxDepth(),
                options.enableSparseLayer(),
                options.thresholdTimeLimitMs(),
                options.fullAnalysisGroupTimeLimitMs(),
                options.fullAnalysisShapeTimeLimitMs(),
                options.maxConfigurations(),
                options.exactNodeLimit(),
                sparseOptions.totalTimeLimitMs(),
                sparseOptions.masterSolveTimeLimitMs(),
                sparseOptions.subproblemTimeLimitMs(),
                sparseOptions.maxSupports(),
                sparseOptions.maxCoefficientSolutions(),
                sparseOptions.maxUniqueStates(),
                sparseOptions.nodeLimit());

        ProductionNeutralMoveSearch.SearchResult result =
                ProductionNeutralMoveSearch.search(
                        universe, baseline, upper, items, parameters, options);

        assertEquals(28, result.baselineAnalysis().exactMinimumGroups());
        assertEquals(1, result.baselineAnalysis().oddGroups());
        assertEquals(0, result.baselineAnalysis().oneGroups());
        assertTrue(!result.layerRuns().isEmpty());
        assertEquals(demands,
                Djx188ProductionNeutralFixture.production(result.bestSolution()));
        assertEquals(169,
                Djx188ProductionNeutralFixture.totalCars(result.bestSolution()));
        assertEquals(36_870,
                Djx188ProductionNeutralFixture.totalWaste(
                        result.bestSolution(), 4600));
        if (result.status() == ProductionNeutralMoveSearch.SearchStatus.IMPROVED) {
            assertTrue(result.bestAnalysis().groupOptimal());
            assertTrue(result.bestAnalysis().exactMinimumGroups() <= 27);
        }

        System.out.printf(
                "DJX188 NEUTRAL-CLOSED-LOOP status=%s bestGroups=%d "
                        + "moveExhausted=%s exactExhausted=%s "
                        + "witnessRefreshMs=%d totalMs=%d layers=%d evaluations=%d%n",
                result.status(),
                result.bestAnalysis().exactMinimumGroups(),
                result.moveGenerationExhausted(),
                result.exactEvaluationExhausted(),
                result.witnessRefreshMs(),
                result.totalElapsedMs(),
                result.layerRuns().size(),
                result.evaluations().size());
        result.layerRuns().forEach(run -> {
            System.out.printf(
                    "  LAYER depth=%d type=%s generated=%d eligible=%d "
                            + "usageRejected=%d visited=%d invalid=%d "
                            + "phase2=%d exact=%d moveExhausted=%s "
                            + "exactExhausted=%s generationMs=%d%n",
                    run.depth(),
                    run.layer(),
                    run.generatedStates(),
                    run.eligibleNeighbors(),
                    run.usageRejected(),
                    run.visitedRejected(),
                    run.invalidInvariants(),
                    run.phase2Evaluated(),
                    run.exactEvaluated(),
                    run.moveGenerationExhausted(),
                    run.exactEvaluationExhausted(),
                    run.generationElapsedMs());
            if (run.sparseMetrics() != null) {
                SparseProductionNeutralMoveEnumerator.Metrics metrics =
                        run.sparseMetrics();
                long exhaustedSupports = metrics.supportRuns().stream()
                        .filter(SparseProductionNeutralMoveEnumerator
                                .SupportRun::exhausted)
                        .count();
                long timedOutSupports = metrics.supportRuns().stream()
                        .filter(support -> !support.exhausted())
                        .filter(support -> support.lastStatus()
                                == MPSolver.ResultStatus.NOT_SOLVED)
                        .count();
                System.out.printf(
                        "    SPARSE status=%s supports=%d seeded=%d exhausted=%d "
                                + "timedOut=%d supportCuts=%d coefficients=%d "
                                + "coefficientCuts=%d states=%d statusExhausted=%s/%s "
                                + "masterNodes=%d subNodes=%d elapsedMs=%d%n",
                        run.sparseStatus(),
                        metrics.supportsVisited(),
                        metrics.seededSupportsVisited(),
                        exhaustedSupports,
                        timedOutSupports,
                        metrics.supportNoGoodCuts(),
                        metrics.coefficientSolutions(),
                        metrics.coefficientNoGoodCuts(),
                        metrics.uniqueStates(),
                        metrics.supportExhausted(),
                        metrics.coefficientExhausted(),
                        metrics.masterNodes(),
                        metrics.subproblemNodes(),
                        metrics.totalElapsedMs());
                metrics.masterIterations().forEach(iteration -> System.out.printf(
                        "      MASTER iteration=%d status=%s cuts=%d "
                                + "nodes=%d objective=%s bound=%s elapsedMs=%d%n",
                        iteration.iteration(),
                        iteration.status(),
                        iteration.supportNoGoodsBeforeSolve(),
                        iteration.nodes(),
                        Double.toString(iteration.objectiveValue()),
                        Double.toString(iteration.bestBound()),
                        iteration.elapsedMs()));
            }
        });
        for (int index = 0; index < result.evaluations().size(); index++) {
            ProductionNeutralMoveSearch.CandidateEvaluation evaluation =
                    result.evaluations().get(index);
            System.out.printf(
                    "  EVAL #%d layer=%s support=%d removedWitness=%d/%d "
                            + "addedWidths=%d movedCars=%d fast=%s/%d/%d/%d/%dms "
                            + "target={%s} current={%s} class=%s%n",
                    index + 1,
                    evaluation.neighbor().layer(),
                    evaluation.neighbor().supportCount(),
                    evaluation.neighbor().removedWitnessPatterns(),
                    evaluation.neighbor().removedWitnessCars(),
                    evaluation.neighbor().addedPreferredWidths(),
                    evaluation.neighbor().movedCars(),
                    evaluation.fastUpperBound().status(),
                    evaluation.fastUpperBound().groups(),
                    evaluation.fastUpperBound().oddGroups(),
                    evaluation.fastUpperBound().oneGroups(),
                    evaluation.fastUpperBound().elapsedMs(),
                    thresholdSummary(evaluation.improvementThreshold()),
                    thresholdSummary(evaluation.currentThreshold()),
                    evaluation.classification());
        }
        if (result.status() == ProductionNeutralMoveSearch.SearchStatus.IMPROVED) {
            result.bestSolution().entrySet().stream()
                    .sorted(Map.Entry.<PatternCandidate, Integer>comparingByValue()
                            .reversed()
                            .thenComparing(entry -> entry.getKey().signature()))
                    .forEach(entry -> System.out.printf(
                            "    %3d x %s%n",
                            entry.getValue(), entry.getKey().signature()));
        }
    }

    private static String thresholdSummary(
            OrderCompatibilityKernelAnalyzer.ThresholdAnalysis analysis) {
        if (analysis == null) {
            return "-";
        }
        return String.format(
                "%s groups=%d odd=%d one=%d nodes=%d elapsedMs=%d",
                analysis.status(),
                analysis.feasibleGroups(),
                analysis.feasibleOddGroups(),
                analysis.feasibleOneGroups(),
                analysis.nodes(),
                analysis.totalElapsedMs());
    }
}
