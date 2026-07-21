package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparseProductionNeutralMoveEnumeratorTest {

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void fixedSupportEnumeratesEveryParityCompatibleCoefficientVector() {
        PatternCandidate source = pattern(1, 1, 1);
        PatternCandidate left = pattern(2, 2, 0);
        PatternCandidate right = pattern(3, 0, 2);
        PatternCandidate oddAnchor = pattern(4, 0, 0, 1);
        List<PatternCandidate> universe = List.of(
                source, left, right, oddAnchor);
        Map<PatternCandidate, Integer> current = Map.of(
                source, 10,
                oddAnchor, 3);

        SparseProductionNeutralMoveEnumerator.Result result =
                SparseProductionNeutralMoveEnumerator.enumerate(
                        universe,
                        current,
                        upper(universe, 10),
                        Set.of(),
                        List.of(Map.of(source, -1, left, 1, right, 1)),
                        SparseProductionNeutralMoveEnumerator.Options.testDefaults());

        assertEquals(SparseProductionNeutralMoveEnumerator.Status.EXHAUSTED,
                result.status());
        assertTrue(result.metrics().supportExhausted());
        assertTrue(result.metrics().coefficientExhausted());
        assertEquals(2, result.candidates().size());
        assertTrue(result.candidates().stream().anyMatch(candidate ->
                candidate.solution().getOrDefault(source, 0) == 6
                        && candidate.solution().getOrDefault(left, 0) == 2
                        && candidate.solution().getOrDefault(right, 0) == 2));
        assertTrue(result.candidates().stream().anyMatch(candidate ->
                candidate.solution().getOrDefault(source, 0) == 2
                        && candidate.solution().getOrDefault(left, 0) == 4
                        && candidate.solution().getOrDefault(right, 0) == 4));
        result.candidates().forEach(candidate -> {
            assertEquals(production(current), production(candidate.solution()));
            assertEquals(totalCars(current), totalCars(candidate.solution()));
            assertEquals(1, oddUsages(candidate.solution()));
            assertEquals(0, oneUsages(candidate.solution()));
        });
        assertEquals(2, result.metrics().coefficientSolutions());
        assertEquals(2, result.metrics().coefficientNoGoodCuts());
        assertEquals(1, result.metrics().seededSupportsVisited());
        assertTrue(result.metrics().supportNoGoodCuts() >= 1);
    }

    @Test
    void arbitraryCoefficientTwoForTwoIsInsideSparseLayer() {
        PatternCandidate firstSource = pattern(10, 3, 0);
        PatternCandidate secondSource = pattern(11, 0, 3);
        PatternCandidate firstTarget = pattern(12, 1, 1);
        PatternCandidate secondTarget = pattern(13, 1, 4);
        PatternCandidate oddAnchor = pattern(14, 0, 0, 1);
        List<PatternCandidate> universe = List.of(
                firstSource,
                secondSource,
                firstTarget,
                secondTarget,
                oddAnchor);
        Map<PatternCandidate, Integer> current = Map.of(
                firstSource, 2,
                secondSource, 4,
                oddAnchor, 3);

        SparseProductionNeutralMoveEnumerator.Result result =
                SparseProductionNeutralMoveEnumerator.enumerate(
                        universe,
                        current,
                        upper(universe, 8),
                        Set.of(),
                        SparseProductionNeutralMoveEnumerator.Options.testDefaults());

        assertTrue(result.candidates().stream().anyMatch(candidate ->
                candidate.solution().getOrDefault(firstSource, 0) == 0
                        && candidate.solution().getOrDefault(secondSource, 0) == 0
                        && candidate.solution().getOrDefault(firstTarget, 0) == 4
                        && candidate.solution().getOrDefault(secondTarget, 0) == 2
                        && candidate.solution().getOrDefault(oddAnchor, 0) == 3));
    }

    @Test
    void twoForOneSupportIsCoveredByTheSparseLayer() {
        PatternCandidate firstSource = pattern(30, 2, 0);
        PatternCandidate secondSource = pattern(31, 0, 2);
        PatternCandidate target = pattern(32, 1, 1);
        PatternCandidate oddAnchor = pattern(33, 0, 0, 1);
        List<PatternCandidate> universe = List.of(
                firstSource, secondSource, target, oddAnchor);
        Map<PatternCandidate, Integer> current = Map.of(
                firstSource, 2,
                secondSource, 2,
                oddAnchor, 3);
        Map<PatternCandidate, Integer> seed = Map.of(
                firstSource, -1,
                secondSource, -1,
                target, 1);

        SparseProductionNeutralMoveEnumerator.Result result =
                SparseProductionNeutralMoveEnumerator.enumerate(
                        universe,
                        current,
                        upper(universe, 8),
                        Set.of(),
                        List.of(seed),
                        oneSupportOptions());

        assertTrue(result.candidates().stream().anyMatch(candidate ->
                candidate.solution().getOrDefault(firstSource, 0) == 0
                        && candidate.solution().getOrDefault(secondSource, 0) == 0
                        && candidate.solution().getOrDefault(target, 0) == 4
                        && candidate.solution().getOrDefault(oddAnchor, 0) == 3));
    }

    @Test
    void threeForThreeSupportIsCoveredByTheSparseLayer() {
        PatternCandidate sourceOne = pattern(40, 3, 0);
        PatternCandidate sourceTwo = pattern(41, 0, 3);
        PatternCandidate sourceThree = pattern(42, 2, 2);
        PatternCandidate targetOne = pattern(43, 1, 1);
        PatternCandidate targetTwo = pattern(44, 1, 2);
        PatternCandidate targetThree = pattern(45, 3, 2);
        PatternCandidate oddAnchor = pattern(46, 0, 0, 1);
        List<PatternCandidate> universe = List.of(
                sourceOne,
                sourceTwo,
                sourceThree,
                targetOne,
                targetTwo,
                targetThree,
                oddAnchor);
        Map<PatternCandidate, Integer> current = Map.of(
                sourceOne, 2,
                sourceTwo, 2,
                sourceThree, 2,
                oddAnchor, 3);
        Map<PatternCandidate, Integer> seed = Map.of(
                sourceOne, -1,
                sourceTwo, -1,
                sourceThree, -1,
                targetOne, 1,
                targetTwo, 1,
                targetThree, 1);

        SparseProductionNeutralMoveEnumerator.Result result =
                SparseProductionNeutralMoveEnumerator.enumerate(
                        universe,
                        current,
                        upper(universe, 8),
                        Set.of(),
                        List.of(seed),
                        oneSupportOptions());

        assertTrue(result.candidates().stream().anyMatch(candidate ->
                candidate.solution().getOrDefault(sourceOne, 0) == 0
                        && candidate.solution().getOrDefault(sourceTwo, 0) == 0
                        && candidate.solution().getOrDefault(sourceThree, 0) == 0
                        && candidate.solution().getOrDefault(targetOne, 0) == 2
                        && candidate.solution().getOrDefault(targetTwo, 0) == 2
                        && candidate.solution().getOrDefault(targetThree, 0) == 2
                        && candidate.solution().getOrDefault(oddAnchor, 0) == 3));
    }

    @Test
    void explicitCoefficientCapIsReportedAsCapped() {
        PatternCandidate source = pattern(20, 1, 1);
        PatternCandidate left = pattern(21, 2, 0);
        PatternCandidate right = pattern(22, 0, 2);
        PatternCandidate oddAnchor = pattern(23, 0, 0, 1);
        List<PatternCandidate> universe = List.of(source, left, right, oddAnchor);
        Map<PatternCandidate, Integer> current = Map.of(source, 10, oddAnchor, 3);
        SparseProductionNeutralMoveEnumerator.Options capped =
                new SparseProductionNeutralMoveEnumerator.Options(
                        30_000L,
                        10_000L,
                        10_000L,
                        -1L,
                        100,
                        1,
                        100,
                        Set.of(),
                        Set.of());

        SparseProductionNeutralMoveEnumerator.Result result =
                SparseProductionNeutralMoveEnumerator.enumerate(
                        universe, current, upper(universe, 10), Set.of(), capped);

        assertEquals(SparseProductionNeutralMoveEnumerator.Status.CAPPED,
                result.status());
        assertEquals(1, result.metrics().coefficientSolutions());
        assertTrue(!result.metrics().coefficientExhausted());
    }

    @Test
    void completionStatusKeepsAllBoundariesDistinct() {
        assertEquals(SparseProductionNeutralMoveEnumerator.Status.SOLVER_UNAVAILABLE,
                SparseProductionNeutralMoveEnumerator.classifyStatus(
                        true, false, false, false, false, false));
        assertEquals(SparseProductionNeutralMoveEnumerator.Status.ABNORMAL,
                SparseProductionNeutralMoveEnumerator.classifyStatus(
                        false, true, false, false, false, false));
        assertEquals(SparseProductionNeutralMoveEnumerator.Status.CAPPED,
                SparseProductionNeutralMoveEnumerator.classifyStatus(
                        false, false, true, true, false, false));
        assertEquals(SparseProductionNeutralMoveEnumerator.Status.TIMED_OUT,
                SparseProductionNeutralMoveEnumerator.classifyStatus(
                        false, false, true, false, true, false));
        assertEquals(SparseProductionNeutralMoveEnumerator.Status.PARTIAL,
                SparseProductionNeutralMoveEnumerator.classifyStatus(
                        false, false, true, false, false, false));
        assertEquals(SparseProductionNeutralMoveEnumerator.Status.EXHAUSTED,
                SparseProductionNeutralMoveEnumerator.classifyStatus(
                        false, false, false, false, false, true));
    }

    private static int totalCars(Map<PatternCandidate, Integer> solution) {
        return solution.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int oddUsages(Map<PatternCandidate, Integer> solution) {
        return (int) solution.values().stream().filter(value -> value % 2 != 0).count();
    }

    private static int oneUsages(Map<PatternCandidate, Integer> solution) {
        return (int) solution.values().stream().filter(value -> value == 1).count();
    }

    private static Map<Integer, Integer> production(
            Map<PatternCandidate, Integer> solution) {
        Map<Integer, Integer> production = new TreeMap<>();
        solution.forEach((pattern, usage) -> pattern.getPattern().forEach(
                (width, coefficient) -> production.merge(
                        width, coefficient * usage, Integer::sum)));
        return production;
    }

    private static Map<PatternCandidate, Integer> upper(
            List<PatternCandidate> patterns,
            int value) {
        Map<PatternCandidate, Integer> upper = new LinkedHashMap<>();
        patterns.forEach(pattern -> upper.put(pattern, value));
        return upper;
    }

    private static SparseProductionNeutralMoveEnumerator.Options oneSupportOptions() {
        return new SparseProductionNeutralMoveEnumerator.Options(
                30_000L,
                10_000L,
                10_000L,
                -1L,
                1,
                100,
                100,
                Set.of(),
                Set.of());
    }

    private static PatternCandidate pattern(
            int rollWidth,
            int first,
            int second) {
        return pattern(rollWidth, first, second, 0);
    }

    private static PatternCandidate pattern(
            int rollWidth,
            int first,
            int second,
            int anchor) {
        Map<Integer, Integer> cuts = new LinkedHashMap<>();
        if (first > 0) {
            cuts.put(1000, first);
        }
        if (second > 0) {
            cuts.put(1200, second);
        }
        if (anchor > 0) {
            cuts.put(1500, anchor);
        }
        return new PatternCandidate(cuts, rollWidth);
    }
}
