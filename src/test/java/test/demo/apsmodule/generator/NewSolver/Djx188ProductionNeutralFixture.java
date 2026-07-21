package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Fixed automatic22 start state for the production-neutral search. */
final class Djx188ProductionNeutralFixture {

    private Djx188ProductionNeutralFixture() {
    }

    static Map<PatternCandidate, Integer> loadAutomatic22() throws IOException {
        InputStream input = Djx188ProductionNeutralFixture.class
                .getResourceAsStream("/djx188-order-compatibility-auto22.csv");
        if (input == null) {
            throw new IllegalStateException(
                    "/djx188-order-compatibility-auto22.csv not found");
        }
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                PatternCandidate pattern = new PatternCandidate(
                        parseCuts(fields[2]), Integer.parseInt(fields[1]));
                Integer previous = solution.put(
                        pattern, Integer.parseInt(fields[0]));
                if (previous != null) {
                    throw new IllegalStateException(
                            "duplicate automatic22 pattern: " + pattern.signature());
                }
            }
        }
        return solution;
    }

    static Map<PatternCandidate, Integer> upperBounds(
            List<PatternCandidate> universe,
            Map<Integer, Integer> demands,
            int exactCars) {
        Map<PatternCandidate, Integer> bounds = new LinkedHashMap<>();
        for (PatternCandidate pattern : universe) {
            int upper = exactCars;
            for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                Integer demand = demands.get(cut.getKey());
                if (demand == null || demand <= 0) {
                    upper = 0;
                    break;
                }
                upper = Math.min(upper, demand / cut.getValue());
            }
            bounds.put(pattern, upper);
        }
        return bounds;
    }

    static int totalCars(Map<PatternCandidate, Integer> solution) {
        return solution.values().stream().mapToInt(Integer::intValue).sum();
    }

    static int totalWaste(
            Map<PatternCandidate, Integer> solution,
            int totalWidth) {
        return solution.entrySet().stream()
                .mapToInt(entry -> entry.getKey().getRealWaste(totalWidth)
                        * entry.getValue())
                .sum();
    }

    static Map<Integer, Integer> production(
            Map<PatternCandidate, Integer> solution) {
        Map<Integer, Integer> production = new TreeMap<>();
        solution.forEach((pattern, usage) -> pattern.getPattern().forEach(
                (width, coefficient) -> production.merge(
                        width, coefficient * usage, Integer::sum)));
        return production;
    }

    static int oddUsages(Map<PatternCandidate, Integer> solution) {
        return (int) solution.values().stream()
                .filter(usage -> usage % 2 != 0)
                .count();
    }

    static int oneUsages(Map<PatternCandidate, Integer> solution) {
        return (int) solution.values().stream()
                .filter(usage -> usage == 1)
                .count();
    }

    private static Map<Integer, Integer> parseCuts(String encoded) {
        Map<Integer, Integer> cuts = new TreeMap<>();
        for (String part : encoded.split("\\|")) {
            String[] fields = part.split("x", -1);
            cuts.put(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]));
        }
        return cuts;
    }
}
