package test.demo.apsmodule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import test.demo.apsmodule.recut.ReCutApplicationService;
import test.demo.apsmodule.recut.ReCutCommand;
import test.demo.apsmodule.service.excel.ExcelExportService;
import test.demo.apsmodule.service.excel.ExcelImportService;
import test.demo.rest.context.OptimizationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SequenceGroupModificationService {

    private static final Logger log = LoggerFactory.getLogger(SequenceGroupModificationService.class);

    private final OptimizationContext optimizationContext;
    private final ExcelExportService excelExportService;
    private final UndoService undoService;
    private final PendingPoolService pendingPoolService;
    private final TempGroupService tempGroupService;
    private final ReCutApplicationService reCutApplicationService;

    public SequenceGroupModificationService(
            OptimizationContext optimizationContext,
            ExcelExportService excelExportService,
            UndoService undoService,
            PendingPoolService pendingPoolService,
            TempGroupService tempGroupService,
            ReCutApplicationService reCutApplicationService) {
        this.optimizationContext = optimizationContext;
        this.excelExportService = excelExportService;
        this.undoService = undoService;
        this.pendingPoolService = pendingPoolService;
        this.tempGroupService = tempGroupService;
        this.reCutApplicationService = reCutApplicationService;
    }

    public Map<String, Object> deleteRow(int sequenceNumber, String messageText, int width) {
        Map<String, Object> result = new HashMap<>();

        if (!optimizationContext.hasResult()) {
            result.put("success", false);
            result.put("message", "没有可修改的优化结果，请先执行优化。");
            return result;
        }

        CuttingOptimizationResult optimizationResult = optimizationContext.getLastOptimizationResult();
        List<Map<String, Object>> currentGroups = excelExportService.buildPreviewGroups(optimizationResult);
        Map<String, Object> targetGroup = findGroup(currentGroups, sequenceNumber);
        if (targetGroup == null) {
            result.put("success", false);
            result.put("message", "未找到序号组 #" + sequenceNumber);
            return result;
        }

        undoService.saveSnapshot("删除行 #" + sequenceNumber + " " + messageText + " " + width + "mm");

        int usageCount = ((Number) targetGroup.get("usageCount")).intValue();
        int length = ((Number) targetGroup.get("length")).intValue();
        int rollWidth = ((Number) targetGroup.get("rollWidth")).intValue();
        String surfaceTreatment = (String) targetGroup.get("surfaceTreatment");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) targetGroup.get("rows");

        log.info("========== 删除行 ==========");
        log.info("序号组 #{}，删除 {} {}mm", sequenceNumber, messageText, width);

        deductDemand(messageText, width, usageCount);

        int pendingAdded = 0;
        for (Map<String, Object> row : rows) {
            String rowMessageText = (String) row.get("messageText");
            int rowWidth = ((Number) row.get("width")).intValue();
            if (rowMessageText.equals(messageText) && rowWidth == width) {
                continue;
            }
            String salesperson = row.get("salesperson") != null ? (String) row.get("salesperson") : "";
            pendingPoolService.add(sequenceNumber, rowMessageText, salesperson,
                    rowWidth, usageCount, length, surfaceTreatment, rollWidth);
            pendingAdded++;
        }

        result.put("success", true);
        result.put("message", "已删除 " + messageText + " " + width + "mm，并释放 " + pendingAdded + " 条待搭切记录");
        result.put("deletedRow", Map.of(
                "sequenceNumber", sequenceNumber,
                "messageText", messageText,
                "width", width));
        result.put("pendingCount", pendingPoolService.size());
        result.put("pendingSlots", pendingPoolService.toMapList());
        result.put("undoCount", undoService.getUndoCount());
        return result;
    }

    public Map<String, Object> reCut(List<Integer> selectedSequenceNumbers) {
        ReCutCommand command = new ReCutCommand();
        command.setSelectedSequenceNumbers(selectedSequenceNumbers);
        return reCutApplicationService.reCut(command);
    }

    public Map<String, Object> reCut(ReCutCommand command) {
        return reCutApplicationService.reCut(command);
    }

    public Map<String, Object> undo() {
        Map<String, Object> undoResult = undoService.undo();
        if (Boolean.TRUE.equals(undoResult.get("success"))) {
            pendingPoolService.clear();
            tempGroupService.clear();
        }
        return undoResult;
    }

    private Map<String, Object> findGroup(List<Map<String, Object>> groups, int sequenceNumber) {
        for (Map<String, Object> group : groups) {
            if (((Number) group.get("sequenceNumber")).intValue() == sequenceNumber) {
                return group;
            }
        }
        return null;
    }

    private void deductDemand(String messageText, int width, int amount) {
        List<ExcelImportService.OrderItem> orderItems = optimizationContext.getLastOrderItems();
        if (orderItems == null) {
            return;
        }

        for (ExcelImportService.OrderItem item : orderItems) {
            if (messageText.equals(item.getMessageText()) && item.getWidth() == width) {
                int oldQuantity = item.getQuantity();
                item.deductQuantity(amount);
                log.info("需求扣减: {} {}mm: {} -> {}", messageText, width, oldQuantity, item.getQuantity());
                return;
            }
        }
    }
}
