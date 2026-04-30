package test.demo.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import test.demo.apsmodule.recut.ReCutCommand;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.CuttingStatistics;
import test.demo.apsmodule.service.ExcelProcessingService;
import test.demo.apsmodule.service.PreviewMetadataService;
import test.demo.apsmodule.service.SequenceGroupModificationService;
import test.demo.apsmodule.service.excel.ExcelExportService;
import test.demo.rest.context.OptimizationContext;
import test.demo.rest.dto.ValidationReportRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cutting")
@CrossOrigin(origins = "*")
public class CuttingExportController {

    private static final Logger log = LoggerFactory.getLogger(CuttingExportController.class);

    private final OptimizationContext optimizationContext;
    private final ExcelExportService excelExportService;
    private final ExcelProcessingService excelProcessingService;
    private final SequenceGroupModificationService modificationService;
    private final PreviewMetadataService previewMetadataService;

    public CuttingExportController(
            OptimizationContext optimizationContext,
            ExcelExportService excelExportService,
            ExcelProcessingService excelProcessingService,
            SequenceGroupModificationService modificationService,
            PreviewMetadataService previewMetadataService) {
        this.optimizationContext = optimizationContext;
        this.excelExportService = excelExportService;
        this.excelProcessingService = excelProcessingService;
        this.modificationService = modificationService;
        this.previewMetadataService = previewMetadataService;
    }

    @PostMapping("/v2/preview")
    public ResponseEntity<Map<String, Object>> previewExport(
            @RequestBody(required = false) Map<String, Object> request) {
        try {
            String groupKey = request != null ? (String) request.get("groupKey") : null;
            if (!optimizationContext.hasResult()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "请先执行优化（调用 /api/cutting/v2/optimize）"));
            }

            CuttingOptimizationResult result = optimizationContext.getLastOptimizationResult();
            String planId = optimizationContext.getCurrentPlanId();
            String revisionId = optimizationContext.getCurrentRevisionId();
            List<Map<String, Object>> previewGroups = previewMetadataService.enrich(
                    excelExportService.buildPreviewGroups(result),
                    planId,
                    revisionId);

            if (groupKey != null) {
                List<Map<String, Object>> filteredGroups = new ArrayList<>();
                for (Map<String, Object> group : previewGroups) {
                    Object lengthObj = group.get("length");
                    Object treatmentObj = group.get("surfaceTreatment");
                    if (lengthObj == null || treatmentObj == null) {
                        continue;
                    }

                    String currentGroupKey = lengthObj + "m+" + treatmentObj;
                    if (currentGroupKey.equals(groupKey)) {
                        filteredGroups.add(group);
                    }
                }
                previewGroups = filteredGroups;
            }

            String filterGroupKey = groupKey;
            List<CuttingOptimizationResult.CuttingInstruction> filteredInstructions = result.getCuttingInstructions()
                    .stream()
                    .filter(instruction -> {
                        if (filterGroupKey == null) {
                            return true;
                        }
                        String instructionGroupKey = instruction.getLength() + "m+" + instruction.getSurfaceTreatment();
                        return instructionGroupKey.equals(filterGroupKey);
                    })
                    .toList();

