package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.MultiStageMIPSolver;
import test.demo.apsmodule.generator.NewSolver.mip.PatternAlignmentContext;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.output.InstructionConverter;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Research-only closed-loop experiment; no manual pattern is used as an input. */
class Djx188ParityTrapComplementExperimentTest {

    private static final Map<String, String> A_LAYER_OVERRIDES = Map.of(
            "cutting.quality", "true",
            "cutting.aLayerParityPenalty", "0.1",
            "cutting.aLayerStage4NodeLimit", "2000",
            "cutting.aLayerStage4SafetyTimeLimitMs", "20000");

    private static final Map<String, String> CONVERSION_OVERRIDES = Map.ofEntries(
            Map.entry("cutting.quality", "true"),
            Map.entry("cutting.lns.enabled", "true"),
            Map.entry("cutting.lns.enrichPatterns", "false"),
            Map.entry("cutting.phase2.enabled", "false"),
            Map.entry("cutting.spr.enabled", "false"));

    @Test
    void trapDirectedPoolIsReSolvedAndCheckedForNewTraps() throws Exception {
        Loader.loadNativeLibraries();
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        Map<PatternCandidate, Integer> baseline =
                Djx188SystemBaselineFixture.loadSolution();
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        params.setTimeoutMs(40_000L);

        PatternParityTrapAnalyzer.Analysis before =
                PatternParityTrapAnalyzer.analyze(baseline, demands);
        List<PatternCandidate> universe = new CompletePatternEnumerator(params, 5)
                .generate(demands);
        ParityTrapComplementSelector.Selection selection =
                ParityTrapComplementSelector.select(
                        before,
                        universe,
                        baseline.keySet(),
                        demands,
                        params.getTotalWidth(),
                        30,
                        300);
        List<PatternCandidate> pool = merge(baseline.keySet(), selection.candidates());

        PatternParityFeasibilityOracle.Result oracle =
                PatternParityFeasibilityOracle.solve(
                        pool,
                        demands,
                        params.getTotalWidth(),
                        169,
                        36_870,
                        26,
                        1,
                        20_000L,
                        20_000L,
                        baseline);

        Set<String> manualSignatures = new HashSet<>();
        Djx188ManualBaselineFixture.loadManualPatterns().forEach(pattern ->
                manualSignatures.add(pattern.candidate().signature()));
        long manualDiagnosticsOnly = selection.candidates().stream()
                .filter(pattern -> manualSignatures.contains(pattern.signature()))
                .count();

        long startedAt = System.currentTimeMillis();
        MultiStageMIPSolver.SolveCandidate refined = SolverRuntimeProperties.withOverrides(
                A_LAYER_OVERRIDES,
                () -> new MultiStageMIPSolver(params).refinePrimaryWithPatterns(
                        pool,
                        demands,
                        Set.of(),
                        baseline,
                        0,
                        0.0,
                        PatternAlignmentContext.empty()));
        assertNotNull(refined, "trap-directed Stage4 refinement must return a plan");
        Map<PatternCandidate, Integer> solution = refined.result().getSolution();
        assertFalse(solution.isEmpty());
        assertExactDemand(solution, demands);
        assertEquals(169, refined.result().getTotalRolls());
        assertEquals(36_870, refined.result().getTotalWaste());
        assertEquals(0, refined.result().getTotalOverProduction());

        PatternParityTrapAnalyzer.Analysis after =
                PatternParityTrapAnalyzer.analyze(solution, demands);
        Set<Integer> newForcedOddWidths = new LinkedHashSet<>(after.forcedOddWidths());
        newForcedOddWidths.removeAll(before.forcedOddWidths());
        Metrics metrics = evaluate(solution, params, items, demands);

        System.out.printf("DJX188 TRAP-CLOSED-LOOP universe=%d complements=%d pool=%d "
                        + "manualDiagnostic=%d oracle=%s/%dms/%dnodes elapsedMs=%d%n",
                universe.size(), selection.candidates().size(), pool.size(),
                manualDiagnosticsOnly, oracle.status(), oracle.elapsedMs(), oracle.nodes(),
                System.currentTimeMillis() - startedAt);
        System.out.printf("  before patterns=%d odd=%d one=%d forced=%s unexplained=%s%n",
                baseline.size(), before.actualOddUsages(), before.actualOneUsages(),
                before.forcedOddWidths(), before.unexplainedOddUsages());
        System.out.printf("  after  patterns=%d odd=%d one=%d forced=%s newForced=%s "
                        + "unexplained=%s groups=%d%n",
                solution.size(), after.actualOddUsages(), after.actualOneUsages(),
                after.forcedOddWidths(), newForcedOddWidths,
                after.unexplainedOddUsages(), metrics.groups());
        System.out.printf("  coefficientTargets=%s selectedPerTarget=%s%n",
                selection.targets(), selection.selectedPerTarget());
    }

