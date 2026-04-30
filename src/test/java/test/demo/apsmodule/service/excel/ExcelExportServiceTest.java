package test.demo.apsmodule.service.excel;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.ProductionOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelExportServiceTest {

    @Test
    void replaceTemplateWidthConstantsUsesRequestedWidth() {
        assertEquals(
                "=(3550-R6-S6-T6-U6-V6-W6)/2",
                ExcelExportService.replaceTemplateWidthConstants("=(4600-R6-S6-T6-U6-V6-W6)/2", 3550));
        assertEquals(
                "=AB6/3550",
                ExcelExportService.replaceTemplateWidthConstants("=AB6/4600", 3550));
    }

    @Test
    void buildPreviewGroupsMergesAdjacentEquivalentGroupsAcrossInstructionBoundaries() {
        ExcelExportService service = new ExcelExportService();
        CuttingOptimizationResult result = new CuttingOptimizationResult();
        result.setCuttingInstructions(List.of(
                instruction("group-a", 3300, Map.of(1100, 3), 1, "78", "alice"),
                instruction("group-a", 3300, Map.of(1100, 3), 1, "78", "alice")));

        List<Map<String, Object>> previewGroups = service.buildPreviewGroups(result);
        Map<String, Object> mergedGroup = previewGroups.get(0);
        Map<String, Object> firstRow = firstRow(mergedGroup);

        assertEquals(1, previewGroups.size());
        assertEquals(2, ((Number) mergedGroup.get("usageCount")).intValue());
        assertEquals(List.of(0, 1), mergedGroup.get("instructionIndices"));
        assertEquals(6, ((Number) firstRow.get("rolls")).intValue());
        assertEquals(3, ((Number) firstRow.get("stationCount")).intValue());
    }

    @Test
    void buildPreviewGroupsCalculatesStationCountFromRollsAndUsageCount() {
        ExcelExportService service = new ExcelExportService();
        CuttingOptimizationResult result = new CuttingOptimizationResult();
        result.setCuttingInstructions(List.of(
                instruction("group-a", 3300, Map.of(1100, 2), 2, "78", "alice")));

        List<Map<String, Object>> previewGroups = service.buildPreviewGroups(result);
        Map<String, Object> row = firstRow(previewGroups.get(0));

        assertEquals(4, ((Number) row.get("rolls")).intValue());
        assertEquals(2, ((Number) row.get("stationCount")).intValue());
    }

    private static CuttingOptimizationResult.CuttingInstruction instruction(
            String groupKey,
            int rollWidth,
            Map<Integer, Integer> subRolls,
            int usageCount,
            String messageText,
            String salesperson) {
        CuttingOptimizationResult.CuttingInstruction instruction = new CuttingOptimizationResult.CuttingInstruction();
        instruction.setGroupKey(groupKey);
        instruction.setRollWidth(rollWidth);
        instruction.setSubRolls(new LinkedHashMap<>(subRolls));
        instruction.setUsageCount(usageCount);
        instruction.setLength(1350);
        instruction.setSurfaceTreatment("plain");
        instruction.setThickness(50);

        List<CuttingOptimizationResult.StationAssignment> assignments = new ArrayList<>();
        for (int i = 0; i < usageCount; i++) {
            for (Map.Entry<Integer, Integer> entry : subRolls.entrySet()) {
                for (int j = 0; j < entry.getValue(); j++) {
                    assignments.add(assignment(entry.getKey(), messageText, salesperson, groupKey));
                }
            }
        }
        instruction.setStationAssignments(assignments);
        return instruction;
    }

    private static Map<String, Object> firstRow(Map<String, Object> group) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) group.get("rows");
        return rows.get(0);
    }

    private static CuttingOptimizationResult.StationAssignment assignment(
            int width,
            String messageText,
            String salesperson,
            String groupKey) {
        ProductionOrder orderItem = new ProductionOrder();
        orderItem.setMessageText(messageText);
        orderItem.setSalesperson(salesperson);
        orderItem.setWidth(width);
        orderItem.setLength(1350);
        orderItem.setSurfaceTreatment("plain");
        orderItem.setGroupKey(groupKey);

        CuttingOptimizationResult.StationAssignment assignment = new CuttingOptimizationResult.StationAssignment();
        assignment.setWidth(width);
        assignment.setOrderItem(orderItem);
        assignment.setMessageText(messageText);
        return assignment;
    }
}
