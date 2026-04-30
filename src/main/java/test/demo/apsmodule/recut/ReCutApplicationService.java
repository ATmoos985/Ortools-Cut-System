package test.demo.apsmodule.recut;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.CuttingOptimizationService;
import test.demo.apsmodule.service.CuttingStatistics;
import test.demo.apsmodule.service.ProductionOrderMapper;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.UndoService;
import test.demo.apsmodule.service.excel.ExcelExportService;
import test.demo.apsmodule.service.excel.ExcelImportService;
import test.demo.rest.context.OptimizationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ReCutApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReCutApplicationService.class);

    private final OptimizationContext optimizationContext;
    private final ExcelExportService excelExportService;
    private final UndoService undoService;
    private final CuttingOptimizationService cuttingOptimizationService;

    public ReCutApplicationService(
            OptimizationContext optimizationContext,
            ExcelExportService excelExportService,
            UndoService undoService,
            CuttingOptimizationService cuttingOptimizationService) {
        this.optimizationContext = optimizationContext;
        this.excelExportService = excelExportService;
        this.undoService = undoService;
        this.cuttingOptimizationService = cuttingOptimizationService;
    }

    public Map<String, Object> reCut(ReCutCommand command) {
        Map<String, Object> result = new HashMap<>();

        List<Integer> selectedSequenceNumbers = command.getSelectedSequenceNumbers();
        if (selectedSequenceNumbers == null || selectedSequenceNumbers.isEmpty()) {
            result.put("success", false);
            result.put("message", "请先勾选需要重新搭切的序号组。");
            return result;
        }

        String currentPlanId = optimizationContext.getCurrentPlanId();
        String currentRevisionId = optimizationContext.getCurrentRevisionId();
        if (hasText(command.getPlanId()) && !Objects.equals(command.getPlanId(), currentPlanId)) {
            result.put("success", false);
            result.put("message", "方案版本已变化，请刷新搭切页面后重试。");
            return result;
        }
        if (hasText(command.getBaseRevisionId()) && !Objects.equals(command.getBaseRevisionId(), currentRevisionId)) {
            result.put("success", false);
            result.put("message", "方案版本已变化，请刷新搭切页面后重试。");
            return result;
        }

        SolverConfig lastConfig = optimizationContext.getLastSolverConfig();
        if (lastConfig == null) {
            result.put("success", false);
            result.put("message", "缺少求解配置，请先执行一次优化。");
            return result;
        }

        CuttingOptimizationResult optResult = optimizationContext.getLastOptimizationResult();
        if (optResult == null) {
            result.put("success", false);
            result.put("message", "没有可重新搭切的方案结果，请先执行优化。");
            return result;
        }

        List<Map<String, Object>> currentGroups = excelExportService.buildPreviewGroups(optResult);
        List<ExcelImportService.OrderItem> allOrderItems = optimizationContext.getLastOrderItems();

        undoService.saveSnapshot("重新搭切");

        log.info("========== 重新搭切 ==========");
        log.info("选中的序号组: {}", selectedSequenceNumbers);
        log.info("选中的组ID: {}", command.getSelectedSequenceGroupIds());

        Set<Integer> selectedSet = new LinkedHashSet<>(selectedSequenceNumbers);

        List<ExcelImportService.OrderItem> reCutOrderItems = new ArrayList<>();
        Set<Integer> pendingWidths = new HashSet<>();
        String groupKey = null;

        for (Map<String, Object> group : currentGroups) {
            int seqNum = ((Number) group.get("sequenceNumber")).intValue();
            if (!selectedSet.contains(seqNum)) {
                continue;
            }

            int usageCount = ((Number) group.get("usageCount")).intValue();
            int length = ((Number) group.get("length")).intValue();
            String surfaceTreatment = (String) group.get("surfaceTreatment");
            if (groupKey == null) {
                groupKey = length + "m+" + surfaceTreatment;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) group.get("rows");

            for (Map<String, Object> row : rows) {
                String msgText = (String) row.get("messageText");
                int width = ((Number) row.get("width")).intValue();
                String salesperson = row.get("salesperson") != null ? (String) row.get("salesperson") : "";

                ExcelImportService.OrderItem orderItem = new ExcelImportService.OrderItem();
                orderItem.setMessageText(msgText);
                orderItem.setSalesperson(salesperson);
                orderItem.setWidth(width);
                orderItem.setQuantity(usageCount);
                orderItem.setLength(length);
                orderItem.setSurfaceTreatment(surfaceTreatment);
                orderItem.setGroupKey(length + "m+" + surfaceTreatment);
                reCutOrderItems.add(orderItem);
                pendingWidths.add(width);

                log.info("待搭切: #{} {} {}mm x {}", seqNum, salesperson, width, usageCount);
            }
        }

        if (reCutOrderItems.isEmpty()) {
            result.put("success", false);
            result.put("message", "选中的序号组中没有可重新搭切的数据。");
            return result;
        }

        Set<String> reCutSalespersons = new HashSet<>();
        for (ExcelImportService.OrderItem item : reCutOrderItems) {
            if (hasText(item.getSalesperson())) {
                reCutSalespersons.add(item.getSalesperson());
            }
        }

        Set<Integer> forceOverWidths = new LinkedHashSet<>();
        if (allOrderItems != null && groupKey != null) {
            final String targetGroupKey = groupKey;
            for (ExcelImportService.OrderItem existingItem : allOrderItems) {
                if (!Objects.equals(existingItem.getGroupKey(), targetGroupKey)
                        || pendingWidths.contains(existingItem.getWidth())) {
                    continue;
                }

                boolean hasCommonSalesperson = reCutSalespersons.isEmpty()
                        || (hasText(existingItem.getSalesperson())
                                && reCutSalespersons.contains(existingItem.getSalesperson()));
                if (!hasCommonSalesperson) {
                    continue;
                }

                forceOverWidths.add(existingItem.getWidth());
                boolean alreadyAdded = reCutOrderItems.stream()
                        .anyMatch(item -> item.getWidth() == existingItem.getWidth());
                if (!alreadyAdded) {
                    ExcelImportService.OrderItem filler = new ExcelImportService.OrderItem();
                    filler.setMessageText(existingItem.getMessageText());
                    filler.setSalesperson(existingItem.getSalesperson());
                    filler.setWidth(existingItem.getWidth());
                    filler.setQuantity(0);
                    filler.setLength(existingItem.getLength());
                    filler.setSurfaceTreatment(existingItem.getSurfaceTreatment());
                    filler.setGroupKey(existingItem.getGroupKey());
                    reCutOrderItems.add(filler);
                }
            }
        }

        SolverConfig reCutConfig = cloneConfig(lastConfig);
        reCutConfig.setForceAllowOverWidths(forceOverWidths);

        removeSelectedInstructions(optResult, currentGroups, selectedSet);

        int totalNewInstructions = 0;
        try {
            CuttingOptimizationResult reCutResult = cuttingOptimizationService.optimizeUnified(
                    ProductionOrderMapper.toProductionOrders(reCutOrderItems), reCutConfig);

            if (reCutResult != null && reCutResult.getCuttingInstructions() != null) {
                for (CuttingOptimizationResult.CuttingInstruction instruction : reCutResult.getCuttingInstructions()) {
                    instruction.setNewGroup(true);
                    optResult.getCuttingInstructions().add(instruction);
                    totalNewInstructions++;
                }
            }
        } catch (Exception exception) {
            log.error("重新搭切求解失败", exception);
            result.put("success", false);
            result.put("message", "重新搭切求解失败: " + exception.getMessage());
            return result;
        }

        recalculateStats(optResult);
        String newRevisionId = optimizationContext.advanceRevision();

        result.put("success", true);
        result.put("message", "重新搭切完成，新增 " + totalNewInstructions + " 个序号组");
        result.put("newGroupCount", totalNewInstructions);
        result.put("undoCount", undoService.getUndoCount());
        result.put("planId", optimizationContext.getCurrentPlanId());
        result.put("baseRevisionId", currentRevisionId);
        result.put("newRevisionId", newRevisionId);
        return result;
    }

    private void removeSelectedInstructions(
            CuttingOptimizationResult optimizationResult,
            List<Map<String, Object>> currentGroups,
            Set<Integer> selectedSequenceNumbers) {
        Set<Integer> instructionIndicesToRemove = new HashSet<>();
        for (Map<String, Object> group : currentGroups) {
            int seqNum = ((Number) group.get("sequenceNumber")).intValue();
            if (!selectedSequenceNumbers.contains(seqNum)) {
                continue;
            }

            Object indicesObj = group.get("instructionIndices");
            if (indicesObj instanceof List<?> indices) {
                for (Object indexObj : indices) {
                    if (indexObj instanceof Number number) {
                        instructionIndicesToRemove.add(number.intValue());
                    }
                }
                continue;
            }

            Object idxObj = group.get("instructionIndex");
            if (idxObj instanceof Number number) {
                instructionIndicesToRemove.add(number.intValue());
            }
        }

        List<Integer> sortedRemoveIndices = new ArrayList<>(instructionIndicesToRemove);
        sortedRemoveIndices.sort(java.util.Collections.reverseOrder());
        for (int idx : sortedRemoveIndices) {
            if (idx >= 0 && idx < optimizationResult.getCuttingInstructions().size()) {
                optimizationResult.getCuttingInstructions().remove(idx);
            }
        }
    }

    private SolverConfig cloneConfig(SolverConfig source) {
        SolverConfig cloned = new SolverConfig();
        cloned.setMode(source.getMode());
        cloned.setMinWidth(source.getMinWidth());
        cloned.setMaxWidth(source.getMaxWidth());
        cloned.setStepSize(source.getStepSize());
        cloned.setTotalWidth(source.getTotalWidth());
        cloned.setTotalOverCap(source.getTotalOverCap());
        cloned.setMaxIterations(source.getMaxIterations());
        cloned.setTimeoutMs(source.getTimeoutMs());
        cloned.setFlexibleWidth(source.isFlexibleWidth());
        cloned.setUseNewSolver(source.isUseNewSolver());
        cloned.setNewSolverTopK(source.getNewSolverTopK());
        cloned.setNewSolverMaxPatterns(source.getNewSolverMaxPatterns());
        cloned.setNewSolverMaxDistinctWidths(source.getNewSolverMaxDistinctWidths());
        cloned.setNewSolverStage4TimeLimit(source.getNewSolverStage4TimeLimit());
        cloned.setNewSolverSeqGroupAlpha(source.getNewSolverSeqGroupAlpha());
        cloned.setNewSolverSeqGroupBeta(source.getNewSolverSeqGroupBeta());
        cloned.setNewSolverUseOptimizedAssignment(source.isNewSolverUseOptimizedAssignment());
        cloned.setNewSolverUnderPenalty(source.getNewSolverUnderPenalty());
        return cloned;
    }

    private void recalculateStats(CuttingOptimizationResult result) {
        CuttingStatistics.Summary summary = CuttingStatistics.summarizeLegacyInstructions(
                result.getCuttingInstructions(), result.getTotalWidth());
        result.setTotalRollsUsed(summary.totalRollsUsed());
        result.setTotalWaste(summary.totalWaste());
        result.setUtilizationRate(summary.utilizationRate());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
