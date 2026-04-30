package test.demo.apsmodule.service;

import org.springframework.stereotype.Component;
import test.demo.apsmodule.service.excel.ExcelImportService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OptimizationResultAssembler {

    public Map<String, Object> toOptimizationResponseV2(
            CuttingOptimizationResult result,
            List<?> orderItems) {
        Map<String, Object> response = new HashMap<>();

        response.put("totalRollsUsed", result.getTotalRollsUsed());
        response.put("totalRolls", result.getTotalRollsUsed());
        response.put("totalWaste", result.getTotalWaste());
        response.put("utilizationRate", result.getUtilizationRate());
        response.put("efficiency", result.getUtilizationRate());
        response.put("executionTimeMs", result.getExecutionTimeMs());
        response.put("totalWidth", result.getTotalWidth());
        response.put("algorithmType", "OR-Tools 列生成");
        response.put("iterations", 0);

        List<CuttingOptimizationResult.CuttingInstruction> instructions = result.getCuttingInstructions();
        response.put("patternCount", instructions != null ? instructions.size() : 0);

        if (instructions != null) {
            response.put("totalInstructions", instructions.size());
            response.put("patterns", buildPatterns(instructions));
        }

        if (instructions != null && !instructions.isEmpty()) {
            response.put("demandAnalysis", buildDemandAnalysis(instructions, orderItems));
        }

        return response;
    }

    private List<Map<String, Object>> buildPatterns(List<CuttingOptimizationResult.CuttingInstruction> instructions) {
        List<Map<String, Object>> patterns = new ArrayList<>();
        for (CuttingOptimizationResult.CuttingInstruction instruction : instructions) {
            Map<String, Object> pattern = new HashMap<>();
            pattern.put("groupKey", instruction.getGroupKey());
            pattern.put("rollWidth", instruction.getRollWidth());
            pattern.put("length", instruction.getLength());
            pattern.put("surfaceTreatment", instruction.getSurfaceTreatment());
            pattern.put("thickness", instruction.getThickness());
            pattern.put("subRolls", instruction.getSubRolls());
            pattern.put("usageCount", instruction.getUsageCount());
            pattern.put("stationAssignments", buildAssignments(instruction.getStationAssignments()));
            patterns.add(pattern);
        }
        return patterns;
    }

    private List<Map<String, Object>> buildAssignments(List<CuttingOptimizationResult.StationAssignment> assignments) {
        List<Map<String, Object>> mappedAssignments = new ArrayList<>();
        if (assignments == null) {
            return mappedAssignments;
        }

        for (CuttingOptimizationResult.StationAssignment assignment : assignments) {
            Map<String, Object> assignmentMap = new HashMap<>();
            assignmentMap.put("width", assignment.getWidth());
            assignmentMap.put("messageText", assignment.getMessageText());
            if (assignment.getOrderItem() != null) {
                assignmentMap.put("salesperson", assignment.getOrderItem().getSalesperson());
            }
            mappedAssignments.add(assignmentMap);
        }

        return mappedAssignments;
    }

    private Map<String, Object> buildDemandAnalysis(
            List<CuttingOptimizationResult.CuttingInstruction> instructions,
            List<?> orderItems) {
        Map<String, Object> demandAnalysis = new HashMap<>();
        List<Map<String, Object>> fulfillmentDetails = new ArrayList<>();

        Map<Integer, Integer> widthToDemand = new HashMap<>();
        if (orderItems != null) {
            for (Object item : orderItems) {
                widthToDemand.merge(readWidth(item), readQuantity(item), Integer::sum);
            }
        }

        Map<Integer, Integer> widthToActual = new HashMap<>();
        for (CuttingOptimizationResult.CuttingInstruction instruction : instructions) {
            if (instruction.getSubRolls() == null) {
                continue;
            }
            int usageCount = instruction.getUsageCount() > 0 ? instruction.getUsageCount() : 1;
            for (Map.Entry<Integer, Integer> entry : instruction.getSubRolls().entrySet()) {
                widthToActual.merge(entry.getKey(), entry.getValue() * usageCount, Integer::sum);
            }
        }

        for (Map.Entry<Integer, Integer> entry : widthToActual.entrySet()) {
            int width = entry.getKey();
            int actual = entry.getValue();
            int demand = widthToDemand.getOrDefault(width, 0);

            Map<String, Object> detail = new HashMap<>();
            detail.put("width", width);
            detail.put("demand", demand);
            detail.put("actual", actual);
            detail.put("difference", actual - demand);
            fulfillmentDetails.add(detail);
        }

        fulfillmentDetails.sort((a, b) -> Integer.compare((Integer) a.get("width"), (Integer) b.get("width")));
        demandAnalysis.put("fulfillmentDetails", fulfillmentDetails);
        demandAnalysis.put("allDemandsMet", fulfillmentDetails.stream()
                .allMatch(detail -> ((Integer) detail.get("difference")) >= 0));

        return demandAnalysis;
    }

    private int readWidth(Object item) {
        if (item instanceof ProductionOrder productionOrder) {
            return productionOrder.getWidth();
        }
        if (item instanceof ExcelImportService.OrderItem orderItem) {
            return orderItem.getWidth();
        }
        throw new IllegalArgumentException("Unsupported order item type: " + item);
    }

    private int readQuantity(Object item) {
        if (item instanceof ProductionOrder productionOrder) {
            return productionOrder.getQuantity();
        }
        if (item instanceof ExcelImportService.OrderItem orderItem) {
            return orderItem.getQuantity();
        }
        throw new IllegalArgumentException("Unsupported order item type: " + item);
    }
}
