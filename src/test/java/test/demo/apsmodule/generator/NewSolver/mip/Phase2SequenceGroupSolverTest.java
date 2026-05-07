package test.demo.apsmodule.generator.NewSolver.mip;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase2SequenceGroupSolverTest {

    @BeforeAll
    static void loadOrTools() {
        Loader.loadNativeLibraries();
    }

    @Test
    void solveWithSolutionBuildsFullRollMessageColumns() {
        SolverParameters params = SolverParameters.createDefault();
        params.setTotalWidth(2200);
        params.setTimeoutMs(10_000);

        PatternCandidate pattern = new PatternCandidate(linkedPattern(1000, 1, 1200, 1), 2200);
        Map<PatternCandidate, Integer> incumbent = new LinkedHashMap<>();
        incumbent.put(pattern, 3);

        Phase2SequenceGroupSolver solver = new Phase2SequenceGroupSolver(params);
        Phase2SequenceGroupSolver.SolveResult result = solver.solveWithSolution(
                incumbent,
                List.of(
                        item(1000, "A", 2),
                        item(1000, "B", 1),
                        item(1200, "X", 3)));

        assertNotNull(result);
        assertEquals(Map.of(pattern, 3), result.solution());
        assertEquals(2, result.assignments().get(pattern).size());

        Map<String, Integer> produced = materialize(pattern, result.assignments().get(pattern));
        assertTrue(produced.getOrDefault("1000|A", 0) >= 2);
        assertTrue(produced.getOrDefault("1000|B", 0) >= 1);
        assertTrue(produced.getOrDefault("1200|X", 0) >= 3);
    }

    @Test
    void solveWithSolutionSupportsMixedMessagesOnDuplicateWidthStations() {
        SolverParameters params = SolverParameters.createDefault();
        params.setTotalWidth(2000);
        params.setTimeoutMs(10_000);

        PatternCandidate pattern = new PatternCandidate(Map.of(1000, 2), 2000);
        Map<PatternCandidate, Integer> incumbent = new LinkedHashMap<>();
        incumbent.put(pattern, 1);

        Phase2SequenceGroupSolver solver = new Phase2SequenceGroupSolver(params);
        Phase2SequenceGroupSolver.SolveResult result = solver.solveWithSolution(
                incumbent,
                List.of(
                        item(1000, "A", 1),
                        item(1000, "B", 1)));

        assertNotNull(result);
        assertEquals(Map.of(pattern, 1), result.solution());

        Map<String, Integer> produced = materialize(pattern, result.assignments().get(pattern));
        assertEquals(1, produced.get("1000|A"));
        assertEquals(1, produced.get("1000|B"));
        assertEquals(List.of("A", "B"),
                result.assignments().get(pattern).get(0).getStationConfig().get(1000).stream().sorted().toList());
    }

    @Test
    void solveWithSolutionLocallyCompressesEquivalentSeedBlocks() {
        SolverParameters params = SolverParameters.createDefault();
        params.setTotalWidth(2200);
        params.setTimeoutMs(10_000);

        PatternCandidate pattern = new PatternCandidate(linkedPattern(1000, 1, 1200, 1), 2200);
        Map<PatternCandidate, Integer> incumbent = new LinkedHashMap<>();
        incumbent.put(pattern, 4);

        Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> seedAssignments = new LinkedHashMap<>();
        seedAssignments.put(pattern, List.of(
                AssignmentMIPSolver.AssignmentBlock.fromStationConfig(stationConfig(1000, "A", 1200, "X"), 1),
                AssignmentMIPSolver.AssignmentBlock.fromStationConfig(stationConfig(1000, "B", 1200, "X"), 1),
                AssignmentMIPSolver.AssignmentBlock.fromStationConfig(stationConfig(1000, "A", 1200, "X"), 1),
                AssignmentMIPSolver.AssignmentBlock.fromStationConfig(stationConfig(1000, "B", 1200, "X"), 1)));

        Phase2SequenceGroupSolver solver = new Phase2SequenceGroupSolver(params);
        Phase2SequenceGroupSolver.SolveResult result = solver.solveWithSolution(
                incumbent,
                List.of(
                        item(1000, "A", 2),
                        item(1000, "B", 2),
                        item(1200, "X", 4)),
                seedAssignments);

        assertNotNull(result);
        assertEquals(Map.of(pattern, 4), result.solution());
        assertEquals(2, result.assignments().get(pattern).size());

        Map<String, Integer> produced = materialize(pattern, result.assignments().get(pattern));
        assertEquals(2, produced.get("1000|A"));
        assertEquals(2, produced.get("1000|B"));
        assertEquals(4, produced.get("1200|X"));
    }

    private static Map<Integer, Integer> linkedPattern(int widthA, int countA, int widthB, int countB) {
        Map<Integer, Integer> pattern = new LinkedHashMap<>();
        pattern.put(widthA, countA);
        pattern.put(widthB, countB);
        return pattern;
    }

    private static SolverOrderItem item(int width, String messageText, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setWidth(width);
        item.setMessageText(messageText);
        item.setDemand(demand);
        return item;
    }

    private static Map<Integer, List<String>> stationConfig(
            int widthA,
            String messageA,
            int widthB,
            String messageB) {
        Map<Integer, List<String>> config = new LinkedHashMap<>();
        config.put(widthA, List.of(messageA));
        config.put(widthB, List.of(messageB));
        return config;
    }

    private static Map<String, Integer> materialize(
            PatternCandidate pattern,
            List<AssignmentMIPSolver.AssignmentBlock> blocks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AssignmentMIPSolver.AssignmentBlock block : blocks) {
            for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                List<String> messages = block.getStationConfig().get(cut.getKey());
                for (int station = 0; station < cut.getValue(); station++) {
                    String message = station < messages.size() ? messages.get(station) : messages.get(messages.size() - 1);
                    counts.merge(cut.getKey() + "|" + message, block.getCount(), Integer::sum);
                }
            }
        }
        return counts;
    }
}
