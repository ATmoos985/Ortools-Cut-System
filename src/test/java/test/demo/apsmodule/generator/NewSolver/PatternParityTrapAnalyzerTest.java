package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPSolver;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternParityTrapAnalyzerTest {

    @Test
    void deterministicPeelingExplainsManualAndSystemParityGap() throws Exception {
        Map<Integer, Integer> demand = Djx188ManualBaselineFixture.demands(
                Djx188ManualBaselineFixture.loadItems());
        Map<PatternCandidate, Integer> manual = Djx188ManualBaselineFixture.solution(
                Djx188ManualBaselineFixture.loadManualPatterns());
        Map<PatternCandidate, Integer> system = Djx188SystemBaselineFixture.loadSolution();

        PatternParityTrapAnalyzer.Analysis manualAnalysis =
                PatternParityTrapAnalyzer.analyze(manual, demand);
        PatternParityTrapAnalyzer.Analysis systemAnalysis =
                PatternParityTrapAnalyzer.analyze(system, demand);

        List<Map.Entry<PatternCandidate, Integer>> reversedEntries =
                new ArrayList<>(system.entrySet());
        Collections.reverse(reversedEntries);
        Map<PatternCandidate, Integer> reversed = new LinkedHashMap<>();
        reversedEntries.forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        PatternParityTrapAnalyzer.Analysis reversedAnalysis =
                PatternParityTrapAnalyzer.analyze(reversed, demand);

        assertEquals("active-degree,residual-demand,width,pattern-signature",
                systemAnalysis.peelOrder());
        assertEquals(systemAnalysis.steps(), reversedAnalysis.steps(),
                "peeling must not depend on map insertion order");

        assertEquals(1, manualAnalysis.actualOddUsages());
        assertEquals(1, manualAnalysis.explainedOddUsages());
        assertEquals(Set.of(1280), manualAnalysis.forcedOddWidths());
        assertEquals(0, manualAnalysis.actualOneUsages());
        assertEquals(6, manualAnalysis.corePatternSignatures().size());

        assertEquals(7, systemAnalysis.actualOddUsages());
        assertEquals(7, systemAnalysis.explainedOddUsages());
        assertEquals(Set.of(665, 760, 840, 1140, 1200, 1250, 1280),
                systemAnalysis.forcedOddWidths());
        assertEquals(Set.of(665, 1200, 1250, 1280),
                systemAnalysis.initialOddAnchorWidths());
        assertEquals(Set.of(760, 840, 1140),
                systemAnalysis.cascadingOddWidths());
        assertEquals(1, systemAnalysis.actualOneUsages());
        assertEquals(5, systemAnalysis.corePatternSignatures().size());
        assertEquals(List.of(), systemAnalysis.unexplainedOddUsages());

        print("manual", manualAnalysis);
        print("system", systemAnalysis);
    }

    @Test
    void complementCoefficientsAreEnumeratedFromResidualsAndUniverse() throws Exception {
        Map<Integer, Integer> demand = Djx188ManualBaselineFixture.demands(
                Djx188ManualBaselineFixture.loadItems());
        Map<PatternCandidate, Integer> system = Djx188SystemBaselineFixture.loadSolution();
        PatternParityTrapAnalyzer.Analysis analysis =
                PatternParityTrapAnalyzer.analyze(system, demand);
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe = new CompletePatternEnumerator(params, 5)
                .generate(demand);

        ParityTrapComplementSelector.Selection selection =
                ParityTrapComplementSelector.select(
                        analysis,
                        universe,
                        system.keySet(),
                        demand,
                        params.getTotalWidth(),
                        10,
                        120);

        assertEquals(Set.of(1), selection.targets().get(665).preferredCoefficients());
        assertEquals(Set.of(1), selection.targets().get(1200).preferredCoefficients());
        assertEquals(Set.of(1), selection.targets().get(1250).preferredCoefficients());
        assertEquals(Set.of(1), selection.targets().get(1280).preferredCoefficients());
        assertEquals(ParityTrapComplementSelector.QuotientClass.SINGLE_USE,
                quotientClass(selection.targets().get(665), 2));
        assertEquals(ParityTrapComplementSelector.QuotientClass.CONTROLLED_ODD,
                quotientClass(selection.targets().get(1280), 1));
        assertEquals(ParityTrapComplementSelector.QuotientClass.REQUIRES_SPLIT,
                quotientClass(selection.targets().get(1280), 2));
        assertFalse(selection.targets().get(1280).options().stream()
                .anyMatch(option -> option.coefficient() == 3),
                "infeasible coefficients must not be invented by arithmetic alone");
        assertFalse(selection.candidates().isEmpty());
        assertTrue(selection.candidates().size() <= 120);
        assertTrue(selection.selectedPerTarget().values().stream()
                .allMatch(count -> count > 0));

        System.out.printf("PARITY-COMPLEMENT universe=%d selected=%d targets=%s perTarget=%s%n",
                universe.size(), selection.candidates().size(),
                selection.targets(), selection.selectedPerTarget());
    }

    @Test
    void feasibilityOracleSeparatesManualAndSystemPatternSets() throws Exception {
        Loader.loadNativeLibraries();
        Map<Integer, Integer> demand = Djx188ManualBaselineFixture.demands(
                Djx188ManualBaselineFixture.loadItems());
        Map<PatternCandidate, Integer> manual = Djx188ManualBaselineFixture.solution(
                Djx188ManualBaselineFixture.loadManualPatterns());
        Map<PatternCandidate, Integer> system = Djx188SystemBaselineFixture.loadSolution();

        PatternParityFeasibilityOracle.Result manualResult =
                PatternParityFeasibilityOracle.solve(
                        new ArrayList<>(manual.keySet()), demand, 4600,
                        169, 36_870, 26, 1, 5_000L, 5_000L, manual);
        PatternParityFeasibilityOracle.Result systemResult =
                PatternParityFeasibilityOracle.solve(
                        new ArrayList<>(system.keySet()), demand, 4600,
                        169, 36_870, 26, 1, 5_000L, 5_000L, system);

        assertTrue(manualResult.feasible());
        assertEquals(1, PatternParityTrapAnalyzer
                .analyze(manualResult.solution(), demand).actualOddUsages());
        assertEquals(MPSolver.ResultStatus.INFEASIBLE, systemResult.status());
        System.out.printf("PARITY-ORACLE manual=%s/%dms/%dnodes system=%s/%dms/%dnodes%n",
                manualResult.status(), manualResult.elapsedMs(), manualResult.nodes(),
                systemResult.status(), systemResult.elapsedMs(), systemResult.nodes());
    }

    @Test
    void singleOddParityKernelUsesTheDemandParityFingerprint() throws Exception {
        Map<Integer, Integer> demand = Djx188ManualBaselineFixture.demands(
                Djx188ManualBaselineFixture.loadItems());
        Map<PatternCandidate, Integer> manual = Djx188ManualBaselineFixture.solution(
                Djx188ManualBaselineFixture.loadManualPatterns());
        Map<PatternCandidate, Integer> system = Djx188SystemBaselineFixture.loadSolution();

        List<PatternCandidate> manualCompatible = manual.keySet().stream()
                .filter(pattern -> PatternParityFeasibilityOracle
                        .hasDemandParityFingerprint(pattern, demand))
                .toList();
        List<PatternCandidate> systemCompatible = system.keySet().stream()
                .filter(pattern -> PatternParityFeasibilityOracle
                        .hasDemandParityFingerprint(pattern, demand))
                .toList();

        assertEquals(1, manualCompatible.size());
        assertEquals(3, manual.get(manualCompatible.get(0)));
        assertEquals(0, systemCompatible.size(),
                "the system set cannot realize a one-odd solution even modulo 2");
        System.out.printf("PARITY-KERNEL manualCompatible=%s systemCompatible=%s%n",
                manualCompatible.stream().map(PatternCandidate::signature).toList(),
                systemCompatible.stream().map(PatternCandidate::signature).toList());
    }

    @Test
    void singleOddDecompositionReconstructsTheManualSetWithoutUsageHints() throws Exception {
        Loader.loadNativeLibraries();
        Map<Integer, Integer> demand = Djx188ManualBaselineFixture.demands(
                Djx188ManualBaselineFixture.loadItems());
        Map<PatternCandidate, Integer> manual = Djx188ManualBaselineFixture.solution(
                Djx188ManualBaselineFixture.loadManualPatterns());

        SingleOddParityDecomposer.SearchResult result =
                SingleOddParityDecomposer.search(
                        new ArrayList<>(manual.keySet()),
                        demand,
                        4600,
                        169,
                        36_870,
                        26,
                        5_000L,
                        5_000L);

        assertTrue(result.feasible());
        assertEquals(manual, result.solution());
        assertEquals(3, result.solution().get(result.selectedSeed()));
        System.out.printf("PARITY-DECOMPOSER manual seed=%s attempts=%d status=%s%n",
                result.selectedSeed().signature(), result.attempts().size(),
                result.attempts().get(result.attempts().size() - 1).status());
    }

    private static ParityTrapComplementSelector.QuotientClass quotientClass(
            ParityTrapComplementSelector.Target target,
            int coefficient) {
        return target.options().stream()
                .filter(option -> option.coefficient() == coefficient)
                .map(ParityTrapComplementSelector.CoefficientOption::quotientClass)
                .findFirst()
                .orElseThrow();
    }

    private static void print(
            String name,
            PatternParityTrapAnalyzer.Analysis analysis) {
        System.out.printf("PARITY-TRAP %-6s order=%s peeled=%d core=%d "
                        + "odd=%d explained=%d one=%d anchors=%s cascades=%s%n",
                name,
                analysis.peelOrder(),
                analysis.steps().size(),
                analysis.corePatternSignatures().size(),
                analysis.actualOddUsages(),
                analysis.explainedOddUsages(),
                analysis.actualOneUsages(),
                analysis.initialOddAnchorWidths(),
                analysis.cascadingOddWidths());
        analysis.forcedOddSteps().forEach(step -> System.out.printf(
                "  #%02d width=%d residual=%d coeff=%d usage=%d origin=%s pattern=%s%n",
                step.order(), step.width(), step.residualDemand(), step.coefficient(),
                step.forcedUsage(), step.origin(), step.patternSignature()));
    }
}
