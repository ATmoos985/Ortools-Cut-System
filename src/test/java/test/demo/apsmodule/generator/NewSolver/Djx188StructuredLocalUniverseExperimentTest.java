package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in real-data probe for the structured local-universe design. */
class Djx188StructuredLocalUniverseExperimentTest {

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void structuredLocalUniversesAdvanceSparseSupportSearchWithoutManualSignatures()
            throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("cutting.test.structuredLocalUniverse"),
                "research-only structured-local-universe experiment is opt-in");

        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        SolverParameters parameters = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe = new CompletePatternEnumerator(parameters, 5)
                .generate(demands);
        Map<PatternCandidate, Integer> baseline =
                Djx188ProductionNeutralFixture.loadAutomatic22();
        Map<PatternCandidate, Integer> upper =
                Djx188ProductionNeutralFixture.upperBounds(universe, demands, 169);

        OrderCompatibilityKernelAnalyzer.Options analysisOptions =
                new OrderCompatibilityKernelAnalyzer.Options(
                        Long.getLong(
                                "cutting.test.local.baselineGroupMs", 180_000L),
                        Long.getLong(
                                "cutting.test.local.baselineShapeMs", 60_000L),
                        Long.getLong(
                                "cutting.test.local.maxConfigurations", 100_000L),
                        Long.getLong(
                                "cutting.test.local.exactNodeLimit", -1L),
                        false);
        OrderCompatibilityKernelAnalyzer.Analysis analysis =
                OrderCompatibilityKernelAnalyzer.analyze(
                        baseline, items, analysisOptions);

        assertEquals(7_717, universe.size());
        assertEquals(22, baseline.size());
        assertEquals(OrderCompatibilityKernelAnalyzer.Status.OPTIMAL,
                analysis.status());
        assertTrue(analysis.groupOptimal());
        assertTrue(analysis.shapeOptimal());
        assertEquals(28, analysis.exactMinimumGroups());
        assertEquals(6, analysis.exactExtraGroups());
        assertEquals(1, analysis.oddGroups());
        assertEquals(0, analysis.oneGroups());
        assertEquals(6, analysis.splitWitnesses().size());

        StructuredLocalPatternUniverseBuilder.Options buildOptions =
                new StructuredLocalPatternUniverseBuilder.Options(
                        Long.getLong("cutting.test.local.buildMs", 180_000L),
                        Integer.getInteger(
                                "cutting.test.local.maxPatterns", Integer.MAX_VALUE),
                        Integer.getInteger(
                                "cutting.test.local.maxEquations", Integer.MAX_VALUE),
                        Integer.getInteger(
                                "cutting.test.local.maxBridgePaths", 200_000),
                        parameters.getTotalWidth(),
                        169,
                        36_870,
                        System::currentTimeMillis);
        StructuredLocalPatternUniverseBuilder.BuildResult build =
                StructuredLocalPatternUniverseBuilder.build(
                        universe, baseline, upper, analysis, buildOptions);
        assertDeterministicBuildAnchors(build);

        System.out.printf(
                "DJX188 LOCAL-BUILD universe=%d baseline=%d witnesses=%d "
                        + "residuals=%d equations=%d/%d directRepair=%d "
                        + "bridgeProbe=%dx%d=%d scans=%d exactPairs=%d "
                        + "eligiblePaths=%d eligibleEquations=%d elapsedMs=%d%n",
                build.metrics().fullUniversePatterns(),
                build.metrics().baselineSupportPatterns(),
                build.metrics().splitWitnesses(),
                build.metrics().residualTargets(),
                build.metrics().processedBaselineEquations(),
                build.metrics().completeBaselineEquations(),
                build.metrics().directRepairPatterns(),
                build.metrics().directRepairPatterns(),
                baseline.size(),
                build.metrics().transferSourcePairs(),
                build.metrics().transferComplementScans(),
                build.metrics().transferExactPairMatches(),
                build.metrics().eligibleBridgePaths(),
                build.metrics().eligibleBridgeEquations(),
                build.metrics().elapsedMs());

        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            StructuredLocalPatternUniverseBuilder.LocalPatternUniverse layer =
                    build.layer(scope);
            assertTrue(signatures(layer.patterns()).containsAll(
                    signatures(new ArrayList<>(baseline.keySet()))));
            assertFalse(layer.evidenceByPattern().isEmpty());
            printLayer(layer);
        }

        UnitTwoForTwoMoveEnumerator.Result completeUnit =
                UnitTwoForTwoMoveEnumerator.enumerate(universe, baseline, upper);
        Set<String> excludedStates = completeUnit.candidates().stream()
                .map(UnitTwoForTwoMoveEnumerator.Candidate::stateSignature)
                .collect(TreeSet::new, Set::add, Set::addAll);
        assertTrue(completeUnit.exhausted());

        Set<String> preferredPatterns = analysis.splitWitnesses().stream()
                .map(OrderCompatibilityKernelAnalyzer.PatternSplit::patternSignature)
                .collect(TreeSet::new, Set::add, Set::addAll);
        Set<Integer> preferredWidths = analysis.splitWitnesses().stream()
                .flatMap(split -> split.varyingWidths().stream())
                .collect(TreeSet::new, Set::add, Set::addAll);
        SparseProductionNeutralMoveEnumerator.Options sparseOptions =
                new SparseProductionNeutralMoveEnumerator.Options(
                        Long.getLong("cutting.test.local.sparseTotalMs", 60_000L),
                        Long.getLong("cutting.test.local.sparseMasterMs", 30_000L),
                        Long.getLong("cutting.test.local.sparseSupportMs", 15_000L),
                        Long.getLong("cutting.test.local.sparseNodeLimit", 200_000L),
                        Integer.getInteger("cutting.test.local.sparseMaxSupports", 200),
                        Integer.getInteger(
                                "cutting.test.local.sparseMaxCoefficients", 2_000),
                        Integer.getInteger("cutting.test.local.sparseMaxStates", 500),
                        preferredPatterns,
                        preferredWidths);

        Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                SparseProductionNeutralMoveEnumerator.Result> sparseRuns =
                new EnumMap<>(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.class);
        boolean engineeringGoal = false;
        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            StructuredLocalPatternUniverseBuilder.LocalPatternUniverse layer =
                    build.layer(scope);
            Map<PatternCandidate, Integer> localUpper = new LinkedHashMap<>();
            layer.patterns().forEach(pattern ->
                    localUpper.put(pattern, upper.get(pattern)));
            SparseProductionNeutralMoveEnumerator.Result sparse =
                    SparseProductionNeutralMoveEnumerator.enumerate(
                            layer.patterns(),
                            baseline,
                            localUpper,
                            excludedStates,
                            sparseOptions);
            sparseRuns.put(scope, sparse);
            SparseProductionNeutralMoveEnumerator.Metrics metrics =
                    sparse.metrics();
            boolean layerAdvanced = metrics.supportsVisited() > 0
                    || sparse.status()
                    == SparseProductionNeutralMoveEnumerator.Status.EXHAUSTED
                    || metrics.masterNodes() > 0;
            engineeringGoal |= layerAdvanced;
            System.out.printf(
                    "DJX188 LOCAL-SPARSE scope=%s patterns=%d build=%s "
                            + "status=%s advanced=%s masterSolves=%d "
                            + "supports=%d coefficients=%d states=%d "
                            + "masterNodes=%d subNodes=%d exhausted=%s/%s "
                            + "elapsedMs=%d%n",
                    scope,
                    layer.patterns().size(),
                    layer.buildStatus(),
                    sparse.status(),
                    layerAdvanced,
                    metrics.masterSolves(),
                    metrics.supportsVisited(),
                    metrics.coefficientSolutions(),
                    metrics.uniqueStates(),
                    metrics.masterNodes(),
                    metrics.subproblemNodes(),
                    metrics.supportExhausted(),
                    metrics.coefficientExhausted(),
                    metrics.totalElapsedMs());
            metrics.masterIterations().forEach(iteration -> System.out.printf(
                    "  MASTER iteration=%d status=%s cuts=%d nodes=%d "
                            + "objective=%s bound=%s elapsedMs=%d%n",
                    iteration.iteration(),
                    iteration.status(),
                    iteration.supportNoGoodsBeforeSolve(),
                    iteration.nodes(),
                    Double.toString(iteration.objectiveValue()),
                    Double.toString(iteration.bestBound()),
                    iteration.elapsedMs()));
        }

        SupportBucketedCandidateEvaluator.Snapshot snapshot =
                SupportBucketedCandidateEvaluator.freeze(sparseRuns, 307);
        printSnapshot(snapshot);

        Map<String, ProductionNeutralMoveSearch.FastUpperBound> phase2ByState =
                evaluateAllPhase2(snapshot, items, parameters);
        int historicalPhase2Limit = Integer.getInteger(
                "cutting.test.local.phase2Candidates", 30);
        int exactLimit = Integer.getInteger(
                "cutting.test.local.exactCandidates", 5);
        SupportBucketedCandidateEvaluator.Evaluation evaluation =
                SupportBucketedCandidateEvaluator.evaluate(
                        snapshot,
                        phase2ByState,
                        historicalPhase2Limit,
                        exactLimit);
        printEvaluation(evaluation);

        assertEquals(snapshot.staticOrder().size(), phase2ByState.size());
        assertEquals(
                evaluation.globalStatePlan().finalists().size(),
                evaluation.supportBucketPlan().finalists().size(),
                "same-budget A/B must receive equal exact candidate counts");

        OrderCompatibilityKernelAnalyzer.Options thresholdOptions =
                new OrderCompatibilityKernelAnalyzer.Options(
                        Long.getLong(
                                "cutting.test.local.thresholdMs", 60_000L),
                        0L,
                        analysisOptions.maxConfigurations(),
                        analysisOptions.nodeLimit(),
                        false);
        ExactComparison exact = evaluateExactPlans(
                evaluation,
                items,
                analysisOptions,
                thresholdOptions);
        printExactComparison(exact);

        boolean phase2SamplingBias = phase2SamplingBias(evaluation);
        boolean coefficientSelectionBias =
                evaluation.bucketSummary().targetShapeImprovedBuckets() > 0
                        || evaluation.bucketSummary().maxGroupImprovement() > 0;
        boolean exactBudgetCrowding =
                evaluation.globalStatePlan().distinctSupports()
                        < evaluation.globalStatePlan().finalists().size()
                        && evaluation.supportBucketPlan().distinctSupports()
                        == evaluation.supportBucketPlan().finalists().size();
        boolean proxySignalNotStronger =
                !phase2SamplingBias && !coefficientSelectionBias;
        String outcome = exact.anyFeasible()
                ? "FOUND_AT_MOST_27"
                : exact.fixedPoolProvenInfeasible()
                ? "FIXED_POOL_PROVEN_NO_27"
                : "INCONCLUSIVE";
        System.out.printf(
                "DJX188 EVAL-CONCLUSION outcome=%s engineeringGoal=%s "
                        + "phase2SamplingBias=%s coefficientSelectionBias=%s "
                        + "exactBudgetCrowding=%s proxySignalNotStronger=%s "
                        + "snapshot=%s exactExhausted=%s%n",
                outcome,
                engineeringGoal,
                phase2SamplingBias,
                coefficientSelectionBias,
                exactBudgetCrowding,
                proxySignalNotStronger,
                snapshot.status(),
                exact.exactExhausted());

        assertTrue(engineeringGoal,
                "all local support masters remained NOT_SOLVED with zero nodes");
    }

    private static void assertDeterministicBuildAnchors(
            StructuredLocalPatternUniverseBuilder.BuildResult build) {
        StructuredLocalPatternUniverseBuilder.BuildMetrics metrics =
                build.metrics();
        assertEquals(252, metrics.completeBaselineEquations());
        assertEquals(252, metrics.processedBaselineEquations());
        assertEquals(93, metrics.directRepairPatterns());
        assertEquals(2_046, metrics.transferSourcePairs());
        assertEquals(15_788_982, metrics.transferComplementScans());
        assertEquals(4_799, metrics.transferExactPairMatches());
        assertEquals(1_902, metrics.eligibleBridgePaths());
        assertEquals(1_044, metrics.eligibleBridgeEquations());

        StructuredLocalPatternUniverseBuilder.LocalPatternUniverse direct =
                build.layer(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.WITNESS_DIRECT);
        StructuredLocalPatternUniverseBuilder.LocalPatternUniverse neutral =
                build.layer(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.NEUTRAL_CLOSURE);
        StructuredLocalPatternUniverseBuilder.LocalPatternUniverse transfer =
                build.layer(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.TRANSFER_BRIDGE_1_HOP);
        assertEquals(121, direct.patterns().size());
        assertEquals(370, neutral.patterns().size());
        assertEquals(1_050, transfer.patterns().size());
        assertEquals(452, direct.metrics().evidenceCount());
        assertEquals(1_454, neutral.metrics().evidenceCount());
        assertEquals(9_062, transfer.metrics().evidenceCount());
        assertEquals(12,
                direct.coverage().residualTargetCoverage().numerator());
        assertEquals(12,
                direct.coverage().residualTargetCoverage().denominator());
        assertEquals(252,
                neutral.coverage().neutralEquationCoverage().numerator());
        assertEquals(252,
                neutral.coverage().neutralEquationCoverage().denominator());
        assertEquals(1_044,
                transfer.coverage().bridgeEquationCoverage().numerator());
        assertEquals(1_044,
                transfer.coverage().bridgeEquationCoverage().denominator());
        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            assertEquals(
                    StructuredLocalPatternUniverseBuilder.UniverseBuildStatus
                            .EXHAUSTED_WITHIN_RULE,
                    build.layer(scope).buildStatus());
        }
    }

    private static void printSnapshot(
            SupportBucketedCandidateEvaluator.Snapshot snapshot) {
        System.out.printf(
                "DJX188 EVAL-SNAPSHOT raw=%d unique=%d anchor=%d status=%s "
                        + "supports=%d crossLayerDuplicates=%d "
                        + "unmappedObjectives=%d%n",
                snapshot.rawCandidateOccurrences(),
                snapshot.staticOrder().size(),
                snapshot.historicalStateAnchor(),
                snapshot.status(),
                snapshot.supportDenominator(),
                snapshot.crossLayerDuplicateOccurrences(),
                snapshot.unmappedObjectiveOccurrences());
        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            SupportBucketedCandidateEvaluator.LayerCoverage coverage =
                    snapshot.layerCoverage().get(scope);
            if (coverage != null) {
                System.out.printf(
                        "  EVAL-LAYER scope=%s states=%d supports=%d%n",
                        scope,
                        coverage.states(),
                        coverage.supports());
            }
        }
    }

    private static Map<String, ProductionNeutralMoveSearch.FastUpperBound>
            evaluateAllPhase2(
                    SupportBucketedCandidateEvaluator.Snapshot snapshot,
                    List<SolverOrderItem> items,
                    SolverParameters parameters) {
        int progressEvery = Math.max(1, Integer.getInteger(
                "cutting.test.local.phase2ProgressEvery", 10));
        Map<String, ProductionNeutralMoveSearch.FastUpperBound> byState =
                new LinkedHashMap<>();
        long startedAt = System.currentTimeMillis();
        for (int index = 0; index < snapshot.staticOrder().size(); index++) {
            SupportBucketedCandidateEvaluator.FrozenCandidate state =
                    snapshot.staticOrder().get(index);
            ProductionNeutralMoveSearch.FastUpperBound fast =
                    ProductionNeutralMoveSearch.fastUpperBound(
                            state.candidate().solution(), items, parameters);
            byState.put(state.stateSignature(), fast);
            int completed = index + 1;
            if (completed % progressEvery == 0
                    || completed == snapshot.staticOrder().size()) {
                System.out.printf(
                        "DJX188 EVAL-PHASE2 progress=%d/%d status=%s "
                                + "groups=%d odd=%d one=%d lastMs=%d "
                                + "wallMs=%d%n",
                        completed,
                        snapshot.staticOrder().size(),
                        fast.status(),
                        fast.groups(),
                        fast.oddGroups(),
                        fast.oneGroups(),
                        fast.elapsedMs(),
                        System.currentTimeMillis() - startedAt);
            }
        }
        return Map.copyOf(byState);
    }

    private static void printEvaluation(
            SupportBucketedCandidateEvaluator.Evaluation evaluation) {
        SupportBucketedCandidateEvaluator.Phase2Summary phase2 =
                evaluation.phase2Summary();
        System.out.printf(
                "DJX188 EVAL-PHASE2-SUMMARY states=%d supports=%d "
                        + "status=%s targetStates=%d targetSupports=%d "
                        + "bestTarget=%d bestFeasible=%d totalMs=%d "
                        + "avgMs=%.2f medianMs=%.2f maxMs=%d%n",
                evaluation.snapshot().staticOrder().size(),
                evaluation.snapshot().supportDenominator(),
                phase2.statusCounts(),
                phase2.targetShapeStates(),
                phase2.targetShapeSupports(),
                phase2.bestTargetGroups(),
                phase2.bestFeasibleGroups(),
                phase2.totalElapsedMs(),
                phase2.averageElapsedMs(),
                phase2.medianElapsedMs(),
                phase2.maxElapsedMs());

        SupportBucketedCandidateEvaluator.HistoricalSummary historical =
                evaluation.historicalSummary();
        System.out.printf(
                "DJX188 EVAL-HISTORICAL states=%d supports=%d/%d "
                        + "missedRepresentatives=%d bestTarget=%d%n",
                historical.evaluatedStates(),
                historical.distinctSupports(),
                historical.supportDenominator(),
                historical.missedRepresentatives(),
                historical.bestTargetGroups());

        SupportBucketedCandidateEvaluator.BucketSummary buckets =
                evaluation.bucketSummary();
        System.out.printf(
                "DJX188 EVAL-BUCKETS buckets=%d multiState=%d "
                        + "representativeChanged=%d targetShapeImproved=%d "
                        + "historicalMissed=%d maxStates=%d "
                        + "maxGroupImprovement=%d sizeFrequency=%s%n",
                buckets.buckets(),
                buckets.multiStateBuckets(),
                buckets.representativeChangedBuckets(),
                buckets.targetShapeImprovedBuckets(),
                buckets.historicalMissedRepresentatives(),
                buckets.maxStatesPerBucket(),
                buckets.maxGroupImprovement(),
                buckets.stateCountFrequency());

        printPlan(evaluation.globalStatePlan());
        printPlan(evaluation.supportBucketPlan());

        SupportBucketedCandidateEvaluator.ObjectiveDiagnostics objectives =
                evaluation.objectiveDiagnostics();
        System.out.printf(
                "DJX188 EVAL-MASTER-OBJECTIVES frequency=%s unique=%d "
                        + "largestBucket=%d ambiguousStates=%d "
                        + "statesWithoutObjective=%d%n",
                objectives.masterIterationFrequency(),
                objectives.masterIterationFrequency().size(),
                objectives.largestMasterIterationBucket(),
                objectives.ambiguousObjectiveStates(),
                objectives.statesWithoutObjective());
        objectives.crossBuckets().forEach(bucket -> System.out.printf(
                "  EVAL-OBJECTIVE-X objective=%s states=%d supports=%d "
                        + "feasible=%d target=%d groups=%d/%.2f/%d "
                        + "frequency=%s%n",
                Double.toString(bucket.objective()),
                bucket.states(),
                bucket.supports(),
                bucket.feasibleStates(),
                bucket.targetShapeStates(),
                bucket.minimumGroups(),
                bucket.medianGroups(),
                bucket.maximumGroups(),
                bucket.groupFrequency()));
    }

    private static void printPlan(
            SupportBucketedCandidateEvaluator.SelectionPlan plan) {
        System.out.printf(
                "DJX188 EVAL-PLAN strategy=%s finalists=%d supports=%d/%d%n",
                plan.strategy(),
                plan.finalists().size(),
                plan.distinctSupports(),
                plan.supportDenominator());
        for (int index = 0; index < plan.finalists().size(); index++) {
            SupportBucketedCandidateEvaluator.ScoredCandidate finalist =
                    plan.finalists().get(index);
            System.out.printf(
                    "  PLAN #%d state=%s supportHash=%s status=%s "
                            + "groups=%d odd=%d one=%d%n",
                    index + 1,
                    finalist.state().stateSignature(),
                    Integer.toHexString(
                            finalist.state().supportIdentity().hashCode()),
                    finalist.score().status(),
                    finalist.score().groups(),
                    finalist.score().oddGroups(),
                    finalist.score().oneGroups());
        }
    }

    private static ExactComparison evaluateExactPlans(
            SupportBucketedCandidateEvaluator.Evaluation evaluation,
            List<SolverOrderItem> items,
            OrderCompatibilityKernelAnalyzer.Options analysisOptions,
            OrderCompatibilityKernelAnalyzer.Options thresholdOptions) {
        Map<String, SupportBucketedCandidateEvaluator.ScoredCandidate> union =
                new LinkedHashMap<>();
        evaluation.globalStatePlan().finalists().forEach(candidate ->
                union.putIfAbsent(
                        candidate.state().stateSignature(), candidate));
        evaluation.supportBucketPlan().finalists().forEach(candidate ->
                union.putIfAbsent(
                        candidate.state().stateSignature(), candidate));
        List<SupportBucketedCandidateEvaluator.ScoredCandidate> exactOrder =
                union.values().stream()
                        .sorted(Comparator.comparing(candidate ->
                                candidate.state().stateSignature()))
                        .toList();
        Map<String, ExactStateEvaluation> exactByState =
                new LinkedHashMap<>();
        for (int index = 0; index < exactOrder.size(); index++) {
            SupportBucketedCandidateEvaluator.ScoredCandidate candidate =
                    exactOrder.get(index);
            OrderCompatibilityKernelAnalyzer.ThresholdAnalysis threshold =
                    OrderCompatibilityKernelAnalyzer.checkThreshold(
                            candidate.state().candidate().solution(),
                            items,
                            27,
                            1,
                            0,
                            thresholdOptions);
            OrderCompatibilityKernelAnalyzer.Analysis full = null;
            boolean confirmed = false;
            if (threshold.status()
                    == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE) {
                assertTrue(threshold.feasibleGroups() <= 27);
                assertEquals(1, threshold.feasibleOddGroups());
                assertEquals(0, threshold.feasibleOneGroups());
                full = OrderCompatibilityKernelAnalyzer.analyze(
                        candidate.state().candidate().solution(),
                        items,
                        analysisOptions);
                confirmed = full.groupOptimal()
                        && full.shapeOptimal()
                        && full.exactMinimumGroups() <= 27
                        && full.oddGroups() == 1
                        && full.oneGroups() == 0;
                if (full.groupOptimal() && full.shapeOptimal()) {
                    assertTrue(full.exactMinimumGroups() <= 27);
                    assertEquals(1, full.oddGroups());
                    assertEquals(0, full.oneGroups());
                }
            }
            ExactStateEvaluation result = new ExactStateEvaluation(
                    candidate, threshold, full, confirmed);
            exactByState.put(candidate.state().stateSignature(), result);
            System.out.printf(
                    "DJX188 EVAL-EXACT progress=%d/%d state=%s selectedBy=%s "
                            + "status=%s groups=%d odd=%d one=%d nodes=%d "
                            + "thresholdMs=%d fullConfirmed=%s fullGroups=%d%n",
                    index + 1,
                    exactOrder.size(),
                    candidate.state().stateSignature(),
                    selectedBy(evaluation, candidate.state().stateSignature()),
                    threshold.status(),
                    threshold.feasibleGroups(),
                    threshold.feasibleOddGroups(),
                    threshold.feasibleOneGroups(),
                    threshold.nodes(),
                    threshold.totalElapsedMs(),
                    confirmed,
                    full == null ? -1 : full.exactMinimumGroups());
        }

        ExactStrategySummary global = summarizeExact(
                evaluation.globalStatePlan(), exactByState);
        ExactStrategySummary bucket = summarizeExact(
                evaluation.supportBucketPlan(), exactByState);
        Set<String> globalStates = stateSignatures(
                evaluation.globalStatePlan());
        Set<String> bucketStates = stateSignatures(
                evaluation.supportBucketPlan());
        Set<String> overlap = new LinkedHashSet<>(globalStates);
        overlap.retainAll(bucketStates);
        boolean exactExhausted = exactByState.size()
                == evaluation.snapshot().staticOrder().size()
                && exactByState.values().stream()
                .allMatch(value -> conclusive(value.threshold().status()));
        boolean fixedPoolProvenInfeasible = exactExhausted
                && exactByState.values().stream()
                .allMatch(value -> value.threshold().status()
                        == OrderCompatibilityKernelAnalyzer.ThresholdStatus
                        .INFEASIBLE);
        boolean anyFeasible = exactByState.values().stream()
                .anyMatch(value -> value.threshold().status()
                        == OrderCompatibilityKernelAnalyzer.ThresholdStatus
                        .FEASIBLE);
        return new ExactComparison(
                exactByState,
                global,
                bucket,
                overlap.size(),
                exactExhausted,
                fixedPoolProvenInfeasible,
                anyFeasible);
    }

    private static ExactStrategySummary summarizeExact(
            SupportBucketedCandidateEvaluator.SelectionPlan plan,
            Map<String, ExactStateEvaluation> exactByState) {
        int feasible = 0;
        int confirmed = 0;
        int infeasible = 0;
        int unknown = 0;
        int other = 0;
        long elapsedMs = 0L;
        int bestConfirmedGroups = Integer.MAX_VALUE;
        for (SupportBucketedCandidateEvaluator.ScoredCandidate candidate
                : plan.finalists()) {
            ExactStateEvaluation result = exactByState.get(
                    candidate.state().stateSignature());
            elapsedMs += result.threshold().totalElapsedMs();
            if (result.full() != null) {
                elapsedMs += result.full().totalElapsedMs();
            }
            switch (result.threshold().status()) {
                case FEASIBLE -> feasible++;
                case INFEASIBLE -> infeasible++;
                case UNKNOWN -> unknown++;
                default -> other++;
            }
            if (result.confirmed()) {
                confirmed++;
                bestConfirmedGroups = Math.min(
                        bestConfirmedGroups,
                        result.full().exactMinimumGroups());
            }
        }
        return new ExactStrategySummary(
                plan.strategy(),
                plan.finalists().size(),
                plan.distinctSupports(),
                plan.supportDenominator(),
                feasible,
                confirmed,
                infeasible,
                unknown,
                other,
                elapsedMs,
                bestConfirmedGroups == Integer.MAX_VALUE
                        ? -1 : bestConfirmedGroups);
    }

    private static void printExactComparison(ExactComparison comparison) {
        System.out.printf(
                "DJX188 EVAL-EXACT-CACHE uniqueSolves=%d sharedStates=%d "
                        + "exactExhausted=%s fixedPoolProvenInfeasible=%s%n",
                comparison.byState().size(),
                comparison.sharedStates(),
                comparison.exactExhausted(),
                comparison.fixedPoolProvenInfeasible());
        printExactStrategy(comparison.globalState());
        printExactStrategy(comparison.supportBucket());
    }

    private static void printExactStrategy(ExactStrategySummary summary) {
        System.out.printf(
                "DJX188 EVAL-EXACT-SUMMARY strategy=%s planned=%d "
                        + "supports=%d/%d feasible=%d confirmed=%d "
                        + "infeasible=%d unknown=%d other=%d "
                        + "bestConfirmed=%d logicalElapsedMs=%d%n",
                summary.strategy(),
                summary.planned(),
                summary.distinctSupports(),
                summary.supportDenominator(),
                summary.feasible(),
                summary.confirmed(),
                summary.infeasible(),
                summary.unknown(),
                summary.other(),
                summary.bestConfirmedGroups(),
                summary.logicalElapsedMs());
    }

    private static boolean phase2SamplingBias(
            SupportBucketedCandidateEvaluator.Evaluation evaluation) {
        int fullBest = evaluation.phase2Summary().bestTargetGroups();
        int historicalBest = evaluation.historicalSummary().bestTargetGroups();
        if (fullBest >= 0
                && (historicalBest < 0 || fullBest < historicalBest)) {
            return true;
        }
        Set<String> historicalSupports = evaluation.historicalPrefix().stream()
                .map(candidate -> candidate.state().supportIdentity())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        return evaluation.globalStatePlan().finalists().stream()
                .map(candidate -> candidate.state().supportIdentity())
                .anyMatch(support -> !historicalSupports.contains(support))
                || evaluation.supportBucketPlan().finalists().stream()
                .map(candidate -> candidate.state().supportIdentity())
                .anyMatch(support -> !historicalSupports.contains(support));
    }

    private static Set<String> stateSignatures(
            SupportBucketedCandidateEvaluator.SelectionPlan plan) {
        return plan.finalists().stream()
                .map(candidate -> candidate.state().stateSignature())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private static List<SupportBucketedCandidateEvaluator.Strategy> selectedBy(
            SupportBucketedCandidateEvaluator.Evaluation evaluation,
            String stateSignature) {
        List<SupportBucketedCandidateEvaluator.Strategy> selected =
                new ArrayList<>();
        if (stateSignatures(evaluation.globalStatePlan())
                .contains(stateSignature)) {
            selected.add(SupportBucketedCandidateEvaluator.Strategy.GLOBAL_STATE);
        }
        if (stateSignatures(evaluation.supportBucketPlan())
                .contains(stateSignature)) {
            selected.add(SupportBucketedCandidateEvaluator.Strategy.SUPPORT_BUCKET);
        }
        return List.copyOf(selected);
    }

    private static boolean conclusive(
            OrderCompatibilityKernelAnalyzer.ThresholdStatus status) {
        return status == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE
                || status
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE;
    }

    private static void printLayer(
            StructuredLocalPatternUniverseBuilder.LocalPatternUniverse layer) {
        StructuredLocalPatternUniverseBuilder.CoverageProfile coverage =
                layer.coverage();
        System.out.printf(
                "DJX188 LOCAL-SCOPE scope=%s status=%s patterns=%d evidence=%d "
                        + "evidenceTypes=%s conflictWidths=%s touchPatterns=%s "
                        + "coefficientClasses=%s residuals=%s neutral=%s "
                        + "bridges=%s universe=%s uncoveredResiduals=%d%n",
                layer.scope(),
                layer.buildStatus(),
                layer.patterns().size(),
                layer.metrics().evidenceCount(),
                layer.metrics().evidenceDistribution(),
                measure(coverage.conflictWidthCoverage()),
                measure(coverage.conflictTouchPatternCoverage()),
                measure(coverage.coefficientClassCoverage()),
                measure(coverage.residualTargetCoverage()),
                measure(coverage.neutralEquationCoverage()),
                measure(coverage.bridgeEquationCoverage()),
                measure(coverage.universeSizeCoverage()),
                coverage.uncoveredResidualTargets().size());
    }

    private static String measure(
            StructuredLocalPatternUniverseBuilder.CoverageMeasure measure) {
        if (!measure.applicable()) {
            return "N/A";
        }
        return measure.numerator() + "/" + measure.denominator()
                + (measure.denominatorComplete() ? "" : "(partial-denominator)");
    }

    private static Set<String> signatures(List<PatternCandidate> patterns) {
        Set<String> signatures = new TreeSet<>();
        patterns.forEach(pattern -> signatures.add(pattern.signature()));
        return signatures;
    }

    private record ExactStateEvaluation(
            SupportBucketedCandidateEvaluator.ScoredCandidate candidate,
            OrderCompatibilityKernelAnalyzer.ThresholdAnalysis threshold,
            OrderCompatibilityKernelAnalyzer.Analysis full,
            boolean confirmed) {
    }

    private record ExactStrategySummary(
            SupportBucketedCandidateEvaluator.Strategy strategy,
            int planned,
            int distinctSupports,
            int supportDenominator,
            int feasible,
            int confirmed,
            int infeasible,
            int unknown,
            int other,
            long logicalElapsedMs,
            int bestConfirmedGroups) {
    }

    private record ExactComparison(
            Map<String, ExactStateEvaluation> byState,
            ExactStrategySummary globalState,
            ExactStrategySummary supportBucket,
            int sharedStates,
            boolean exactExhausted,
            boolean fixedPoolProvenInfeasible,
            boolean anyFeasible) {

        ExactComparison {
            byState = Map.copyOf(byState);
        }
    }
}
