package test.demo.apsmodule.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PreviewMetadataServiceTest {

    @Test
    void enrichAddsStablePlanAndRowIdentifiers() {
        PreviewMetadataService service = new PreviewMetadataService();

        Map<String, Object> row = new HashMap<>();
        row.put("salesperson", "Alice");
        row.put("messageText", "MSG-1");
        row.put("width", 1100);
        row.put("length", 1350);
        row.put("rolls", 2);
        row.put("stationCount", 2);

        Map<String, Object> group = new HashMap<>();
        group.put("groupKey", "1350m+PE");
        group.put("instructionIndices", List.of(0, 1));
        group.put("rollWidth", 3300);
        group.put("length", 1350);
        group.put("usageCount", 2);
        group.put("comboExpanded", List.of(1100, 1100, 1100));
        group.put("rows", new ArrayList<>(List.of(row)));

        List<Map<String, Object>> enriched = service.enrich(new ArrayList<>(List.of(group)), "plan-1", "rev-2");

        assertEquals("plan-1", enriched.get(0).get("planId"));
        assertEquals("rev-2", enriched.get(0).get("revisionId"));
        assertNotNull(enriched.get(0).get("sequenceGroupId"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) enriched.get(0).get("rows");
        assertEquals(enriched.get(0).get("sequenceGroupId"), rows.get(0).get("sequenceGroupId"));
        assertNotNull(rows.get(0).get("rowId"));
    }
}
