package test.demo.apsmodule.solver;

import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.List;

/**
 * 切割算法策略接口
 * <p>
 * 定义所有切割优化算法必须实现的统一标准。
 * 通过此接口，系统可以根据配置动态选择最合适的求解器。
 */
public interface CuttingSolverAlgorithm {

    /**
     * 判断当前算法是否支持给定的配置
     *
     * @param config 求解器配置
     * @return 如果支持返回 true，否则返回 false
     */
    boolean supports(SolverConfig config);

    /**
     * 执行核心求解逻辑
     *
     * @param items  规范化的订单项列表
     * @param config 求解器配置
     * @return 优化后的切割指令列表
     */
    List<CuttingInstruction> solve(List<SolverOrderItem> items, SolverConfig config);
}
