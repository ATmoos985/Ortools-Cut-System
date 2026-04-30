package test.demo.apsmodule.service;

import org.springframework.stereotype.Service;
import test.demo.rest.context.OptimizationContext;
import test.demo.apsmodule.service.excel.ExcelImportService;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 临时序号组服务 — 管理未能自动搭配的项 + 人工候选补位
 *
 * 核心功能：
 * 1. 创建临时序号组（只有一个或多个宽幅，但总宽未达标准）
 * 2. 提供候选列表（按需求量排序 + 宽幅范围颜色标识）
 * 3. 人工加入候选到临时组
 */
@Service
public class TempGroupService {

    private static final Logger log = LoggerFactory.getLogger(TempGroupService.class);

    /** 临时序号组 */
    public static class TempGroup {
        public final String id;
        public final String groupKey;
        public final int length;
        public final String surfaceTreatment;
        public final int rollWidth; // 母卷宽度
        public final int minRollWidth; // 宽幅范围下限
        public final int maxRollWidth; // 宽幅范围上限
        public final List<TempGroupRow> rows = new ArrayList<>();

        public TempGroup(String groupKey, int length, String surfaceTreatment,
                int rollWidth, int minRollWidth, int maxRollWidth) {
            this.id = "T" + UUID.randomUUID().toString().substring(0, 6);
            this.groupKey = groupKey;
            this.length = length;
            this.surfaceTreatment = surfaceTreatment;
            this.rollWidth = rollWidth;
            this.minRollWidth = minRollWidth;
            this.maxRollWidth = maxRollWidth;
        }

        /** 当前已用宽度总和 */
        public int currentWidth() {
            return rows.stream().mapToInt(r -> r.width).sum();
        }

        /** 剩余可填空间 */
        public int remainingSpace() {
            return maxRollWidth - currentWidth();
        }

        /** 是否已达标（总宽在 [min, max] 范围内） */
        public boolean isComplete() {
            int w = currentWidth();
            return w >= minRollWidth && w <= maxRollWidth;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("groupKey", groupKey);
            m.put("length", length);
            m.put("surfaceTreatment", surfaceTreatment);
            m.put("rollWidth", rollWidth);
            m.put("minRollWidth", minRollWidth);
            m.put("maxRollWidth", maxRollWidth);
            m.put("currentWidth", currentWidth());
            m.put("remainingMin", minRollWidth - currentWidth()); // 还差多少到下限
            m.put("remainingMax", maxRollWidth - currentWidth()); // 还能加多少到上限
            m.put("isComplete", isComplete());

            List<Map<String, Object>> rowList = new ArrayList<>();
            for (TempGroupRow r : rows) {
                rowList.add(r.toMap());
            }
            m.put("rows", rowList);
            return m;
        }
    }

    /** 临时组中的行 */
    public static class TempGroupRow {
        public final String messageText;
        public final String salesperson;
        public final int width;
        public final int usageCount;

        public TempGroupRow(String messageText, String salesperson, int width, int usageCount) {
            this.messageText = messageText;
            this.salesperson = salesperson;
            this.width = width;
            this.usageCount = usageCount;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("messageText", messageText);
            m.put("salesperson", salesperson);
            m.put("width", width);
            m.put("usageCount", usageCount);
            return m;
        }
    }

    /** 候选结果（含颜色标识） */
    public static class CandidateResult {
        public final String messageText;
        public final String salesperson;
        public final int width;
        public final int totalDemand;
        public final int remainingDemand;
        public final String status; // "ok" / "partial" / "exceed"

        public CandidateResult(String messageText, String salesperson, int width,
                int totalDemand, int remainingDemand, String status) {
            this.messageText = messageText;
            this.salesperson = salesperson;
            this.width = width;
            this.totalDemand = totalDemand;
            this.remainingDemand = remainingDemand;
            this.status = status;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("messageText", messageText);
            m.put("salesperson", salesperson);
            m.put("width", width);
            m.put("totalDemand", totalDemand);
            m.put("remainingDemand", remainingDemand);
            m.put("status", status); // "ok"=绿色, "partial"=橙色, "exceed"=灰色
            return m;
        }
    }

    private final List<TempGroup> tempGroups = new ArrayList<>();
    private final OptimizationContext optimizationContext;

    public TempGroupService(OptimizationContext optimizationContext) {
        this.optimizationContext = optimizationContext;
    }

    /**
     * 创建新的临时序号组
     */
    public TempGroup createTempGroup(String groupKey, int length, String surfaceTreatment,
            int rollWidth, int minRollWidth, int maxRollWidth) {
        TempGroup tg = new TempGroup(groupKey, length, surfaceTreatment,
                rollWidth, minRollWidth, maxRollWidth);
        tempGroups.add(tg);
        log.info("[TempGroup] 创建临时组 " + tg.id + " (groupKey=" + groupKey + ")");
        return tg;
    }

    /**
     * 向临时组添加一行
     */
    public void addRow(String tempGroupId, String messageText, String salesperson, int width, int usageCount) {
        TempGroup tg = findById(tempGroupId);
        if (tg != null) {
            tg.rows.add(new TempGroupRow(messageText, salesperson, width, usageCount));
            log.info("[TempGroup] " + tg.id + " 添加: " + salesperson + " " + width + "mm");
        }
    }

