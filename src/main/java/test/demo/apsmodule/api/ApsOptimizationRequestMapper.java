package test.demo.apsmodule.api;

import org.springframework.stereotype.Component;
import test.demo.apsmodule.api.dto.ApsOptimizationModels;
import test.demo.rest.dto.OptimizationRequest;
import test.demo.apsmodule.service.excel.ExcelImportService;

import java.util.ArrayList;
import java.util.List;

@Component
public class ApsOptimizationRequestMapper {

    public OptimizationRequest toOptimizationRequest(ApsOptimizationModels.CreateJobRequest request) {
        OptimizationRequest mapped = new OptimizationRequest();
        ApsOptimizationModels.OptimizationConfig config = request.config();

        boolean flexibleWidth = config != null && Boolean.TRUE.equals(config.flexibleWidth());
        mapped.setFlexibleWidth(flexibleWidth);
        mapped.setFixedWidth(firstNonNull(config != null ? config.fixedWidth() : null,
                config != null ? config.totalWidth() : null,
                4600));
        mapped.setTotalWidth(firstNonNull(config != null ? config.totalWidth() : null,
                config != null ? config.fixedWidth() : null,
                4600));
        mapped.setMinWidth(firstNonNull(config != null ? config.minWidth() : null, 4300));
        mapped.setMaxWidth(firstNonNull(config != null ? config.maxWidth() : null, 4600));
        mapped.setStepSize(firstNonNull(config != null ? config.stepSize() : null, 10));
        mapped.setTotalOverCap(firstNonNull(config != null ? config.totalOverCap() : null, 30));
        mapped.setMaxIterations(firstNonNull(config != null ? config.maxIterations() : null, 300));
        mapped.setTimeoutMs(firstNonNull(config != null ? config.timeoutMs() : null, 120000L));
        mapped.setUseNewSolver(config != null && Boolean.TRUE.equals(config.useNewSolver()));
        mapped.setNewSolverTopK(firstNonNull(config != null ? config.newSolverTopK() : null, 3));
        mapped.setNewSolverMaxPatterns(firstNonNull(config != null ? config.newSolverMaxPatterns() : null, 800));
        mapped.setNewSolverMaxDistinctWidths(
                firstNonNull(config != null ? config.newSolverMaxDistinctWidths() : null, 4));
        mapped.setNewSolverStage4TimeLimit(
                firstNonNull(config != null ? config.newSolverStage4TimeLimit() : null, 30000L));
        mapped.setNewSolverSeqGroupAlpha(firstNonNull(config != null ? config.newSolverSeqGroupAlpha() : null, 1.0));
        mapped.setNewSolverSeqGroupBeta(firstNonNull(config != null ? config.newSolverSeqGroupBeta() : null, 0.0));
        mapped.setNewSolverUseOptimizedAssignment(
                config == null || config.newSolverUseOptimizedAssignment() == null
                        || config.newSolverUseOptimizedAssignment());
        mapped.setNewSolverUnderPenalty(firstNonNull(config != null ? config.newSolverUnderPenalty() : null, 1e6));
        mapped.setLnsEnabled(config == null || config.lnsEnabled() == null || config.lnsEnabled());
        mapped.setLnsEnrichPatterns(config != null && Boolean.TRUE.equals(config.lnsEnrichPatterns()));
        mapped.setQualityMode(config != null && Boolean.TRUE.equals(config.qualityMode()));
        mapped.setOrderItems(toOrderItems(request.orders()));

        return mapped;
    }

    private List<ExcelImportService.OrderItem> toOrderItems(List<ApsOptimizationModels.Order> orders) {
        List<ExcelImportService.OrderItem> orderItems = new ArrayList<>();
        if (orders == null) {
            return orderItems;
        }

        int index = 1;
        for (ApsOptimizationModels.Order order : orders) {
            ExcelImportService.OrderItem item = new ExcelImportService.OrderItem();
            item.setMessageText(order.orderId() != null && !order.orderId().isBlank()
                    ? order.orderId()
                    : "APS-" + index++);
            item.setWidth(firstNonNull(order.width(), 0));
            item.setQuantity(firstNonNull(order.quantity(), 0));
            item.setLength(firstNonNull(order.length(), 0));
            item.setSurfaceTreatment(order.surfaceTreatment() != null ? order.surfaceTreatment() : "");
            item.setSalesperson(order.salesperson());
            item.setDescription(order.description());
            item.setThickness(firstNonNull(order.thickness(), 0));
            orderItems.add(item);
        }

        return orderItems;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