            CuttingStatistics.Summary summary = CuttingStatistics.summarizeLegacyInstructions(
                    filteredInstructions, result.getTotalWidth());
            long singleUsageGroups = previewGroups.stream()
                    .filter(group -> {
                        Object usageCount = group.get("usageCount");
                        return usageCount instanceof Number number && number.intValue() == 1;
                    })
                    .count();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "预览数据生成成功");
            response.put("preview", previewGroups);
            response.put("totalGroups", previewGroups.size());
            response.put("totalRollsUsed", summary.totalRollsUsed());
            response.put("patternCount", filteredInstructions.size());
            response.put("totalWaste", summary.totalWaste());
            response.put("utilizationRate", Math.round(summary.utilizationRate() * 100.0) / 100.0);
            response.put("singleUsageGroups", singleUsageGroups);
            response.put("groupKey", groupKey);
            response.put("jobId", optimizationContext.getLastJobId());
            response.put("planId", planId);
            response.put("revisionId", revisionId);
            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            log.error("预览接口失败", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", exception.getMessage()));
        }
    }

    @PostMapping("/v2/sequence-groups/delete-row")
    public ResponseEntity<Map<String, Object>> deleteRow(@RequestBody Map<String, Object> request) {
        try {
            String planId = (String) request.get("planId");
            String baseRevisionId = (String) request.get("baseRevisionId");
            if (!matchesCurrentPlanRevision(planId, baseRevisionId)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "方案版本已变化，请刷新搭切页面后重试。"));
            }

            int sequenceNumber = ((Number) request.get("sequenceNumber")).intValue();
            String messageText = (String) request.get("messageText");
            int width = ((Number) request.get("width")).intValue();

            Map<String, Object> deleteResult = modificationService.deleteRow(sequenceNumber, messageText, width);
            if (!(boolean) deleteResult.get("success")) {
                return ResponseEntity.badRequest().body(deleteResult);
            }

            String revisionId = optimizationContext.advanceRevision();
            deleteResult.put("planId", optimizationContext.getCurrentPlanId());
            deleteResult.put("revisionId", revisionId);
            return ResponseEntity.ok(deleteResult);
        } catch (Exception exception) {
            log.error("删除行失败", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "删除失败: " + exception.getMessage()));
        }
    }

    @PostMapping("/v2/sequence-groups/undo")
    public ResponseEntity<Map<String, Object>> undo() {
        try {
            Map<String, Object> undoResult = modificationService.undo();
            if (!(boolean) undoResult.get("success")) {
                return ResponseEntity.badRequest().body(undoResult);
            }

            String revisionId = optimizationContext.advanceRevision();
            ResponseEntity<Map<String, Object>> previewResponse = previewExport(new HashMap<>());
            Map<String, Object> response = new HashMap<>(previewResponse.getBody());
            response.put("undoMessage", undoResult.get("message"));
            response.put("undoRemaining", undoResult.get("undoRemaining"));
            response.put("revisionId", revisionId);
            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            log.error("撤销失败", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", exception.getMessage()));
        }
    }

    @PostMapping("/v2/sequence-groups/re-cut")
    public ResponseEntity<Map<String, Object>> reCut(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<Integer> selectedSequenceNumbers = (List<Integer>) request.get("selectedSequenceNumbers");
            @SuppressWarnings("unchecked")
            List<String> selectedSequenceGroupIds = (List<String>) request.get("selectedSequenceGroupIds");

            ReCutCommand command = new ReCutCommand();
            command.setPlanId((String) request.get("planId"));
            command.setBaseRevisionId((String) request.get("baseRevisionId"));
            command.setSelectedSequenceNumbers(selectedSequenceNumbers);
            command.setSelectedSequenceGroupIds(selectedSequenceGroupIds);

            Map<String, Object> reCutResult = modificationService.reCut(command);
            if (!(boolean) reCutResult.get("success")) {
                return ResponseEntity.badRequest().body(reCutResult);
            }

            ResponseEntity<Map<String, Object>> previewResponse = previewExport(new HashMap<>());
            Map<String, Object> response = new HashMap<>(previewResponse.getBody());
            response.put("reCutMessage", reCutResult.get("message"));
            response.put("newGroupCount", reCutResult.get("newGroupCount"));
            response.put("undoCount", reCutResult.get("undoCount"));
            response.put("baseRevisionId", reCutResult.get("baseRevisionId"));
            response.put("revisionId", reCutResult.get("newRevisionId"));
            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            log.error("重新搭切失败", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "重新搭切失败: " + exception.getMessage()));
        }
    }

    @PostMapping("/v2/export")
    public ResponseEntity<Map<String, Object>> exportV2() {
        try {
            if (!optimizationContext.hasResult()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请先执行优化"));
            }

            CuttingOptimizationResult result = optimizationContext.getLastOptimizationResult();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "cutting_result_v2_" + timestamp + ".xlsx";
            String filePath = excelExportService.exportOptimizationResultV2(result, fileName);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Excel 文件导出成功");
            response.put("fileName", fileName);
            response.put("filePath", filePath);
            response.put("totalRollsUsed", result.getTotalRollsUsed());
            response.put("totalWaste", result.getTotalWaste());
            response.put("utilizationRate", result.getUtilizationRate());
            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            log.error("导出失败", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", exception.getMessage()));
        }
    }

    @PostMapping("/export-validation-report")
    public ResponseEntity<Map<String, Object>> exportValidationReport(@RequestBody ValidationReportRequest request) {
        try {
            if (request.getValidationResult() == null || request.getFileName() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "验证结果或文件名为空"));
            }

            String filePath = excelProcessingService.exportValidationReport(
                    request.getValidationResult(),
                    request.getFileName());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "验证报告导出成功");
            response.put("fileName", request.getFileName());
            response.put("filePath", filePath);
            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "message", "验证报告导出失败: " + exception.getMessage()));
        }
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable("fileName") String fileName) {
        try {
            String decodedFileName = java.net.URLDecoder.decode(fileName, "UTF-8");
            Path filePath = Paths.get("exports", decodedFileName);
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            byte[] fileContent = Files.readAllBytes(filePath);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", decodedFileName);
            headers.setContentLength(fileContent.length);
            return ResponseEntity.ok().headers(headers).body(fileContent);
        } catch (Exception exception) {
            log.error("下载失败", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean matchesCurrentPlanRevision(String planId, String revisionId) {
        if (planId != null && !planId.isBlank() && !planId.equals(optimizationContext.getCurrentPlanId())) {
            return false;
        }
        return revisionId == null
                || revisionId.isBlank()
                || revisionId.equals(optimizationContext.getCurrentRevisionId());
    }
}
