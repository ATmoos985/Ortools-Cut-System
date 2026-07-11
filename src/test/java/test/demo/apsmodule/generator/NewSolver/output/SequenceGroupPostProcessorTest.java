package test.demo.apsmodule.generator.NewSolver.output;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.StationAssignment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SequenceGroupPostProcessorTest {

    @Test
    void countTotalGroupsUsesFinalRollContent() {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setUsageCount(3);

        Map<Integer, Integer> subRolls = new LinkedHashMap<>();
        subRolls.put(1000, 1);
        subRolls.put(1200, 1);
        instruction.setSubRolls(subRolls);

        instruction.setStationAssignments(List.of(
                new StationAssignment(1000, "A"),
                new StationAssignment(1000, "A"),
                new StationAssignment(1000, "C"),
                new StationAssignment(1200, "B"),
                new StationAssignment(1200, "B"),
                new StationAssignment(1200, "B")));

        assertEquals(2, SequenceGroupPostProcessor.countTotalGroups(List.of(instruction)));
        assertEquals(2, SequenceGroupPostProcessor.countGroups(instruction));
    }

    @Test
    void countTotalGroupsMergesAcrossInstructionBoundaries() {
        CuttingInstruction left = new CuttingInstruction();
        left.setUsageCount(1);
        left.setSubRolls(linkedSubRolls());
        left.setStationAssignments(List.of(
                new StationAssignment(1000, "A"),
                new StationAssignment(1200, "B")));

        CuttingInstruction right = new CuttingInstruction();
        right.setUsageCount(1);
        right.setSubRolls(linkedSubRolls());
        right.setStationAssignments(List.of(
                new StationAssignment(1000, "A"),
                new StationAssignment(1200, "B")));

        assertEquals(1, SequenceGroupPostProcessor.countTotalGroups(List.of(left, right)));
    }

    @Test
    void computeGroupStatsCountsSingleCarGroupsSeparately() {
        CuttingInstruction single = instruction(1, "A");
        CuttingInstruction triple = instruction(3, "B");
        CuttingInstruction even = instruction(2, "C");

        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(List.of(single, triple, even));

        assertEquals(3, stats.groups());
        assertEquals(2, stats.oddCarGroups());
        assertEquals(1, stats.oneCarGroups());
        assertEquals(3, stats.smallCarGroups());
    }

    private static CuttingInstruction instruction(int cars, String message) {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setUsageCount(cars);
        instruction.setSubRolls(Map.of(1000, 1));
        java.util.ArrayList<StationAssignment> assignments = new java.util.ArrayList<>();
        for (int i = 0; i < cars; i++) {
            assignments.add(new StationAssignment(1000, message));
        }
        instruction.setStationAssignments(assignments);
        return instruction;
    }

    private static Map<Integer, Integer> linkedSubRolls() {
        Map<Integer, Integer> subRolls = new LinkedHashMap<>();
        subRolls.put(1000, 1);
        subRolls.put(1200, 1);
        return subRolls;
    }
}