    /**
     * 获取临时组的候选列表（按需求量排序 + 颜色标识）
     */
    public List<CandidateResult> getCandidates(String tempGroupId) {
        TempGroup tg = findById(tempGroupId);
        if (tg == null)
            return Collections.emptyList();

        List<ExcelImportService.OrderItem> orderItems = optimizationContext.getLastOrderItems();
        CuttingOptimizationResult optResult = optimizationContext.getLastOptimizationResult();
        if (orderItems == null)
            return Collections.emptyList();

        // 统计已分配量
        Map<String, Integer> allocatedMap = buildAllocatedMap(optResult);

        int currentWidth = tg.currentWidth();
        List<CandidateResult> candidates = new ArrayList<>();

        for (ExcelImportService.OrderItem item : orderItems) {
            // 跳过不同 groupKey 的
            String itemGroupKey = item.getLength() + "m+" + item.getSurfaceTreatment();
            if (!itemGroupKey.equals(tg.groupKey))
                continue;

            // 跳过已在临时组中的（同 messageText + width）
            boolean alreadyInGroup = tg.rows.stream()
                    .anyMatch(r -> r.messageText.equals(item.getMessageText()) && r.width == item.getWidth());
            if (alreadyInGroup)
                continue;

            String key = item.getMessageText() + "|" + item.getWidth();
            int allocated = allocatedMap.getOrDefault(key, 0);
            int remaining = item.getQuantity() - allocated;

            // 计算加入后的总宽
            int newTotal = currentWidth + item.getWidth();
            String status;
            if (newTotal > tg.maxRollWidth) {
                status = "exceed"; // 超上限，灰色
            } else if (newTotal < tg.minRollWidth) {
                status = "partial"; // 未达下限，橙色（还可以继续加）
            } else {
                status = "ok"; // 在范围内，绿色
            }

            candidates.add(new CandidateResult(
                    item.getMessageText(), item.getSalesperson(),
                    item.getWidth(), item.getQuantity(), remaining, status));
        }

        // 按剩余需求量降序
        candidates.sort((a, b) -> Integer.compare(b.remainingDemand, a.remainingDemand));
        return candidates;
    }

    /**
     * 人工加入候选到临时组
     */
    public Map<String, Object> addCandidate(String tempGroupId, String messageText, int width) {
        Map<String, Object> result = new HashMap<>();
        TempGroup tg = findById(tempGroupId);
        if (tg == null) {
            result.put("success", false);
            result.put("message", "未找到临时组 " + tempGroupId);
            return result;
        }

        // 校验宽幅范围
        int newTotal = tg.currentWidth() + width;
        if (newTotal > tg.maxRollWidth) {
            result.put("success", false);
            result.put("message", "加入后总宽 " + newTotal + "mm 超过上限 " + tg.maxRollWidth + "mm");
            return result;
        }

        // 从原始订单中查找
        List<ExcelImportService.OrderItem> orderItems = optimizationContext.getLastOrderItems();
        ExcelImportService.OrderItem targetItem = null;
        if (orderItems != null) {
            for (ExcelImportService.OrderItem item : orderItems) {
                if (item.getMessageText().equals(messageText) && item.getWidth() == width) {
                    targetItem = item;
                    break;
                }
            }
        }

        if (targetItem == null) {
            result.put("success", false);
            result.put("message", "未找到订单 " + messageText);
            return result;
        }

        String salesperson = targetItem.getSalesperson() != null ? targetItem.getSalesperson() : "";
        addRow(tempGroupId, messageText, salesperson, width, 1);

        result.put("success", true);
        result.put("message", "已将 " + salesperson + " " + width + "mm 加入临时组");
        result.put("tempGroup", tg.toMap());
        result.put("isComplete", tg.isComplete());
        return result;
    }

    /**
     * 获取所有临时组
     */
    public List<Map<String, Object>> toMapList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TempGroup tg : tempGroups) {
            list.add(tg.toMap());
        }
        return list;
    }

    public List<TempGroup> getAll() {
        return new ArrayList<>(tempGroups);
    }

    public TempGroup findById(String id) {
        for (TempGroup tg : tempGroups) {
            if (tg.id.equals(id))
                return tg;
        }
        return null;
    }

    public void clear() {
        tempGroups.clear();
    }

    /**
     * 移除已完成的临时组（已转正为正式序号组的）
     */
    public boolean remove(String id) {
        return tempGroups.removeIf(tg -> tg.id.equals(id));
    }

    // ========== 内部方法 ==========

    private Map<String, Integer> buildAllocatedMap(CuttingOptimizationResult optResult) {
        Map<String, Integer> map = new HashMap<>();
        if (optResult == null || optResult.getCuttingInstructions() == null)
            return map;

        for (CuttingOptimizationResult.CuttingInstruction instr : optResult.getCuttingInstructions()) {
            if (instr.getStationAssignments() == null)
                continue;
            for (CuttingOptimizationResult.StationAssignment a : instr.getStationAssignments()) {
                if (a.getOrderItem() != null) {
                    String key = a.getOrderItem().getMessageText() + "|" + a.getWidth();
                    map.merge(key, instr.getUsageCount(), Integer::sum);
                }
            }
        }
        return map;
    }
}
