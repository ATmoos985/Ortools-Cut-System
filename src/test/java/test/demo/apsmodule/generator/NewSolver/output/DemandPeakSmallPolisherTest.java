package test.demo.apsmodule.generator.NewSolver.output;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemandPeakSmallPolisherTest {

    private static final int WIDTH = 1000;

    @BeforeAll
    static void loadOrTools() {
        Loader.loadNativeLibraries();
    }

    @Test
    void addsResidualMessageColumnAndStrictlyImprovesProtectedSolution() {
        SolverParameters params = SolverParameters.createDefault();
        Column onlyA = column("A", "A");
        Column onlyB = column("B", "B");
        Column fixedC = column(900, "C", "C");
        List<CuttingInstruction> current = SetPartitionRefiner.toInstructions(
                List.of(
                        new ColumnUse(onlyA, 4),
                        new ColumnUse(onlyB, 4),
                        new ColumnUse(fixedC, 10)),
                template(), params);
        List<SolverOrderItem> items = List.of(
                item(WIDTH, "A", 8),
                item(WIDTH, "B", 8),
                item(900, "C", 20));

        DemandPeakSmallPolisher.PolishResult result = SolverRuntimeProperties.withOverrides(
                Map.of(
                        "cutting.demandPeak.smallPolish.enabled", "true",
                        "cutting.demandPeak.smallPolish.timeMs", "5000",
                        "cutting.demandPeak.smallPolish.maxDonors", "3"),
                () -> new DemandPeakSmallPolisher(params).polish(
                        current, items, List.of(onlyA, onlyB, fixedC)));

        assertTrue(result.executed());
        assertTrue(result.improved());
        assertEquals("accepted", result.reason());
        assertEquals(3, result.beforeStats().groups());
        assertEquals(2, result.afterStats().groups());
        assertEquals(0, result.afterStats().smallCarGroups());
        assertEquals(3, result.poolSize(), "fixed columns must stay outside the local MIP");
        assertTrue(result.residualColumns() > 0);
        assertEquals(18, result.instructions().stream()
                .mapToInt(CuttingInstruction::getUsageCount).sum());
        assertEquals(demand(current), demand(result.instructions()));
        assertEquals(waste(current), waste(result.instructions()));
    }

    @Test
    void zeroBudgetKeepsCurrentInstructionsUntouched() {
        SolverParameters params = SolverParameters.createDefault();
        Column onlyA = column("A", "A");
        Column onlyB = column("B", "B");
        List<CuttingInstruction> current = SetPartitionRefiner.toInstructions(
                List.of(new ColumnUse(onlyA, 4), new ColumnUse(onlyB, 4)),
                template(), params);

        DemandPeakSmallPolisher.PolishResult result = SolverRuntimeProperties.withOverrides(
                Map.of(
                        "cutting.demandPeak.smallPolish.enabled", "true",
                        "cutting.demandPeak.smallPolish.timeMs", "0"),
                () -> new DemandPeakSmallPolisher(params).polish(
                        current, List.of(item("A", 8), item("B", 8)),
                        List.of(onlyA, onlyB)));

        assertTrue(result.executed());
        assertFalse(result.improved());
        assertEquals("no-time-budget", result.reason());
        assertEquals(current, result.instructions());
    }

    private static Column column(String first, String second) {
        return column(WIDTH, first, second);
    }

    private static Column column(int width, String first, String second) {
        return Column.of(Map.of(width, 2), Map.of(width, List.of(first, second)));
    }

    private static CuttingInstruction template() {
        CuttingInstruction template = new CuttingInstruction();
        template.setGroupKey("group-a");
        template.setLength(1350);
        template.setSurfaceTreatment("plain");
        template.setThickness(50);
        return template;
    }

    private static SolverOrderItem item(String message, int demand) {
        return item(WIDTH, message, demand);
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

    private static Map<String, Integer> demand(List<CuttingInstruction> instructions) {
        Map<String, Integer> result = new java.util.TreeMap<>();
        instructions.forEach(instruction -> instruction.getStationAssignments().forEach(assignment ->
                result.merge(assignment.getWidth() + "|" + assignment.getMessageText(),
                        1, Integer::sum)));
        return result;
    }

    private static int waste(List<CuttingInstruction> instructions) {
        return instructions.stream()
                .mapToInt(instruction -> instruction.getWaste() * instruction.getUsageCount())
                .sum();
    }
}
