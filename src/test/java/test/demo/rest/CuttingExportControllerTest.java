package test.demo.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CuttingExportControllerTest {

    @Test
    void summarizesOddAndSingleUsageGroupsFromVisiblePreviewGroups() {
        List<Map<String, Object>> previewGroups = List.of(
                Map.of("usageCount", 1),
                Map.of("usageCount", 2),
                Map.of("usageCount", 3),
                Map.of("usageCount", "invalid"));

        CuttingExportController.UsageGroupSummary summary =
                CuttingExportController.summarizeUsageGroups(previewGroups);

        assertEquals(2, summary.oddUsageGroups());
        assertEquals(1, summary.singleUsageGroups());
    }
}
