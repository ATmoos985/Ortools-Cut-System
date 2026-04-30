package test.demo.apsmodule.service;

import org.springframework.stereotype.Service;
import test.demo.rest.context.OptimizationContext;
import test.demo.apsmodule.service.excel.ExcelImportService;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 撤销服务 — 快照栈管理
 * 
 * 每次删除操作前保存快照，支持最多 10 层撤销。
 * 快照内容：CuttingInstruction 列表深拷贝 + OrderItem 需求量快照
 */
@Service
public class UndoService {

    private static final Logger log = LoggerFactory.getLogger(UndoService.class);

    private static final int MAX_UNDO = 10;

    /** 快照记录 */
    public static class Snapshot {
        public final List<Map<String, Object>> instructionSnapshots; // instruction 序列化快照
        public final Map<String, Integer> quantitySnapshot; // messageText → quantity
        public final String description;

        public Snapshot(List<Map<String, Object>> instructionSnapshots,
                Map<String, Integer> quantitySnapshot,
                String description) {
            this.instructionSnapshots = instructionSnapshots;
            this.quantitySnapshot = quantitySnapshot;
            this.description = description;
        }
    }

    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final OptimizationContext optimizationContext;

    public UndoService(OptimizationContext optimizationContext) {
        this.optimizationContext = optimizationContext;
    }

    /**
     * 保存当前状态快照
     */
    public void saveSnapshot(String description) {
        CuttingOptimizationResult optResult = optimizationContext.getLastOptimizationResult();
        List<ExcelImportService.OrderItem> orderItems = optimizationContext.getLastOrderItems();
        if (optResult == null || orderItems == null)
            return;

        // 深拷贝 instruction 列表（序列化为 Map）
        List<Map<String, Object>> instrSnap = new ArrayList<>();
        for (CuttingOptimizationResult.CuttingInstruction instr : optResult.getCuttingInstructions()) {
            Map<String, Object> snap = new HashMap<>();
            snap.put("rollWidth", instr.getRollWidth());
            snap.put("usageCount", instr.getUsageCount());
            snap.put("length", instr.getLength());
            snap.put("surfaceTreatment", instr.getSurfaceTreatment());
            snap.put("groupKey", instr.getGroupKey());
            snap.put("waste", instr.getWaste());
            snap.put("totalWidth", instr.getTotalWidth());
            snap.put("thickness", instr.getThickness());

            // 深拷贝 subRolls
            if (instr.getSubRolls() != null) {
                snap.put("subRolls", new LinkedHashMap<>(instr.getSubRolls()));
            }

            // 深拷贝 stationAssignments
            if (instr.getStationAssignments() != null) {
                List<Map<String, Object>> assignSnaps = new ArrayList<>();
                for (CuttingOptimizationResult.StationAssignment a : instr.getStationAssignments()) {
                    Map<String, Object> aSnap = new HashMap<>();
                    aSnap.put("stationIndex", a.getStationIndex());
                    aSnap.put("width", a.getWidth());
                    aSnap.put("messageText", a.getMessageText());
                    // 保存 orderItem 引用（OrderItem 本身通过 quantity 恢复）
                    aSnap.put("orderItem", a.getOrderItem());
                    assignSnaps.add(aSnap);
                }
                snap.put("stationAssignments", assignSnaps);
            }
            instrSnap.add(snap);
        }

        // 快照需求量
        Map<String, Integer> qtySnap = new HashMap<>();
        for (ExcelImportService.OrderItem item : orderItems) {
            qtySnap.put(item.getMessageText() + "|" + item.getWidth(), item.getQuantity());
        }

        undoStack.push(new Snapshot(instrSnap, qtySnap, description));

        // 限制栈深
        while (undoStack.size() > MAX_UNDO) {
            ((ArrayDeque<Snapshot>) undoStack).removeLast();
        }

        log.info("[UndoService] 快照已保存: " + description + " (栈深: " + undoStack.size() + ")");
    }

    /**
     * 撤销上一步操作
     */
    public Map<String, Object> undo() {
        Map<String, Object> result = new HashMap<>();

        if (undoStack.isEmpty()) {
            result.put("success", false);
            result.put("message", "没有可撤销的操作");
            return result;
        }

        Snapshot snapshot = undoStack.pop();
        CuttingOptimizationResult optResult = optimizationContext.getLastOptimizationResult();
        List<ExcelImportService.OrderItem> orderItems = optimizationContext.getLastOrderItems();

        if (optResult == null || orderItems == null) {
            result.put("success", false);
            result.put("message", "没有优化结果可恢复");
            return result;
        }

        // 恢复 instructions
        List<CuttingOptimizationResult.CuttingInstruction> restored = new ArrayList<>();
        for (Map<String, Object> snap : snapshot.instructionSnapshots) {
            CuttingOptimizationResult.CuttingInstruction instr = new CuttingOptimizationResult.CuttingInstruction();
            instr.setRollWidth((int) snap.get("rollWidth"));
            instr.setUsageCount((int) snap.get("usageCount"));
            instr.setLength((Integer) snap.get("length"));
            instr.setSurfaceTreatment((String) snap.get("surfaceTreatment"));
            instr.setGroupKey((String) snap.get("groupKey"));
            instr.setWaste((int) snap.get("waste"));
            instr.setTotalWidth((int) snap.get("totalWidth"));
            instr.setThickness((Integer) snap.get("thickness"));

            @SuppressWarnings("unchecked")
            Map<Integer, Integer> subRolls = (Map<Integer, Integer>) snap.get("subRolls");
            if (subRolls != null) {
                instr.setSubRolls(new LinkedHashMap<>(subRolls));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> assignSnaps = (List<Map<String, Object>>) snap.get("stationAssignments");
            if (assignSnaps != null) {
                List<CuttingOptimizationResult.StationAssignment> assignments = new ArrayList<>();
                for (Map<String, Object> aSnap : assignSnaps) {
                    CuttingOptimizationResult.StationAssignment a = new CuttingOptimizationResult.StationAssignment();
                    a.setStationIndex((int) aSnap.get("stationIndex"));
                    a.setWidth((int) aSnap.get("width"));
                    a.setMessageText((String) aSnap.get("messageText"));
                    a.setOrderItem((ProductionOrder) aSnap.get("orderItem"));
                    assignments.add(a);
                }
                instr.setStationAssignments(assignments);
            }

            restored.add(instr);
        }
        optResult.setCuttingInstructions(restored);

        // 恢复需求量
        for (ExcelImportService.OrderItem item : orderItems) {
            String key = item.getMessageText() + "|" + item.getWidth();
            if (snapshot.quantitySnapshot.containsKey(key)) {
                item.setQuantity(snapshot.quantitySnapshot.get(key));
                // 如果恢复到原始值，清除 modified 标记
                if (item.getOriginalQuantity() == item.getQuantity()) {
                    item.setModified(false);
                }
            }
        }

        log.info("[UndoService] 已撤销: " + snapshot.description + " (剩余: " + undoStack.size() + ")");

        result.put("success", true);
        result.put("message", "已撤销: " + snapshot.description);
        result.put("undoRemaining", undoStack.size());
        return result;
    }

    public int getUndoCount() {
        return undoStack.size();
    }

    public void clear() {
        undoStack.clear();
    }
}
