package test.demo.apsmodule.service;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 订单数据规范化服务
 * 将Excel导入的冗余OrderItem转换为干净的SolverOrderItem
 */
@Component
public class OrderNormalizer {

    /**
     * 规范化订单项列表
     * 清理原始数据，生成groupKey，转换为求解器输入结构
     * 
     * @param input Excel导入的原始订单项列表
     * @return 规范化的求解器输入列表
     */
    public List<SolverOrderItem> normalize(List<ProductionOrder> input) {
        List<SolverOrderItem> list = new ArrayList<>();

        for (ProductionOrder o : input) {
            SolverOrderItem s = new SolverOrderItem();

            // 核心求解字段
            s.setMessageText(o.getMessageText());
            s.setWidth(o.getWidth());
            s.setDemand(o.getQuantity());
            s.setLength(o.getLength());
            s.setSurfaceTreatment(o.getSurfaceTreatment());

            // 🔥 修复：分组键只用长度+表面处理，不包含业务员
            // 这样可以正确分组（3个分组而不是17个），并且与需求满足情况计算逻辑匹配
            s.setGroupKey(o.getLength() + "m+" + o.getSurfaceTreatment());

            // 导出需要的字段
            s.setSalesperson(o.getSalesperson());
            s.setThickness(o.getThickness());
            s.setDescription(o.getDescription());

            list.add(s);
        }

        return list;
    }
}
