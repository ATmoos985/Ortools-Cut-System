package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitTwoForTwoMoveEnumeratorTest {

    @Test
    void complementLookupMatchesBruteForceAndEnumeratesEveryScale() {
        PatternCandidate left = pattern(2000, 2, 0);
        PatternCandidate right = pattern(2000, 0, 2);
        PatternCandidate middle = pattern(2000, 1, 1);
        List<PatternCandidate> universe = List.of(left, right, middle);
        Map<PatternCandidate, Integer> current = Map.of(left, 2, right, 2);
        Map<PatternCandidate, Integer> upper = upper(universe, 4);

        UnitTwoForTwoMoveEnumerator.Result result =
                UnitTwoForTwoMoveEnumerator.enumerate(universe, current, upper);

        assertTrue(result.exhausted());
        assertEquals(bruteForceStates(universe, current, upper),
                stateSignatures(result));
        assertEquals(Set.of(1, 2), result.candidates().stream()
                .map(UnitTwoForTwoMoveEnumerator.Candidate::scale)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(2L, result.metrics().expandedScaleCandidates());
        assertEquals(2L, result.metrics().uniqueStates());
        result.candidates().forEach(candidate ->
                assertProductionNeutral(current, candidate.solution()));
    }

    @Test
    void repeatedSourcePairUsesMultiplicityInMaximumScale() {
        PatternCandidate left = pattern(2000, 2, 0);
        PatternCandidate right = pattern(2000, 0, 2);
        PatternCandidate middle = pattern(2000, 1, 1);
        List<PatternCandidate> universe = List.of(left, right, middle);
        Map<PatternCandidate, Integer> current = Map.of(middle, 4);
        Map<PatternCandidate, Integer> upper = upper(universe, 4);

        UnitTwoForTwoMoveEnumerator.Result result =
                UnitTwoForTwoMoveEnumerator.enumerate(universe, current, upper);

        assertEquals(bruteForceStates(universe, current, upper),
                stateSignatures(result));
        assertEquals(Set.of(1, 2), result.candidates().stream()
                .map(UnitTwoForTwoMoveEnumerator.Candidate::scale)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.candidates().stream().allMatch(candidate ->
                candidate.move().unitDelta().get(middle) == -2));
    }

    @Test
    void positiveUpperBoundLimitsScaleWithoutSkippingIntermediateValue() {
        PatternCandidate left = pattern(2000, 2, 0);
        PatternCandidate right = pattern(2000, 0, 2);
        PatternCandidate middle = pattern(2000, 1, 1);
        List<PatternCandidate> universe = List.of(left, right, middle);
        Map<PatternCandidate, Integer> current = Map.of(left, 3, right, 3);
        Map<PatternCandidate, Integer> upper = new LinkedHashMap<>();
        upper.put(left, 4);
        upper.put(right, 4);
        upper.put(middle, 3);

        UnitTwoForTwoMoveEnumerator.Result result =
                UnitTwoForTwoMoveEnumerator.enumerate(universe, current, upper);

        assertEquals(bruteForceStates(universe, current, upper),
                stateSignatures(result));
        assertEquals(1, result.candidates().size());
        assertEquals(1, result.candidates().get(0).scale());
        assertEquals(1, result.candidates().get(0).move().maxScale());
    }

    private static Set<String> stateSignatures(
            UnitTwoForTwoMoveEnumerator.Result result) {
        Set<String> signatures = new LinkedHashSet<>();
        result.candidates().forEach(candidate ->
                signatures.add(candidate.stateSignature()));
        return signatures;
    }

    /** Independent all-pairs oracle used to verify the complement index. */
    private static Set<String> bruteForceStates(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upper) {
        List<PatternCandidate> patterns = universe.stream()
                .sorted(Comparator.comparing(PatternCandidate::signature))
                .toList();
        int[] usage = new int[patterns.size()];
        int[] caps = new int[patterns.size()];
        for (int index = 0; index < patterns.size(); index++) {
            PatternCandidate pattern = patterns.get(index);
            usage[index] = current.entrySet().stream()
                    .filter(entry -> entry.getKey().signature()
                            .equals(pattern.signature()))
                    .mapToInt(Map.Entry::getValue)
                    .findFirst().orElse(0);
            caps[index] = upper.entrySet().stream()
                    .filter(entry -> entry.getKey().signature()
                            .equals(pattern.signature()))
                    .mapToInt(Map.Entry::getValue)
                    .findFirst().orElseThrow();
        }

        Set<String> states = new LinkedHashSet<>();
        for (int sourceLeft = 0; sourceLeft < patterns.size(); sourceLeft++) {
            if (usage[sourceLeft] <= 0) {
                continue;
            }
            for (int sourceRight = sourceLeft;
                    sourceRight < patterns.size(); sourceRight++) {
                if (usage[sourceRight] <= 0
                        || (sourceLeft == sourceRight && usage[sourceLeft] < 2)) {
                    continue;
                }
                for (int targetLeft = 0;
                        targetLeft < patterns.size(); targetLeft++) {
                    for (int targetRight = targetLeft;
                            targetRight < patterns.size(); targetRight++) {
                        if (!samePairProduction(
                                patterns.get(sourceLeft), patterns.get(sourceRight),
                                patterns.get(targetLeft), patterns.get(targetRight))) {
                            continue;
                        }
                        TreeMap<Integer, Integer> delta = new TreeMap<>();
                        delta.merge(sourceLeft, -1, Integer::sum);
                        delta.merge(sourceRight, -1, Integer::sum);
                        delta.merge(targetLeft, 1, Integer::sum);
                        delta.merge(targetRight, 1, Integer::sum);
                        delta.values().removeIf(value -> value == 0);
                        if (delta.isEmpty()) {
                            continue;
                        }
                        int maxScale = Integer.MAX_VALUE;
                        for (Map.Entry<Integer, Integer> entry : delta.entrySet()) {
                            int index = entry.getKey();
                            int coefficient = entry.getValue();
                            int bound = coefficient < 0
                                    ? usage[index] / -coefficient
                                    : (caps[index] - usage[index]) / coefficient;
                            maxScale = Math.min(maxScale, bound);
                        }
                        for (int scale = 1; scale <= maxScale; scale++) {
                            int[] next = usage.clone();
                            for (Map.Entry<Integer, Integer> entry
                                    : delta.entrySet()) {
                                next[entry.getKey()] += scale * entry.getValue();
                            }
                            states.add(stateSignature(patterns, next));
                        }
                    }
                }
            }
        }
        return states;
    }

    private static boolean samePairProduction(
            PatternCandidate sourceLeft,
            PatternCandidate sourceRight,
            PatternCandidate targetLeft,
            PatternCandidate targetRight) {
        Set<Integer> widths = new LinkedHashSet<>();
        widths.addAll(sourceLeft.getPattern().keySet());
        widths.addAll(sourceRight.getPattern().keySet());
        widths.addAll(targetLeft.getPattern().keySet());
        widths.addAll(targetRight.getPattern().keySet());
        return widths.stream().allMatch(width ->
                sourceLeft.getPattern().getOrDefault(width, 0)
                        + sourceRight.getPattern().getOrDefault(width, 0)
                        == targetLeft.getPattern().getOrDefault(width, 0)
                        + targetRight.getPattern().getOrDefault(width, 0));
    }

    private static void assertProductionNeutral(
            Map<PatternCandidate, Integer> before,
            Map<PatternCandidate, Integer> after) {
        assertEquals(totalCars(before), totalCars(after));
        assertEquals(production(before), production(after));
    }

    private static int totalCars(Map<PatternCandidate, Integer> solution) {
        return solution.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static Map<Integer, Integer> production(
            Map<PatternCandidate, Integer> solution) {
        Map<Integer, Integer> production = new TreeMap<>();
        solution.forEach((pattern, usage) -> pattern.getPattern().forEach(
                (width, coefficient) -> production.merge(
                        width, coefficient * usage, Integer::sum)));
        return production;
    }

    private static String stateSignature(
            List<PatternCandidate> patterns,
            int[] values) {
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            if (values[index] > 0) {
                parts.add(patterns.get(index).signature() + "=" + values[index]);
            }
        }
        return String.join(";", parts);
    }

    private static Map<PatternCandidate, Integer> upper(
            List<PatternCandidate> patterns,
            int value) {
        Map<PatternCandidate, Integer> result = new LinkedHashMap<>();
        patterns.forEach(pattern -> result.put(pattern, value));
        return result;
    }

    private static PatternCandidate pattern(
            int rollWidth,
            int firstCoefficient,
            int secondCoefficient) {
        Map<Integer, Integer> cuts = new LinkedHashMap<>();
        if (firstCoefficient > 0) {
            cuts.put(1000, firstCoefficient);
        }
        if (secondCoefficient > 0) {
            cuts.put(1200, secondCoefficient);
        }
        return new PatternCandidate(cuts, rollWidth);
    }
}
