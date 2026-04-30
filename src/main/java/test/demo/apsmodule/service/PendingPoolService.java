package test.demo.apsmodule.service;

import org.springframework.stereotype.Service;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 待搭切池服务 — 管理因删除而释放的订单行
 *
 * 池中每个条目代表一个需要重新搭配的订单行，
 * 包含 messageText/salesperson/width/groupKey/usageCount 等信息。
 */
@Service
public class PendingPoolService {

    private static final Logger log = LoggerFactory.getLogger(PendingPoolService.class);

    /** 待搭切条目 */
    public static class PendingItem {
        public final String id;
        public final int originSequenceNumber;
        public final String messageText;
        public final String salesperson;
        public final int width;
        public final int usageCount; // 这个行在原序号组中对应的卷数（= instruction.usageCount）
        public final int length;
        public final String surfaceTreatment;
        public final String groupKey;
        public final int rollWidth;

        public PendingItem(int originSequenceNumber, String messageText, String salesperson,
                int width, int usageCount, int length, String surfaceTreatment, int rollWidth) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.originSequenceNumber = originSequenceNumber;
            this.messageText = messageText;
            this.salesperson = salesperson;
            this.width = width;
            this.usageCount = usageCount;
            this.length = length;
            this.surfaceTreatment = surfaceTreatment;
            this.groupKey = length + "m+" + surfaceTreatment;
            this.rollWidth = rollWidth;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("originSequenceNumber", originSequenceNumber);
            m.put("messageText", messageText);
            m.put("salesperson", salesperson);
            m.put("width", width);
            m.put("usageCount", usageCount);
            m.put("length", length);
            m.put("surfaceTreatment", surfaceTreatment);
            m.put("groupKey", groupKey);
            m.put("rollWidth", rollWidth);
            return m;
        }
    }

    private final List<PendingItem> pool = new ArrayList<>();

    /**
     * 添加一个待搭切条目
     */
    public PendingItem add(int originSeqNum, String messageText, String salesperson,
            int width, int usageCount, int length, String surfaceTreatment, int rollWidth) {
        PendingItem item = new PendingItem(originSeqNum, messageText, salesperson,
                width, usageCount, length, surfaceTreatment, rollWidth);
        pool.add(item);
        log.info("[PendingPool] 新增: " + salesperson + " " + width + "mm × " + usageCount
                + "卷 (来自 #" + originSeqNum + ")");
        return item;
    }

    /**
     * 移除指定条目
     */
    public boolean remove(String id) {
        return pool.removeIf(item -> item.id.equals(id));
    }

    /**
     * 按 groupKey 获取待搭切列表
     */
    public List<PendingItem> getByGroupKey(String groupKey) {
        List<PendingItem> result = new ArrayList<>();
        for (PendingItem item : pool) {
            if (item.groupKey.equals(groupKey)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 获取全部待搭切列表
     */
    public List<PendingItem> getAll() {
        return new ArrayList<>(pool);
    }

    /**
     * 转为 Map 列表（返回前端）
     */
    public List<Map<String, Object>> toMapList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PendingItem item : pool) {
            list.add(item.toMap());
        }
        return list;
    }

    /**
     * 取出全部并清空（给二次求解器消费）
     */
    public List<PendingItem> drainAll() {
        List<PendingItem> drained = new ArrayList<>(pool);
        pool.clear();
        return drained;
    }

    /**
     * 取出指定 groupKey 的并清空
     */
    public List<PendingItem> drainByGroupKey(String groupKey) {
        List<PendingItem> drained = new ArrayList<>();
        pool.removeIf(item -> {
            if (item.groupKey.equals(groupKey)) {
                drained.add(item);
                return true;
            }
            return false;
        });
        return drained;
    }

    public int size() {
        return pool.size();
    }

    public void clear() {
        pool.clear();
    }
}
