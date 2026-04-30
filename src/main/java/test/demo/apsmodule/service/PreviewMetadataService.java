package test.demo.apsmodule.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class PreviewMetadataService {

    public List<Map<String, Object>> enrich(
            List<Map<String, Object>> previewGroups,
            String planId,
            String revisionId) {
        for (Map<String, Object> group : previewGroups) {
            String sequenceGroupId = buildSequenceGroupId(group, planId, revisionId);
            group.put("planId", planId);
            group.put("revisionId", revisionId);
            group.put("sequenceGroupId", sequenceGroupId);
            group.put("isNewGroup", Boolean.TRUE.equals(group.get("isNewGroup")));

            Object rowsObject = group.get("rows");
            if (!(rowsObject instanceof List<?> rows)) {
                continue;
            }

            for (Object rowObject : rows) {
                if (!(rowObject instanceof Map<?, ?> rawRow)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) rawRow;
                row.put("sequenceGroupId", sequenceGroupId);
                row.put("rowId", buildRowId(sequenceGroupId, row));
            }
        }
        return previewGroups;
    }

    private String buildSequenceGroupId(Map<String, Object> group, String planId, String revisionId) {
        String rawValue = String.join("|",
                Objects.toString(planId, "plan-local"),
                Objects.toString(revisionId, "rev-local"),
                Objects.toString(group.get("groupKey"), ""),
                Objects.toString(group.get("instructionIndices"), "[]"),
                Objects.toString(group.get("rollWidth"), ""),
                Objects.toString(group.get("length"), ""),
                Objects.toString(group.get("usageCount"), ""),
                Objects.toString(group.get("comboExpanded"), "[]"),
                Objects.toString(group.get("rows"), "[]"));
        return "sg-" + UUID.nameUUIDFromBytes(rawValue.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    private String buildRowId(String sequenceGroupId, Map<String, Object> row) {
        String rawValue = String.join("|",
                sequenceGroupId,
                Objects.toString(row.get("salesperson"), ""),
                Objects.toString(row.get("messageText"), ""),
                Objects.toString(row.get("width"), ""),
                Objects.toString(row.get("length"), ""),
                Objects.toString(row.get("rolls"), ""),
                Objects.toString(row.get("stationCount"), ""));
        return "row-" + UUID.nameUUIDFromBytes(rawValue.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }
}
