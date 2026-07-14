package test.demo.apsmodule.generator.NewSolver.output;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemandPeakFastStageTest {

    @BeforeAll
    static void loadOrTools() {
        Loader.loadNativeLibraries();
    }

    @Test
    void consolidatesStage5BlocksUnderExactDemandCarsAndWaste() {
        List<CuttingInstruction> stage5 = List.of(
                instruction("A", "X"),
                instruction("A", "Y"),
                instruction("B", "X"),
                instruction("B", "Y"));
        List<SolverOrderItem> items = List.of(
                item(1000, "A", 2),
                item(1000, "B", 2),
                item(1200, "X", 2),
                item(1200, "Y", 2));

        DemandPeakFastStage.StageResult result = SolverRuntimeProperties.withOverrides(
                Map.of(
                        "cutting.demandPeak.enabled", "true",
                        "cutting.demandPeak.timeMs", "5000",
                        "cutting.demandPeak.peakCount", "1"),
                () -> new DemandPeakFastStage(SolverParameters.createDefault())
                        .improve(stage5, items));

        assertTrue(result.executed());
        assertTrue(result.improved());
        assertEquals("accepted", result.reason());
        assertEquals(4, result.beforeStats().groups());
        assertEquals(2, result.afterStats().groups());
        assertEquals(4, result.instructions().stream()
                .mapToInt(CuttingInstruction::getUsageCount).sum());
        assertEquals(messageCounts(stage5), messageCounts(result.instructions()));
        assertEquals(waste(stage5), waste(result.instructions()));
    }

    @Test
    void leavesStage5UntouchedWhenDisabled() {
        List<CuttingInstruction> stage5 = List.of(instruction("A", "X"));

        DemandPeakFastStage.StageResult result = new DemandPeakFastStage(
                SolverParameters.createDefault()).improve(stage5, List.of(item(1000, "A", 1)));

        assertFalse(result.executed());
        assertFalse(result.improved());
        assertEquals("disabled", result.reason());
        assertEquals(stage5, result.instructions());
    }

    @Test
    void skipsAlreadyMinimalOneGroupResult() {
        List<CuttingInstruction> stage5 = List.of(instruction("A", "X"));
        List<SolverOrderItem> items = List.of(item(1000, "A", 1), item(1200, "X", 1));

        DemandPeakFastStage.StageResult result = SolverRuntimeProperties.withOverrides(
                Map.of("cutting.demandPeak.enabled", "true"),
                () -> new DemandPeakFastStage(SolverParameters.createDefault())
                        .improve(stage5, items));

        assertTrue(result.executed());
        assertFalse(result.improved());
        assertEquals("group-lower-bound", result.reason());
        assertEquals(stage5, result.instructions());
    }

    private static CuttingInstruction instruction(String message1000, String message1200) {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setGroupKey("group-a");
        instruction.setRollWidth(2200);
        instruction.setLength(1350);
        instruction.setSurfaceTreatment("plain");
        instruction.setThickness(50);
        instruction.setSubRolls(new LinkedHashMap<>(Map.of(1000, 1, 1200, 1)));
        instruction.setUsageCount(1);
        instruction.setPatternWidth(2200);
        instruction.setWaste(1380);
        instruction.setStationAssignments(List.of(
                new StationAssignment(1000, message1000),
                new StationAssignment(1200, message1200)));
        return instruction;
    }

    private static SolverOrderItem item(int width, String message, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setWidth(width);
        item.setMessageText(message);
        item.setDemand(demand);
        item.setLength(1350);
        item.setSurfaceTreatment("plain");
        item.setGroupKey("group-a");
        return item;
    }

    private static Map<String, Integer> messageCounts(List<CuttingInstruction> instructions) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (CuttingInstruction instruction : instructions) {
            for (StationAssignment assignment : instruction.getStationAssignments()) {
                result.merge(assignment.getWidth() + "|" + assignment.getMessageText(), 1, Integer::sum);
            }
        }
        return result;
    }

    private static int waste(List<CuttingInstruction> instructions) {
        return instructions.stream()
                .mapToInt(instruction -> instruction.getWaste() * instruction.getUsageCount())
                .sum();
    }
}
