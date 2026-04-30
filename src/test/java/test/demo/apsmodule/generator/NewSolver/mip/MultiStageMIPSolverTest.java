package test.demo.apsmodule.generator.NewSolver.mip;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.model.SolverResult;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiStageMIPSolverTest {

    @BeforeAll
    static void loadOrTools() {
        Loader.loadNativeLibraries();
    }

    @Test
    void solveRepairsUnderProducedWidthsBeforeAdvancing() {
        SolverParameters params = testParams();
        MultiStageMIPSolver solver = new MultiStageMIPSolver(params);

        List<PatternCandidate> patterns = new ArrayList<>();
        patterns.add(pattern(linkedPattern(850, 1), 850));

        Map<Integer, Integer> demands = new LinkedHashMap<>();
        demands.put(840, 1);
        demands.put(850, 1);

        SolverResult result = solver.solve(patterns, demands, Set.of(850));

        assertTrue(result.isSuccess());
        assertTrue(patterns.stream().anyMatch(candidate -> candidate.getPattern().containsKey(840)));

        Map<Integer, Integer> produced = produce(result.getSolution());
        assertEquals(1, produced.getOrDefault(840, 0));
        assertEquals(1, produced.getOrDefault(850, 0));
        assertEquals(0, result.getTotalOverProduction());
    }

    @Test
    void solveFailsWhenTargetWidthCannotBeRepaired() {
        SolverParameters params = testParams();
        MultiStageMIPSolver solver = new MultiStageMIPSolver(params);

        List<PatternCandidate> patterns = new ArrayList<>();
        patterns.add(pattern(linkedPattern(850, 1), 850));

        Map<Integer, Integer> demands = new LinkedHashMap<>();
        demands.put(840, 1);

        SolverResult result = solver.solve(patterns, demands, Set.of(850));

        assertFalse(result.isSuccess());
    }

    @Test
    void stage3RejectsLowerPatternSolutionOutsideWasteGuard() throws Exception {
        SolverParameters params = guardedWasteParams();
        MultiStageMIPSolver solver = new MultiStageMIPSolver(params);
        Method stage3 = stage3Method();

        PatternCandidate filler390 = pattern(linkedPattern(10, 1, 390, 1), 400);
        PatternCandidate filler380 = pattern(linkedPattern(20, 1, 380, 1), 400);
        PatternCandidate sparse = pattern(linkedPattern(10, 1, 20, 1), 400);
        List<PatternCandidate> patterns = List.of(filler390, filler380, sparse);

        Map<Integer, Integer> demands = new LinkedHashMap<>();
        demands.put(10, 1);
        demands.put(20, 1);
        demands.put(380, 0);
        demands.put(390, 0);

        Map<PatternCandidate, Integer> hintSolution = new LinkedHashMap<>();
        hintSolution.put(filler390, 1);
        hintSolution.put(filler380, 1);

        @SuppressWarnings("unchecked")
        Map<PatternCandidate, Integer> result = (Map<PatternCandidate, Integer>) stage3.invoke(
                solver, patterns, demands, Set.of(380, 390), 2, 200, hintSolution);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(1, result.getOrDefault(filler390, 0));
        assertEquals(1, result.getOrDefault(filler380, 0));
        assertEquals(0, result.getOrDefault(sparse, 0));
    }

    @Test
    void stage3ChoosesFewerPatternsInsideWasteGuard() throws Exception {
        SolverParameters params = guardedWasteParams();
        MultiStageMIPSolver solver = new MultiStageMIPSolver(params);
        Method stage3 = stage3Method();

        PatternCandidate filler390 = pattern(linkedPattern(10, 1, 390, 1), 400);
        PatternCandidate filler380 = pattern(linkedPattern(20, 1, 380, 1), 400);
        PatternCandidate sparse = pattern(linkedPattern(10, 1, 20, 1), 400);
        List<PatternCandidate> patterns = List.of(filler390, filler380, sparse);

        Map<Integer, Integer> demands = new LinkedHashMap<>();
        demands.put(10, 1);
        demands.put(20, 1);
        demands.put(380, 0);
        demands.put(390, 0);

        @SuppressWarnings("unchecked")
        Map<PatternCandidate, Integer> result = (Map<PatternCandidate, Integer>) stage3.invoke(
                solver, patterns, demands, Set.of(380, 390), 2, 400, Map.of());

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1, result.getOrDefault(sparse, 0));
    }

    @Test
    void solveCandidatesUsesLegacyOrderPatternSelectionWhenAvailable() {
        SolverParameters params = guardedWasteParams();
        MultiStageMIPSolver solver = new MultiStageMIPSolver(params);

        PatternCandidate filler390 = pattern(linkedPattern(10, 1, 390, 1), 400);
        PatternCandidate filler380 = pattern(linkedPattern(20, 1, 380, 1), 400);
        PatternCandidate sparse = pattern(linkedPattern(10, 1, 20, 1), 400);
        List<PatternCandidate> patterns = List.of(filler390, filler380, sparse);

        Map<Integer, Integer> demands = new LinkedHashMap<>();
        demands.put(10, 1);
        demands.put(20, 1);
        demands.put(380, 0);
        demands.put(390, 0);

        List<MultiStageMIPSolver.SolveCandidate> candidates = solver.solveCandidates(
                patterns,
                demands,
                Set.of(380, 390));

        assertEquals(1, candidates.size());
        assertEquals("legacy-best-waste", candidates.get(0).name());
        assertEquals(1, candidates.get(0).result().getPatternCount());
        assertEquals(370, candidates.get(0).result().getTotalWaste());
    }

    @Test
    void allowOverSetStaysBoundedAcrossRepairAttempts() throws Exception {
        SolverParameters params = testParams();
        params.setForceAllowOverWidths(Set.of(990));
        MultiStageMIPSolver solver = new MultiStageMIPSolver(params);

        Method method = MultiStageMIPSolver.class.getDeclaredMethod(
                "buildAllowOverSetForAttempt", int.class, Map.class, Set.class, int.class);
        method.setAccessible(true);

        Map<Integer, Integer> demands = new LinkedHashMap<>();
        demands.put(850, 10);
        demands.put(1000, 9);
        demands.put(1100, 8);
        demands.put(980, 7);
        demands.put(970, 6);

        @SuppressWarnings("unchecked")
        Set<Integer> attempt0 = (Set<Integer>) method.invoke(solver, 0, demands, Set.of(850), 1);
        @SuppressWarnings("unchecked")
        Set<Integer> attempt2 = (Set<Integer>) method.invoke(solver, 2, demands, Set.of(850), 1);

        assertEquals(Set.of(850, 990), attempt0);
        assertEquals(Set.of(850, 980, 1000, 970, 1100, 990), attempt2);
    }

    private static Method stage3Method() throws NoSuchMethodException {
        Method method = MultiStageMIPSolver.class.getDeclaredMethod(
                "solveMIPStage3MinPatterns", List.class, Map.class, Set.class, int.class, int.class, Map.class);
        method.setAccessible(true);
        return method;
    }

    private static SolverParameters testParams() {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(1);
        params.setMaxRollWidth(5000);
        params.setStepSize(1);
        params.setTotalWidth(2000);
        params.setTotalOverCap(1);
        params.setTopK(1);
        params.setTimeoutMs(5000);
        params.setStage4TimeLimit(5000);
        return params;
    }

    private static SolverParameters guardedWasteParams() {
        SolverParameters params = SolverParameters.createDefault();
        params.setTotalWidth(400);
        params.setTotalOverCap(2);
        params.setTimeoutMs(5000);
        params.setStage4TimeLimit(5000);
        return params;
    }

    private static PatternCandidate pattern(Map<Integer, Integer> subRolls, int rollWidth) {
        return new PatternCandidate(subRolls, rollWidth);
    }

    private static Map<Integer, Integer> linkedPattern(int widthA, int countA) {
        Map<Integer, Integer> pattern = new LinkedHashMap<>();
        pattern.put(widthA, countA);
        return pattern;
    }

    private static Map<Integer, Integer> linkedPattern(int widthA, int countA, int widthB, int countB) {
        Map<Integer, Integer> pattern = new LinkedHashMap<>();
        pattern.put(widthA, countA);
        pattern.put(widthB, countB);
        return pattern;
    }

    private static Map<Integer, Integer> produce(Map<PatternCandidate, Integer> solution) {
        Map<Integer, Integer> produced = new LinkedHashMap<>();
        for (Map.Entry<PatternCandidate, Integer> entry : solution.entrySet()) {
            for (Map.Entry<Integer, Integer> cut : entry.getKey().getPattern().entrySet()) {
                produced.merge(cut.getKey(), cut.getValue() * entry.getValue(), Integer::sum);
            }
        }
        return produced;
    }
}
