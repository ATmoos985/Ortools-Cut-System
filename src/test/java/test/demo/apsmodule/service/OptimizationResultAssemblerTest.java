package test.demo.apsmodule.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimizationResultAssemblerTest {

    private final OptimizationResultAssembler assembler = new OptimizationResultAssembler();

    @Test
    void buildsFrontendCompatibleResponse() {
        ProductionOrder orderItem = new ProductionOrder();
        orderItem.setMessageText("MSG-1");
        orderItem.setSalesperson("Alice");
        orderItem.setWidth(1200);
        orderItem.setQuantity(5);
        orderItem.setLength(1000);
        orderItem.setSurfaceTreatment("T1");

        CuttingOptimizationResult.StationAssignment assignment =
                new CuttingOptimizationResult.StationAssignment(0, 1200, orderItem);

        CuttingOptimizationResult.CuttingInstruction instruction =
                new CuttingOptimizationResult.CuttingInstruction();
        instruction.setGroupKey("1000m+T1");
        instruction.setRollWidth(4600);
        instruction.setLength(1000);
        instruction.setSurfaceTreatment("T1");
        instruction.setThickness(20);
        instruction.setSubRolls(Map.of(1200, 2));
        instruction.setUsageCount(3);
        instruction.setStationAssignments(List.of(assignment));

        CuttingOptimizationResult result = new CuttingOptimizationResult();
        result.setTotalRollsUsed(3);
        result.setTotalWaste(100);
        result.setUtilizationRate(97.5);
        result.setExecutionTimeMs(1234);
        result.setTotalWidth(4600);
        result.setCuttingInstructions(List.of(instruction));

        Map<String, Object> response = assembler.toOptimizationResponseV2(result, List.of(orderItem));

        assertEquals(3, response.get("totalRollsUsed"));
        assertEquals(3, response.get("totalRolls"));
        assertEquals(100, response.get("totalWaste"));
        assertEquals(97.5, response.get("utilizationRate"));
        assertEquals(1, response.get("patternCount"));
        assertEquals(1, response.get("totalInstructions"));
        assertEquals("OR-Tools 列生成", response.get("algorithmType"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> patterns = (List<Map<String, Object>>) response.get("patterns");
        assertEquals(1, patterns.size());
        assertEquals("1000m+T1", patterns.get(0).get("groupKey"));
        assertEquals(4600, patterns.get(0).get("rollWidth"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignments = (List<Map<String, Object>>) patterns.get(0).get("stationAssignments");
        assertEquals(1, assignments.size());
        assertEquals("MSG-1", assignments.get(0).get("messageText"));
        assertEquals("Alice", assignments.get(0).get("salesperson"));

        @SuppressWarnings("unchecked")
        Map<String, Object> demandAnalysis = (Map<String, Object>) response.get("demandAnalysis");
        assertNotNull(demandAnalysis);
        assertTrue((Boolean) demandAnalysis.get("allDemandsMet"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fulfillmentDetails =
                (List<Map<String, Object>>) demandAnalysis.get("fulfillmentDetails");
        assertEquals(1, fulfillmentDetails.size());
        assertEquals(1200, fulfillmentDetails.get(0).get("width"));
        assertEquals(5, fulfillmentDetails.get(0).get("demand"));
        assertEquals(6, fulfillmentDetails.get(0).get("actual"));
        assertEquals(1, fulfillmentDetails.get(0).get("difference"));
    }
}
