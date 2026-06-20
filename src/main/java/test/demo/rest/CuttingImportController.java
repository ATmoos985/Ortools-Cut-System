package test.demo.rest;

import test.demo.apsmodule.service.ExcelProcessingService;
import test.demo.apsmodule.service.excel.ExcelImportService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.*;

@RestController
@RequestMapping("/api/cutting")
@CrossOrigin(origins = "*")
/**
 * 模块：数据导入与验证控制器
 * <p>
 * 职责：
 * 1. 处理 Excel 文件上传与解析 (/api/cutting/parse-excel)
 * 2. 执行数据完整性与业务规则验证 (/api/cutting/validate-data)
 * 3. 提供数据分组诊断功能 (/api/cutting/diagnose-grouping)
 * 4. 提供系统配置信息 (/api/cutting/config)
 * <p>
 * 此控制器负责所有“进入系统前”的数据处理工作。
 */
public class CuttingImportController {

    private final ExcelProcessingService excelProcessingService;

    public CuttingImportController(ExcelProcessingService excelProcessingService) {
        this.excelProcessingService = excelProcessingService;
    }

    @PostMapping(value = "/parse-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> parseExcel(@RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = new HashMap<>();

            ExcelImportService.ParseResult parseResult = excelProcessingService.parseExcelFileWithSource(file);
            List<ExcelImportService.OrderItem> orderItems = parseResult.getOrderItems();

            result.put("success", true);
            result.put("message", "Excel文件解析成功");
            result.put("orderItems", orderItems);
            result.put("totalItems", orderItems.size());
            result.put("templateType", parseResult.getTemplateType());
            result.put("templateSource", parseResult.getTemplateSource());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "Excel文件解析失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResult);
        }
    }

    /**
     * 验证Excel文件数据
     */
    @PostMapping(value = "/validate-data", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> validateData(@RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = new HashMap<>();

            // 解析Excel文件并获取字段映射信息
            Map<String, Object> parseResult = excelProcessingService.parseExcelFileWithMapping(file);
            @SuppressWarnings("unchecked")
            List<ExcelImportService.OrderItem> orderItems = (List<ExcelImportService.OrderItem>) parseResult
                    .get("orderItems");
            @SuppressWarnings("unchecked")
            Map<String, Integer> actualColumnMapping = (Map<String, Integer>) parseResult.get("columnMapping");

            // 执行数据验证
            Map<String, Object> validationResult = performDataValidation(orderItems, actualColumnMapping);

            result.put("success", true);
            result.put("message", "数据验证完成");
            result.put("validationResult", validationResult);
            result.put("templateType", parseResult.get("templateType"));
            result.put("templateSource", parseResult.get("templateSource"));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "数据验证失败: " + e.getMessage()));
        }
    }

    /**
     * 数据诊断API - 检查订单项的分组情况
     */
    @PostMapping("/diagnose-grouping")
    public ResponseEntity<Map<String, Object>> diagnoseGrouping(
            @RequestBody List<ExcelImportService.OrderItem> orderItems) {
        try {
            Map<String, Object> diagnosis = new HashMap<>();

            // 按groupKey分组
            Map<String, List<ExcelImportService.OrderItem>> groupedItems = new HashMap<>();
            for (ExcelImportService.OrderItem item : orderItems) {
                String groupKey = item.getGroupKey() != null ? item.getGroupKey() : "default";
                groupedItems.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(item);
            }

            // 统计每个分组的详细信息
            List<Map<String, Object>> groupStats = new ArrayList<>();
            for (Map.Entry<String, List<ExcelImportService.OrderItem>> entry : groupedItems.entrySet()) {
                String groupKey = entry.getKey();
                List<ExcelImportService.OrderItem> items = entry.getValue();

                Map<String, Object> groupInfo = new HashMap<>();
                groupInfo.put("groupKey", groupKey);
                groupInfo.put("itemCount", items.size());

                // 计算需求总量（需要切割出的小卷数）
                int totalDemand = items.stream().mapToInt(ExcelImportService.OrderItem::getQuantity).sum();
                groupInfo.put("totalDemand", totalDemand);

                // 统计各宽度的需求量
                Map<Integer, Integer> widthStats = new HashMap<>();
                for (ExcelImportService.OrderItem item : items) {
                    widthStats.put(item.getWidth(),
                            widthStats.getOrDefault(item.getWidth(), 0) + item.getQuantity());
                }
                groupInfo.put("widthStats", widthStats);

                // 提取长度和表面处理信息
                if (!items.isEmpty()) {
                    ExcelImportService.OrderItem firstItem = items.get(0);
                    groupInfo.put("length", firstItem.getLength());
                    groupInfo.put("surfaceTreatment", firstItem.getSurfaceTreatment());
                }

                groupStats.add(groupInfo);
            }

            diagnosis.put("success", true);
            diagnosis.put("totalItems", orderItems.size());
            diagnosis.put("groupCount", groupedItems.size());
            diagnosis.put("groups", groupStats);

            return ResponseEntity.ok(diagnosis);

        } catch (Exception e) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "分组诊断失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResult);
        }
    }

    /**
     * 获取系统配置信息
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("supportedFormats", Arrays.asList("xlsx", "xls", "csv"));
        config.put("maxFileSize", "10MB");
        config.put("defaultRollWidth", 4400);
        config.put("defaultFlexibleRange", Map.of("min", 4300, "max", 4400, "step", 10));

        return ResponseEntity.ok(config);
    }

    // 辅助方法

    /**
     * 执行数据验证 - 重点验证字段映射和数据解析
     */
    private Map<String, Object> performDataValidation(List<ExcelImportService.OrderItem> orderItems,
            Map<String, Integer> actualColumnMapping) {
        Map<String, Object> validationResult = new HashMap<>();

        // 使用实际的字段映射信息
        Map<String, Object> fieldMapping = validateFieldMapping(actualColumnMapping);

        // 验证数据解析
        List<Map<String, Object>> parsingValidation = new ArrayList<>();
        List<Map<String, Object>> dataValidation = new ArrayList<>();

        int totalErrors = 0;
        int totalWarnings = 0;
        int totalValid = 0;
        int totalRecords = orderItems.size();

        // 解析验证 - 检查每个订单项的字段完整性
        for (int i = 0; i < orderItems.size(); i++) {
            ExcelImportService.OrderItem item = orderItems.get(i);
            int rowNumber = i + 2; // Excel行号从2开始（第1行是标题）

            // 检查必要字段
            if (item.getMessageText() == null || item.getMessageText().trim().isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("type", "error");
                error.put("message", String.format("第%d行：消息文本为空，无法唯一标识订单项", rowNumber));
                parsingValidation.add(error);
                totalErrors++;
            }

            if (item.getWidth() <= 0) {
                Map<String, Object> error = new HashMap<>();
                error.put("type", "error");
                error.put("message", String.format("第%d行：宽度值无效 (%d)，必须大于0", rowNumber, item.getWidth()));
                parsingValidation.add(error);
                totalErrors++;
            }

            if (item.getQuantity() <= 0) {
                Map<String, Object> error = new HashMap<>();
                error.put("type", "error");
                error.put("message", String.format("第%d行：卷数值无效 (%d)，必须大于0", rowNumber, item.getQuantity()));
                parsingValidation.add(error);
                totalErrors++;
            }

            if (item.getLength() <= 0) {
                Map<String, Object> error = new HashMap<>();
                error.put("type", "error");
                error.put("message", String.format("第%d行：长度值无效 (%d)，必须大于0", rowNumber, item.getLength()));
                parsingValidation.add(error);
                totalErrors++;
            }

            if (item.getSurfaceTreatment() == null || item.getSurfaceTreatment().trim().isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("type", "error");
                error.put("message", String.format("第%d行：表面处理为空，无法进行分组", rowNumber));
                parsingValidation.add(error);
                totalErrors++;
            }

            // 如果所有必要字段都有效，则记录为有效
            if (item.getMessageText() != null && !item.getMessageText().trim().isEmpty() &&
                    item.getWidth() > 0 && item.getQuantity() > 0 &&
                    item.getLength() > 0 && item.getSurfaceTreatment() != null &&
                    !item.getSurfaceTreatment().trim().isEmpty()) {
                totalValid++;
            }

            // 数据范围检查
            if (item.getWidth() > 0 && (item.getWidth() < 100 || item.getWidth() > 5000)) {
                Map<String, Object> warning = new HashMap<>();
                warning.put("type", "warning");
                warning.put("message", String.format("第%d行：宽度值 (%d) 超出常规范围 (100-5000mm)", rowNumber, item.getWidth()));
                parsingValidation.add(warning);
                totalWarnings++;
            }

            if (item.getQuantity() > 0 && item.getQuantity() > 1000) {
                Map<String, Object> warning = new HashMap<>();
                warning.put("type", "warning");
                warning.put("message", String.format("第%d行：卷数值 (%d) 较大，请确认是否正确", rowNumber, item.getQuantity()));
                parsingValidation.add(warning);
                totalWarnings++;
            }
        }

        // 数据一致性验证
        Map<String, Integer> groupKeyCounts = new HashMap<>();
        Map<Integer, Integer> widthCounts = new HashMap<>();

        for (ExcelImportService.OrderItem item : orderItems) {
            // 统计分组键
            if (item.getGroupKey() != null) {
                groupKeyCounts.put(item.getGroupKey(), groupKeyCounts.getOrDefault(item.getGroupKey(), 0) + 1);
            }

            // 统计宽度规格
            widthCounts.put(item.getWidth(), widthCounts.getOrDefault(item.getWidth(), 0) + item.getQuantity());
        }

        // 检查分组情况
        if (groupKeyCounts.size() > 1) {
            Map<String, Object> warning = new HashMap<>();
            warning.put("type", "warning");
            warning.put("message", String.format("检测到 %d 个不同的分组（长度+表面处理组合），可能影响切割优化", groupKeyCounts.size()));
            dataValidation.add(warning);
            totalWarnings++;
        }

        // 检查宽度分布
        if (widthCounts.size() > 20) {
            Map<String, Object> warning = new HashMap<>();
            warning.put("type", "warning");
            warning.put("message", String.format("宽度规格过多 (%d种)，可能影响切割效率", widthCounts.size()));
            dataValidation.add(warning);
            totalWarnings++;
        }

        // 总需求量检查
        int totalDemand = widthCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalDemand > 10000) {
            Map<String, Object> warning = new HashMap<>();
            warning.put("type", "warning");
            warning.put("message", String.format("总需求量 (%d) 较大，请确认订单规模", totalDemand));
            dataValidation.add(warning);
            totalWarnings++;
        }

        // 准备样本数据（前10条）
        List<Map<String, Object>> sampleData = orderItems.stream()
                .limit(10)
                .map(item -> {
                    Map<String, Object> sample = new HashMap<>();
                    sample.put("messageText", item.getMessageText());
                    sample.put("width", item.getWidth());
                    sample.put("quantity", item.getQuantity());
                    sample.put("length", item.getLength());
                    sample.put("surfaceTreatment", item.getSurfaceTreatment());
                    sample.put("description", item.getDescription());
                    sample.put("groupKey", item.getGroupKey());
                    return sample;
                })
                .collect(java.util.stream.Collectors.toList());

        validationResult.put("fieldMapping", fieldMapping);
        validationResult.put("parsingValidation", parsingValidation);
        validationResult.put("dataValidation", dataValidation);
        validationResult.put("sampleData", sampleData);
        validationResult.put("totalErrors", totalErrors);
        validationResult.put("totalWarnings", totalWarnings);
        validationResult.put("totalValid", totalValid);
        validationResult.put("totalRecords", totalRecords);

        return validationResult;
    }

    /**
     * 验证字段映射 - 使用实际的Excel列映射
     */
    private Map<String, Object> validateFieldMapping(Map<String, Integer> actualColumnMapping) {
        Map<String, Object> fieldMapping = new HashMap<>();

        // 定义期望的字段映射
        String[] expectedFields = {
                "消息文本", "宽度mm", "卷数", "长度m", "表面处理",
                "导入日期", "导入时间", "委托日期", "业务员", "客户编码",
                "客户名称", "物料编码", "物料描述", "厚度µm", "需求量",
                "单位", "电晕", "润湿张力", "机型", "涂布类型",
                "出货型号", "交货日期"
        };

        // 使用实际的列映射验证结果
        for (String fieldName : expectedFields) {
            Map<String, Object> fieldInfo = new HashMap<>();

            // 检查实际Excel中是否有这个字段
            if (actualColumnMapping.containsKey(fieldName)) {
                Integer columnIndex = actualColumnMapping.get(fieldName);
                fieldInfo.put("status", isCoreField(fieldName) ? "success" : "warning");
                fieldInfo.put("columnIndex", columnIndex);
                fieldInfo.put("columnName", fieldName);
            } else {
                fieldInfo.put("status", "error");
                fieldInfo.put("columnIndex", -1);
                fieldInfo.put("columnName", "未找到");
            }

            fieldMapping.put(fieldName, fieldInfo);
        }

        return fieldMapping;
    }

    private boolean isCoreField(String fieldName) {
        return fieldName.equals("消息文本") || fieldName.equals("宽度mm") ||
                fieldName.equals("卷数") || fieldName.equals("长度m") ||
                fieldName.equals("表面处理");
    }
}
