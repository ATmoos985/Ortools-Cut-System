package test.demo.apsmodule.solver.kernel;

import test.demo.apsmodule.service.SolverOrderItem;

/**
 * Immutable order item at the complete-kernel boundary.
 */
public record SolverKernelOrderItem(
        String messageText,
        int width,
        int demand,
        int length,
        String surfaceTreatment,
        String groupKey,
        String salesperson,
        int thickness,
        String description) {

    public static SolverKernelOrderItem from(SolverOrderItem item) {
        return new SolverKernelOrderItem(
                item.getMessageText(),
                item.getWidth(),
                item.getDemand(),
                item.getLength(),
                item.getSurfaceTreatment(),
                item.getGroupKey(),
                item.getSalesperson(),
                item.getThickness(),
                item.getDescription());
    }

    public SolverOrderItem toSolverOrderItem() {
        SolverOrderItem item = new SolverOrderItem();
        item.setMessageText(messageText);
        item.setWidth(width);
        item.setDemand(demand);
        item.setLength(length);
        item.setSurfaceTreatment(surfaceTreatment);
        item.setGroupKey(groupKey);
        item.setSalesperson(salesperson);
        item.setThickness(thickness);
        item.setDescription(description);
        return item;
    }
}
