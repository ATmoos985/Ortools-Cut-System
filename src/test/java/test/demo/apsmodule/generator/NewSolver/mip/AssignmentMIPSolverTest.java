package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AssignmentMIPSolverTest {

    @BeforeAll
    static void loadOrTools() {
        com.google.ortools.Loader.loadNativeLibraries();
    }

    @Test
    void buildBlocksFromWidthAssignmentsPreservesPerWidthMessageCounts() {
        Map<Integer, List<Map.Entry<String, Integer>>> widthAssignments = new LinkedHashMap<>();
        widthAssignments.put(1000, List.of(
                Map.entry("A", 2),
                Map.entry("B", 1)));
        widthAssignments.put(1200, List.of(
                Map.entry("X", 1),
                Map.entry("Y", 2)));

        List<AssignmentMIPSolver.AssignmentBlock> blocks =
                AssignmentMIPSolver.buildBlocksFromWidthAssignments(widthAssignments, 3);

        assertEquals(2, blocks.size());
        assertEquals(Map.of(1000, "A", 1200, "Y"), blocks.get(0).getConfig());
        assertEquals(2, blocks.get(0).getCount());
        assertEquals(Map.of(1000, "B", 1200, "X"), blocks.get(1).getConfig());
        assertEquals(1, blocks.get(1).getCount());

        Map<String, Integer> materializedCounts = new HashMap<>();
        for (AssignmentMIPSolver.AssignmentBlock block : blocks) {
            for (Map.Entry<Integer, String> configEntry : block.getConfig().entrySet()) {
                String key = configEntry.getKey() + "|" + configEntry.getValue();
                materializedCounts.merge(key, block.getCount(), Integer::sum);
            }
        }

        assertEquals(2, materializedCounts.get("1000|A"));
        assertEquals(1, materializedCounts.get("1000|B"));
        assertEquals(1, materializedCounts.get("1200|X"));
        assertEquals(2, materializedCounts.get("1200|Y"));
    }

    @Test
    void buildBlocksFromWidthSlotAssignmentsPreservesDuplicateWidthMessages() {
        Map<Integer, List<Map.Entry<String, Integer>>> widthSlotAssignments = new LinkedHashMap<>();
        widthSlotAssignments.put(1000, List.of(
                Map.entry("A", 1),
                Map.entry("B", 1)));

        List<AssignmentMIPSolver.AssignmentBlock> blocks =
                AssignmentMIPSolver.buildBlocksFromWidthSlotAssignments(
                        widthSlotAssignments,
                        Map.of(1000, 2),
                        1);

        assertEquals(1, blocks.size());
        assertEquals(List.of("A", "B"), blocks.get(0).getStationConfig().get(1000));
        assertEquals(1, blocks.get(0).getCount());
    }

    @Test
    void solveIsIndependentOfPatternMapInsertionOrder() {
        PatternCandidate first = new PatternCandidate(Map.of(1000, 1), 1000);
        PatternCandidate second = new PatternCandidate(Map.of(1000, 1), 1100);
        Map<PatternCandidate, Integer> forward = new LinkedHashMap<>();
        forward.put(first, 2);
        forward.put(second, 2);
        Map<PatternCandidate, Integer> reversed = new LinkedHashMap<>();
        reversed.put(second, 2);
        reversed.put(first, 2);

        List<SolverOrderItem> items = List.of(
                item("A", 2),
                item("B", 2));
        SolverParameters parameters = new SolverParameters();
        parameters.setTimeoutMs(10_000L);
        AssignmentMIPSolver solver = new AssignmentMIPSolver(parameters);

        String previous = System.getProperty("cutting.stage5.stablePatternOrder");
        Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> firstResult;
        Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> secondResult;
        try {
            System.setProperty("cutting.stage5.stablePatternOrder", "true");
            firstResult = solver.solve(forward, items);
            secondResult = solver.solve(reversed, items);
        } finally {
            if (previous == null) {
                System.clearProperty("cutting.stage5.stablePatternOrder");
            } else {
                System.setProperty("cutting.stage5.stablePatternOrder", previous);
            }
        }

        assertNotNull(firstResult);
        assertNotNull(secondResult);
        assertEquals(resultSignature(firstResult), resultSignature(secondResult));
    }

    private static SolverOrderItem item(String message, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setMessageText(message);
        item.setWidth(1000);
        item.setDemand(demand);
        return item;
    }

    private static List<String> resultSignature(
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> result) {
        return result.entrySet().stream()
                .map(entry -> entry.getKey().signature() + "=" + entry.getValue().stream()
                        .map(block -> block.getStationConfig() + "#" + block.getCount())
                        .toList())
                .sorted()
                .toList();
    }
}
