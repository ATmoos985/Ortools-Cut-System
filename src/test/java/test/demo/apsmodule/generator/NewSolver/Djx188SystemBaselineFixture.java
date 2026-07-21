package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Reproducible copy of the 2026-07-20 production 26-pattern DJX188 plan. */
final class Djx188SystemBaselineFixture {

    private Djx188SystemBaselineFixture() {
    }

    static Map<PatternCandidate, Integer> loadSolution() throws IOException {
        InputStream input = Djx188SystemBaselineFixture.class
                .getResourceAsStream("/djx188-system-patterns.csv");
        if (input == null) {
            throw new IllegalStateException(
                    "/djx188-system-patterns.csv not found on test classpath");
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
                        parseCuts(fields[3]), Integer.parseInt(fields[2]));
                solution.put(pattern, Integer.parseInt(fields[1]));
            }
        }
        return solution;
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