    @Test
    void completeUniverseOracleSearchesForCompatibleSetWithoutManualHint() throws Exception {
        Loader.loadNativeLibraries();
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        Map<PatternCandidate, Integer> systemBaseline =
                Djx188SystemBaselineFixture.loadSolution();
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe = new CompletePatternEnumerator(params, 5)
                .generate(demands);

        long parityCompatible = universe.stream()
                .filter(pattern -> PatternParityFeasibilityOracle
                        .hasDemandParityFingerprint(pattern, demands))
                .count();
        PatternParityFeasibilityOracle.Result oracle =
                PatternParityFeasibilityOracle.solveSingleOddParityKernel(
                        universe,
                        demands,
                        params.getTotalWidth(),
                        169,
                        36_870,
                        26,
                        60_000L,
                        100_000L,
                        systemBaseline);

        System.out.printf("DJX188 COMPLETE-UNIVERSE ORACLE status=%s elapsedMs=%d nodes=%d "
                        + "patterns=%d parityCompatible=%d%n",
                oracle.status(), oracle.elapsedMs(), oracle.nodes(), oracle.solution().size(),
                parityCompatible);
        if (!oracle.feasible()) {
            return;
        }

        assertExactDemand(oracle.solution(), demands);
        PatternParityTrapAnalyzer.Analysis analysis =
                PatternParityTrapAnalyzer.analyze(oracle.solution(), demands);
        assertEquals(1, analysis.actualOddUsages());
        assertEquals(0, analysis.actualOneUsages());
        assertTrue(oracle.solution().size() <= 26);
        oracle.solution().entrySet().stream()
                .sorted(Map.Entry.<PatternCandidate, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().signature()))
                .forEach(entry -> System.out.printf("  %2d x %s%n",
                        entry.getValue(), entry.getKey().signature()));
        System.out.printf("  forced=%s anchors=%s cascades=%s core=%d unexplained=%s%n",
                analysis.forcedOddWidths(), analysis.initialOddAnchorWidths(),
                analysis.cascadingOddWidths(), analysis.corePatternSignatures().size(),
                analysis.unexplainedOddUsages());
    }

    @Test
    void parityDecompositionSearchesEightOddCoresWithoutManualHint() throws Exception {
        Loader.loadNativeLibraries();
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe = new CompletePatternEnumerator(params, 5)
                .generate(demands);

        SingleOddParityDecomposer.SearchResult result =
                SingleOddParityDecomposer.search(
                        universe,
                        demands,
                        params.getTotalWidth(),
                        169,
                        36_870,
                        26,
                        15_000L,
                        20_000L);

        System.out.printf("DJX188 PARITY-DECOMPOSITION seeds=%d attempts=%d feasible=%s%n",
                result.seedCount(), result.attempts().size(), result.feasible());
        result.attempts().forEach(attempt -> System.out.printf(
                "  seed=%s status=%s elapsedMs=%d nodes=%d patterns=%d withinCap=%s%n",
                attempt.seed().signature(), attempt.status(),
                attempt.elapsedMs(), attempt.nodes(), attempt.solution().size(),
                attempt.withinPatternCap()));
        Map<PatternCandidate, Integer> finalSolution = result.solution();
        if (!result.feasible() && result.hasRelaxedSolution()) {
            Map<String, PatternCandidate> bundlePoolBySignature = new LinkedHashMap<>();
            result.attempts().stream()
                    .filter(SingleOddParityDecomposer.SeedAttempt::feasible)
                    .flatMap(attempt -> attempt.solution().keySet().stream())
                    .forEach(pattern -> bundlePoolBySignature.putIfAbsent(
                            pattern.signature(), pattern));
            List<PatternCandidate> bundlePool = new ArrayList<>(
                    bundlePoolBySignature.values());
            SingleOddPatternSupportCompressor.Result compressed =
                    SingleOddPatternSupportCompressor.solve(
                            bundlePool,
                            demands,
                            params.getTotalWidth(),
                            169,
                            36_870,
                            1,
                            30_000L,
                            30_000L,
                            result.solution());
            System.out.printf("  bundle compression pool=%d status=%s elapsedMs=%d "
                            + "nodes=%d patterns=%d bestBound=%.2f%n",
                    bundlePool.size(), compressed.status(), compressed.elapsedMs(),
                    compressed.nodes(), compressed.solution().size(), compressed.bestBound());
            if (compressed.feasible() && compressed.solution().size() <= 26) {
                finalSolution = compressed.solution();
                if (finalSolution.values().stream().anyMatch(value -> value == 1)) {
                    SingleOddPatternSupportCompressor.Result noSingle =
                            SingleOddPatternSupportCompressor.solve(
                                    bundlePool,
                                    demands,
                                    params.getTotalWidth(),
                                    169,
                                    36_870,
                                    2,
                                    30_000L,
                                    30_000L,
                                    finalSolution);
                    System.out.printf("  no-single compression status=%s elapsedMs=%d "
                                    + "nodes=%d patterns=%d bestBound=%.2f%n",
                            noSingle.status(), noSingle.elapsedMs(), noSingle.nodes(),
                            noSingle.solution().size(), noSingle.bestBound());
                    if (noSingle.feasible() && noSingle.solution().size() <= 26) {
                        finalSolution = noSingle.solution();
                    }
                }
            } else {
                System.out.printf("  best relaxed seed=%s patterns=%d%n",
                        result.selectedSeed().signature(), result.solution().size());
                return;
            }
        } else if (!result.feasible()) {
            return;
        }

        assertExactDemand(finalSolution, demands);
        PatternParityTrapAnalyzer.Analysis analysis =
                PatternParityTrapAnalyzer.analyze(finalSolution, demands);
        Metrics metrics = evaluate(finalSolution, params, items, demands);
        assertEquals(169, finalSolution.values().stream()
                .mapToInt(Integer::intValue).sum());
        assertEquals(36_870, finalSolution.entrySet().stream()
                .mapToInt(entry -> entry.getKey().getRealWaste(params.getTotalWidth())
                        * entry.getValue())
                .sum());
        assertEquals(1, analysis.actualOddUsages());
        assertEquals(0, analysis.actualOneUsages());
        assertTrue(finalSolution.size() <= 26);
        System.out.printf("  winner seed=%s patterns=%d odd=%d one=%d groups=%d "
                        + "forced=%s unexplained=%s%n",
                result.selectedSeed().signature(), finalSolution.size(),
                analysis.actualOddUsages(), analysis.actualOneUsages(), metrics.groups(),
                analysis.forcedOddWidths(), analysis.unexplainedOddUsages());
        finalSolution.entrySet().stream()
                .sorted(Map.Entry.<PatternCandidate, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().signature()))
                .forEach(entry -> System.out.printf("  %2d x %s%n",
                        entry.getValue(), entry.getKey().signature()));
    }

    private static Metrics evaluate(
            Map<PatternCandidate, Integer> solution,
            SolverParameters params,
            List<SolverOrderItem> items,
            Map<Integer, Integer> demands) {
        InstructionConverter.ConversionResult converted =
                SolverRuntimeProperties.withOverrides(
                        CONVERSION_OVERRIDES,
                        () -> new InstructionConverter(params)
                                .convertWithoutSetPartition(
                                        solution, "djx188-trap", items, demands));
        List<CuttingInstruction> instructions = converted.instructions();
        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);
        return new Metrics(
                stats.groups(), stats.oddCarGroups(), stats.oneCarGroups());
    }

    private static List<PatternCandidate> merge(
            Iterable<PatternCandidate> baseline,
            Iterable<PatternCandidate> additions) {
        Map<String, PatternCandidate> merged = new LinkedHashMap<>();
        baseline.forEach(pattern -> merged.putIfAbsent(pattern.signature(), pattern));
        additions.forEach(pattern -> merged.putIfAbsent(pattern.signature(), pattern));
        return new ArrayList<>(merged.values());
    }

    private static void assertExactDemand(
            Map<PatternCandidate, Integer> solution,
            Map<Integer, Integer> demands) {
        Map<Integer, Integer> production = new TreeMap<>();
        solution.forEach((pattern, usage) -> pattern.getPattern().forEach(
                (width, count) -> production.merge(width, count * usage, Integer::sum)));
        assertEquals(demands, production);
    }

    private record Metrics(int groups, int odd, int one) {
    }
}
