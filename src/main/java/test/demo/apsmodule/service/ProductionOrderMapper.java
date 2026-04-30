package test.demo.apsmodule.service;

import test.demo.apsmodule.service.excel.ExcelImportService;

import java.util.ArrayList;
import java.util.List;

public final class ProductionOrderMapper {

    private ProductionOrderMapper() {
    }

    public static ProductionOrder toProductionOrder(ExcelImportService.OrderItem source) {
        if (source == null) {
            return null;
        }

        ProductionOrder target = new ProductionOrder();
        target.setMessageText(source.getMessageText());
        target.setWidth(source.getWidth());
        target.setQuantity(source.getQuantity());
        target.setLength(source.getLength());
        target.setSurfaceTreatment(source.getSurfaceTreatment());
        target.setImportDate(source.getImportDate());
        target.setImportTime(source.getImportTime());
        target.setConsignmentDate(source.getConsignmentDate());
        target.setSalesperson(source.getSalesperson());
        target.setCustomerCode(source.getCustomerCode());
        target.setCustomerName(source.getCustomerName());
        target.setMaterialCode(source.getMaterialCode());
        target.setDescription(source.getDescription());
        target.setThickness(source.getThickness());
        target.setDemandQuantity(source.getDemandQuantity());
        target.setUnit(source.getUnit());
        target.setCoatingType(source.getCoatingType());
        target.setShippingModel(source.getShippingModel());
        target.setDeliveryDate(source.getDeliveryDate());
        target.setGroupKey(source.getGroupKey());
        target.setModified(source.isModified());
        target.setOriginalQuantity(source.getOriginalQuantity());
        return target;
    }

    public static List<ProductionOrder> toProductionOrders(List<ExcelImportService.OrderItem> source) {
        List<ProductionOrder> target = new ArrayList<>();
        if (source == null) {
            return target;
        }

        for (ExcelImportService.OrderItem item : source) {
            target.add(toProductionOrder(item));
        }
        return target;
    }
}
