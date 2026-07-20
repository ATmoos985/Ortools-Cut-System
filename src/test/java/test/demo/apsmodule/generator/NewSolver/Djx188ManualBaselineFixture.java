package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Djx188ManualBaselineFixture {

    private Djx188ManualBaselineFixture() {
    }

    static List<SolverOrderItem> loadItems() throws IOException {
        InputStream input = resource("/djx188.csv");
        List<SolverOrderItem> items = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                SolverOrderItem item = new SolverOrderItem();
                item.setMessageText(fields[0]);
                item.setWidth(Integer.parseInt(fields[1]));
                item.setDemand(Integer.parseInt(fields[2]));
                item.setLength(Integer.parseInt(fields[3]));
                item.setSurfaceTreatment(fields[4]);
                item.setGroupKey(fields[3] + "m+" + fields[4]);
                items.add(item);
            }
        }
        return items;
    }

    static List<ManualPattern> loadManualPatterns() throws IOException {
        InputStream input = resource("/djx188-manual-patterns.csv");
        List<ManualPattern> patterns = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                int patternWidth = Integer.parseInt(fields[2]);
                patterns.add(new ManualPattern(
                        fields[0],
                        Integer.parseInt(fields[1]),
                        patternWidth,
                        new PatternCandidate(parseCuts(fields[3]), ceilToStep(patternWidth, 10))));
            }
        }
        return patterns;
    }

    static Map<Integer, Integer> demands(List<SolverOrderItem> items) {
        Map<Integer, Integer> demands = new TreeMap<>();
        for (SolverOrderItem item : items) {
            demands.merge(item.getWidth(), item.getDemand(), Integer::sum);
        }
        return demands;
    }

    static Map<PatternCandidate, Integer> solution(List<ManualPattern> patterns) {
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        for (ManualPattern pattern : patterns) {
            solution.put(pattern.candidate(), pattern.usage());
        }
        return solution;
    }

    static SolverParameters parameters() {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(4300);
        params.setMaxRollWidth(4400);
        params.setStepSize(10);
        params.setTotalWidth(4600);
        params.setTotalOverCap(0);
        params.setMaxPatterns(800);
        params.setMaxDistinctWidths(4);
        params.setUseOptimizedAssignment(true);
        params.sanitize();
        return params;
    }

    private static Map<Integer, Integer> parseCuts(String encoded) {
        Map<Integer, Integer> cuts = new TreeMap<>();
        for (String part : encoded.split("\\|")) {
            String[] fields = part.split("x", -1);
            cuts.put(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]));
        }
        return cuts;
    }

    private static int ceilToStep(int value, int step) {
        return ((value + step - 1) / step) * step;
    }

    private static InputStream resource(String name) {
        InputStream input = Djx188ManualBaselineFixture.class.getResourceAsStream(name);
        if (input == null) {
            throw new IllegalStateException(name + " not found on test classpath");
        }
        return input;
    }

    record ManualPattern(
            String id,
            int usage,
            int expectedPatternWidth,
            PatternCandidate candidate) {
    }
}
