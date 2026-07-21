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

        List<CandidateSource> candidates = uniqueCandidates(sparseRuns);
        int phase2Limit = Math.min(
                Integer.getInteger("cutting.test.local.phase2Candidates", 20),
                candidates.size());
        List<FastCandidateSource> fastCandidates = new ArrayList<>();
        for (int index = 0; index < phase2Limit; index++) {
            CandidateSource candidate = candidates.get(index);
            ProductionNeutralMoveSearch.FastUpperBound fast =
                    ProductionNeutralMoveSearch.fastUpperBound(
                            candidate.candidate().solution(), items, parameters);
            fastCandidates.add(new FastCandidateSource(candidate, fast));
            System.out.printf(
                    "DJX188 LOCAL-PHASE2 #%d scope=%s support=%s "
                            + "status=%s groups=%d odd=%d one=%d elapsedMs=%d%n",
                    index + 1,
                    candidate.scope(),
                    candidate.candidate().supportSignature(),
                    fast.status(),
                    fast.groups(),
                    fast.oddGroups(),
                    fast.oneGroups(),
                    fast.elapsedMs());
        }
        fastCandidates.sort(Comparator
                .comparing((FastCandidateSource source) ->
                        !source.fast().targetShapeFeasible())
                .thenComparingInt(source -> source.fast().groups() < 0
                        ? Integer.MAX_VALUE : source.fast().groups())
                .thenComparing(source ->
                        source.candidate().candidate().stateSignature()));

        int exactLimit = Integer.getInteger(
                "cutting.test.local.exactCandidates", 3);
        int evaluated = 0;
        int feasible = 0;
        int confirmed = 0;
        int infeasible = 0;
        int unknown = 0;
        for (FastCandidateSource ranked : fastCandidates) {
            if (evaluated >= exactLimit) {
                break;
            }
            CandidateSource candidate = ranked.candidate();
            evaluated++;
            OrderCompatibilityKernelAnalyzer.Options thresholdOptions =
                    new OrderCompatibilityKernelAnalyzer.Options(
                            Long.getLong(
                                    "cutting.test.local.thresholdMs", 60_000L),
                            0L,
                            analysisOptions.maxConfigurations(),
                            analysisOptions.nodeLimit(),
                            false);
            OrderCompatibilityKernelAnalyzer.ThresholdAnalysis threshold =
                    OrderCompatibilityKernelAnalyzer.checkThreshold(
                            candidate.candidate().solution(),
                            items,
                            27,
                            1,
                            0,
                            thresholdOptions);
            switch (threshold.status()) {
                case FEASIBLE -> {
                    feasible++;
                    OrderCompatibilityKernelAnalyzer.Analysis full =
                            OrderCompatibilityKernelAnalyzer.analyze(
                                    candidate.candidate().solution(),
                                    items,
                                    analysisOptions);
                    assertTrue(full.groupOptimal());
                    assertTrue(full.shapeOptimal());
                    assertTrue(full.exactMinimumGroups() <= 27);
                    assertEquals(1, full.oddGroups());
                    assertEquals(0, full.oneGroups());
                    confirmed++;
                    System.out.printf(
                            "DJX188 LOCAL-CONFIRMED #%d groups=%d extra=%d "
                                    + "odd=%d one=%d elapsedMs=%d%n",
                            evaluated,
                            full.exactMinimumGroups(),
                            full.exactExtraGroups(),
                            full.oddGroups(),
                            full.oneGroups(),
                            full.totalElapsedMs());
                }
                case INFEASIBLE -> infeasible++;
                default -> unknown++;
            }
            System.out.printf(
                    "DJX188 LOCAL-EXACT #%d scope=%s support=%s "
                            + "status=%s groups=%d odd=%d one=%d nodes=%d "
                            + "elapsedMs=%d%n",
                    evaluated,
                    candidate.scope(),
                    candidate.candidate().supportSignature(),
                    threshold.status(),
                    threshold.feasibleGroups(),
                    threshold.feasibleOddGroups(),
                    threshold.feasibleOneGroups(),
                    threshold.nodes(),
                    threshold.totalElapsedMs());
        }
        boolean exactExhausted = phase2Limit == candidates.size()
                && evaluated == candidates.size();
        System.out.printf(
                "DJX188 LOCAL-SUMMARY engineeringGoal=%s candidates=%d "
                        + "phase2=%d exact=%d feasible=%d confirmed=%d "
                        + "infeasible=%d unknown=%d "
                        + "exactExhausted=%s%n",
                engineeringGoal,
                candidates.size(),
                phase2Limit,
                evaluated,
                feasible,
                confirmed,
                infeasible,
                unknown,
                exactExhausted);

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

    private static List<CandidateSource> uniqueCandidates(
            Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                    SparseProductionNeutralMoveEnumerator.Result> runs) {
        Map<String, CandidateSource> unique = new LinkedHashMap<>();
        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            runs.get(scope).candidates().stream()
                    .sorted(Comparator.comparing(
                            SparseProductionNeutralMoveEnumerator.Candidate
                                    ::stateSignature))
                    .forEach(candidate -> unique.putIfAbsent(
                            candidate.stateSignature(),
                            new CandidateSource(scope, candidate)));
        }
        List<CandidateSource> ordered = unique.values().stream()
                .sorted(Comparator
                        .comparingInt((CandidateSource source) ->
                                source.candidate().delta().size())
                        .thenComparing(source ->
                                source.candidate().stateSignature()))
                .toList();
        Map<String, CandidateSource> firstBySupport = new LinkedHashMap<>();
        ordered.forEach(candidate -> firstBySupport.putIfAbsent(
                supportIdentity(candidate.candidate()), candidate));
        List<CandidateSource> diversified = new ArrayList<>(
                firstBySupport.values());
        Set<String> selectedStates = diversified.stream()
                .map(candidate -> candidate.candidate().stateSignature())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        ordered.stream()
                .filter(candidate -> selectedStates.add(
                        candidate.candidate().stateSignature()))
                .forEach(diversified::add);
        return List.copyOf(diversified);
    }

    private static String supportIdentity(
            SparseProductionNeutralMoveEnumerator.Candidate candidate) {
        return candidate.delta().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(PatternCandidate::signature)))
                .map(entry -> (entry.getValue() < 0 ? "R:" : "A:")
                        + entry.getKey().signature())
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow();
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

    private record CandidateSource(
            StructuredLocalPatternUniverseBuilder.UniverseScope scope,
            SparseProductionNeutralMoveEnumerator.Candidate candidate) {
    }

    private record FastCandidateSource(
            CandidateSource candidate,
            ProductionNeutralMoveSearch.FastUpperBound fast) {
    }
}
