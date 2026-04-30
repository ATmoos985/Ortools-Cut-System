package test.demo.apsmodule.api;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.api.dto.ApsOptimizationModels;
import test.demo.rest.dto.OptimizationRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApsOptimizationRequestMapperTest {

    private final ApsOptimizationRequestMapper mapper = new ApsOptimizationRequestMapper();

    @Test
    void mapsApsRequestToInternalOptimizationRequest() {
        ApsOptimizationModels.CreateJobRequest request = new ApsOptimizationModels.CreateJobRequest(
                "REQ-1",
                "IDEMP-1",
                List.of(new ApsOptimizationModels.Order("SO-1", 1200, 8, 1000, "T1", "Alice", "Desc", 20)),
                new ApsOptimizationModels.OptimizationConfig(
                        true, 4600, 4550, 4300, 4500, 20, 15, 500, 90000L,
                        true, 5, 900, 6, 45000L, 2.0, 0.5, false, 1234.0));

        OptimizationRequest mapped = mapper.toOptimizationRequest(request);

        assertTrue(mapped.isFlexibleWidth());
        assertEquals(4550, mapped.getTotalWidth());
        assertEquals(4300, mapped.getMinWidth());
        assertEquals(4500, mapped.getMaxWidth());
        assertEquals(20, mapped.getStepSize());
        assertEquals(15, mapped.getTotalOverCap());
        assertEquals(500, mapped.getMaxIterations());
        assertEquals(90000L, mapped.getTimeoutMs());
        assertEquals(1, mapped.getOrderItems().size());
        assertEquals("SO-1", mapped.getOrderItems().get(0).getMessageText());
        assertEquals(1200, mapped.getOrderItems().get(0).getWidth());
        assertEquals(8, mapped.getOrderItems().get(0).getQuantity());
    }
}
