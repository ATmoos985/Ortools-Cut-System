package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssignmentMIPSolverTest {

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
}
