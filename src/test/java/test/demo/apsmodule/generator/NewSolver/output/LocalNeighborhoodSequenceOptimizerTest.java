package test.demo.apsmodule.generator.NewSolver.output;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalNeighborhoodSequenceOptimizerTest {

    @Test
    void improvesClosedNeighborhoodWithoutChangingCarsWasteOrDemand() {
        CuttingInstruction instruction = instruction(
                pattern(100, 200, 300),
                List.of(
                        roll("A", "X", "C"),
                        roll("A", "Y", "C"),
                        roll("B", "X", "C"),
                        roll("B", "Y", "C")));
        List<CuttingInstruction> before = List.of(instruction);

        LocalNeighborhoodSequenceOptimizer optimizer =
                new LocalNeighborhoodSequenceOptimizer(SolverParameters.createDefault());
        LocalNeighborhoodSequenceOptimizer.LnsResult result = optimizer.improve(before, List.of(
                item("A", 100, 2),
                item("B", 100, 2),
                item("X", 200, 2),
                item("Y", 200, 2),
                item("C", 300, 4)));

        assertTrue(result.improved());
        assertEquals(4, result.beforeGroups());
        assertEquals(2, result.afterGroups());
        assertEquals(4, totalCars(result.instructions()));
        assertEquals(totalWaste(before), totalWaste(result.instructions()));
        assertEquals(assignmentCounts(before), assignmentCounts(result.instructions()));
    }

    private Map<Integer, Integer> pattern(int... widths) {
        Map<Integer, Integer> pattern = new LinkedHashMap<>();
        for (int width : widths) {
            pattern.put(width, 1);
        }
        return pattern;
    }

    private CuttingInstruction instruction(Map<Integer, Integer> pattern, List<List<StationAssignment>> rolls) {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setGroupKey("1350m+N");
        instruction.setRollWidth(4600);
        instruction.setLength(1350);
        instruction.setSurfaceTreatment("N");
        instruction.setThickness(188);
        instruction.setSubRolls(new LinkedHashMap<>(pattern));
        instruction.setUsageCount(rolls.size());
        instruction.setPatternWidth(pattern.entrySet().stream()
                .mapToInt(entry -> entry.getKey() * entry.getValue())
                .sum());
        instruction.setWaste(4600 - instruction.getPatternWidth());

        List<StationAssignment> assignments = new ArrayList<>();
        for (List<StationAssignment> roll : rolls) {
            assignments.addAll(roll);
        }
        instruction.setStationAssignments(assignments);
        return instruction;
    }

    private List<StationAssignment> roll(String w100, String w200, String w300) {
        return List.of(
                new StationAssignment(100, w100),
                new StationAssignment(200, w200),
                new StationAssignment(300, w300));
    }

    private SolverOrderItem item(String message, int width, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setMessageText(message);
        item.setWidth(width);
        item.setDemand(demand);
        item.setLength(1350);
        item.setSurfaceTreatment("N");
        item.setGroupKey("1350m+N");
        return item;
    }

    private int totalCars(List<CuttingInstruction> instructions) {
        return instructions.stream().mapToInt(CuttingInstruction::getUsageCount).sum();
    }

    private int totalWaste(List<CuttingInstruction> instructions) {
        return instructions.stream()
                .mapToInt(instruction -> instruction.getWaste() * instruction.getUsageCount())
                .sum();
    }

    private Map<String, Integer> assignmentCounts(List<CuttingInstruction> instructions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CuttingInstruction instruction : instructions) {
            for (StationAssignment assignment : instruction.getStationAssignments()) {
                counts.merge(assignment.getWidth() + "|" + assignment.getMessageText(), 1, Integer::sum);
            }
        }
        return counts;
    }
}
