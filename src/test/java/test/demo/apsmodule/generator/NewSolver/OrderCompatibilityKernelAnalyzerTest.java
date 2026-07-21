package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderCompatibilityKernelAnalyzerTest {

    private static final OrderCompatibilityKernelAnalyzer.Options OPTIONS =
            OrderCompatibilityKernelAnalyzer.Options.regressionDefaults();

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void djx188AutomaticSetProvesSixStructuralExtraGroups() throws Exception {
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<PatternCandidate, Integer> solution = loadSolution(
                "/djx188-order-compatibility-auto22.csv");

        assertEquals(22, solution.size());
        assertEquals(169, totalCars(solution));
        assertEquals(36_870, totalWaste(solution, 4600));

        OrderCompatibilityKernelAnalyzer.Analysis analysis =
                OrderCompatibilityKernelAnalyzer.analyze(solution, items, OPTIONS);

        assertOptimal("DJX188-auto22", analysis, 28, 6, 1, 0);
        assertEquals(557L, analysis.configurationCount());
        assertEquals(6, analysis.splitWitnesses().stream()
                .mapToInt(split -> split.configurations().size() - 1)
                .sum());
        printBenchmark("DJX188-auto22", analysis);
        analysis.splitWitnesses().forEach(split -> System.out.printf(
                "  SPLIT pattern=%s usage=%d groups=%d varyingWidths=%s%n",
                split.patternSignature(),
                split.patternUsage(),
                split.configurations().size(),
                split.varyingWidths()));
    }

    @Test
    void djx188ManualSetNeedsNoCompatibilitySplits() throws Exception {
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<PatternCandidate, Integer> solution =
                Djx188ManualBaselineFixture.solution(
                        Djx188ManualBaselineFixture.loadManualPatterns());

        assertEquals(26, solution.size());
        assertEquals(169, totalCars(solution));
        assertEquals(36_870, totalWaste(solution, 4600));

        OrderCompatibilityKernelAnalyzer.Analysis analysis =
                OrderCompatibilityKernelAnalyzer.analyze(solution, items, OPTIONS);

        assertOptimal("DJX188-manual26", analysis, 26, 0, 1, 0);
        assertEquals(748L, analysis.configurationCount());
        assertTrue(analysis.splitWitnesses().isEmpty());
        printBenchmark("DJX188-manual26", analysis);
    }

    @Test
    void t42SetKeepsOneConfigurationPerPattern() throws Exception {
        List<SolverOrderItem> items = loadItems("/t42djx250.csv");
        Map<PatternCandidate, Integer> solution = loadSolution(
                "/t42-order-compatibility-patterns.csv");

        assertEquals(10, solution.size());
        assertEquals(45, totalCars(solution));

        OrderCompatibilityKernelAnalyzer.Analysis analysis =
                OrderCompatibilityKernelAnalyzer.analyze(solution, items, OPTIONS);

        assertOptimal("T42", analysis, 10, 0, 1, 0);
        assertTrue(analysis.splitWitnesses().isEmpty());
        printBenchmark("T42", analysis);
    }

    private static void assertOptimal(
            String name,
            OrderCompatibilityKernelAnalyzer.Analysis analysis,
            int expectedGroups,
            int expectedExtra,
            int expectedOdd,
            int expectedOne) {
        assertEquals(OrderCompatibilityKernelAnalyzer.Status.OPTIMAL,
                analysis.status(), name + " group status");
        assertEquals(MPSolver.ResultStatus.OPTIMAL,
                analysis.groupSolverStatus(), name + " solver status");
        assertTrue(analysis.groupOptimal(), name + " must prove the group optimum");
        assertTrue(analysis.shapeOptimal(), name + " must prove odd/one shape optimum");
        assertEquals(expectedGroups, analysis.feasibleGroups());
        assertEquals(expectedGroups, analysis.provenGroupLowerBound());
        assertEquals(expectedGroups, analysis.exactMinimumGroups());
        assertEquals(expectedExtra, analysis.exactExtraGroups());
        assertEquals(expectedOdd, analysis.oddGroups());
        assertEquals(expectedOne, analysis.oneGroups());
        assertEquals(0.0, analysis.relativeGap(), 1e-9);
    }

    private static void printBenchmark(
            String name,
            OrderCompatibilityKernelAnalyzer.Analysis analysis) {
        System.out.printf(
                "ORDER-COMPAT %-16s status=%s/%s/%s solver=%s "
                        + "patterns=%d configs=%d vars=%d constraints=%d "
                        + "groups=%d lower=%d extra=%d odd=%d one=%d "
                        + "nodes=%d enumMs=%d groupMs=%d shapeMs=%d totalMs=%d%n",
                name,
                analysis.groupSolverStatus(),
                analysis.oddSolverStatus(),
                analysis.oneSolverStatus(),
                analysis.solverName(),
                analysis.patternCount(),
                analysis.configurationCount(),
                analysis.variableCount(),
                analysis.constraintCount(),
                analysis.feasibleGroups(),
                analysis.provenGroupLowerBound(),
                analysis.exactExtraGroups(),
                analysis.oddGroups(),
                analysis.oneGroups(),
                analysis.nodes(),
                analysis.enumerationMs(),
                analysis.groupSolveMs(),
                analysis.shapeSolveMs(),
                analysis.totalElapsedMs());
    }

    private static List<SolverOrderItem> loadItems(String resource) throws IOException {
        InputStream input = resource(resource);
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

    private static Map<PatternCandidate, Integer> loadSolution(String resource)
            throws IOException {
        InputStream input = resource(resource);
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
                int usage = Integer.parseInt(fields[0]);
                int rollWidth = Integer.parseInt(fields[1]);
                PatternCandidate pattern = new PatternCandidate(
                        parseCuts(fields[2]), rollWidth);
                Integer previous = solution.put(pattern, usage);
                if (previous != null) {
                    throw new IllegalStateException(
                            "duplicate fixture pattern: " + pattern.signature());
                }
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

    private static int totalCars(Map<PatternCandidate, Integer> solution) {
        return solution.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int totalWaste(
            Map<PatternCandidate, Integer> solution,
            int totalWidth) {
        return solution.entrySet().stream()
                .mapToInt(entry -> entry.getKey().getRealWaste(totalWidth)
                        * entry.getValue())
                .sum();
    }

    private static InputStream resource(String path) {
        InputStream input = OrderCompatibilityKernelAnalyzerTest.class
                .getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException(path + " not found on test classpath");
        }
        return input;
    }
}
