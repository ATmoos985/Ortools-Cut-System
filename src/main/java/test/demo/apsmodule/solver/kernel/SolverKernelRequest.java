package test.demo.apsmodule.solver.kernel;

import java.util.List;
import java.util.Objects;

import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.solver.kernel.execution.SolverExecutionContext;

/**
 * Immutable request passed to a complete solver kernel.
 */
public record SolverKernelRequest(
        List<SolverKernelOrderItem> items,
        SolverKernelSettings settings,
        SolverExecutionContext executionContext) {

    public SolverKernelRequest {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        Objects.requireNonNull(settings, "settings");
        executionContext = executionContext == null
                ? SolverExecutionContext.empty()
                : executionContext;
    }

    public static SolverKernelRequest from(List<SolverOrderItem> items, SolverConfig config,
            SolverExecutionContext executionContext) {
        return new SolverKernelRequest(
                items.stream().map(SolverKernelOrderItem::from).toList(),
                SolverKernelSettings.from(config),
                executionContext);
    }

    public List<SolverOrderItem> toSolverOrderItems() {
        return items.stream().map(SolverKernelOrderItem::toSolverOrderItem).toList();
    }
}
