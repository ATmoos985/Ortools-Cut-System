package test.demo.apsmodule.service;

public class ProductionOrder {

    private String messageText;
    private int width;
    private int quantity;
    private int length;
    private String surfaceTreatment;

    private String importDate;
    private String importTime;
    private String consignmentDate;
    private String salesperson;
    private String customerCode;
    private String customerName;
    private String materialCode;
    private String description;
    private int thickness;
    private int demandQuantity;
    private String unit;
    private String coatingType;
    private String shippingModel;
    private String deliveryDate;

    private String groupKey;
    private boolean modified;
    private int originalQuantity = -1;

    public ProductionOrder() {
    }

    public ProductionOrder(String messageText, int width, int quantity, int length, String surfaceTreatment) {
        this.messageText = messageText;
        this.width = width;
        this.quantity = quantity;
        this.length = length;
        this.surfaceTreatment = surfaceTreatment;
        this.groupKey = generateGroupKey(length, surfaceTreatment);
    }

    public ProductionOrder(ProductionOrder source) {
        this.messageText = source.messageText;
        this.width = source.width;
        this.quantity = source.quantity;
        this.length = source.length;
        this.surfaceTreatment = source.surfaceTreatment;
        this.importDate = source.importDate;
        this.importTime = source.importTime;
        this.consignmentDate = source.consignmentDate;
        this.salesperson = source.salesperson;
        this.customerCode = source.customerCode;
        this.customerName = source.customerName;
        this.materialCode = source.materialCode;
        this.description = source.description;
        this.thickness = source.thickness;
        this.demandQuantity = source.demandQuantity;
        this.unit = source.unit;
        this.coatingType = source.coatingType;
        this.shippingModel = source.shippingModel;
        this.deliveryDate = source.deliveryDate;
        this.groupKey = source.groupKey;
        this.modified = source.modified;
        this.originalQuantity = source.originalQuantity;
    }

    public static ProductionOrder copyOf(ProductionOrder source) {
        return source == null ? null : new ProductionOrder(source);
    }

    private String generateGroupKey(int length, String surfaceTreatment) {
        return length + "m+" + surfaceTreatment;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
        this.groupKey = generateGroupKey(length, this.surfaceTreatment);
    }

    public String getSurfaceTreatment() {
        return surfaceTreatment;
    }

    public void setSurfaceTreatment(String surfaceTreatment) {
        this.surfaceTreatment = surfaceTreatment;
        this.groupKey = generateGroupKey(this.length, surfaceTreatment);
    }

    public String getImportDate() {
        return importDate;
    }

    public void setImportDate(String importDate) {
        this.importDate = importDate;
    }

    public String getImportTime() {
        return importTime;
    }

    public void setImportTime(String importTime) {
        this.importTime = importTime;
    }

    public String getConsignmentDate() {
        return consignmentDate;
    }

    public void setConsignmentDate(String consignmentDate) {
        this.consignmentDate = consignmentDate;
    }

    public String getSalesperson() {
        return salesperson;
    }

    public void setSalesperson(String salesperson) {
        this.salesperson = salesperson;
    }

    public String getCustomerCode() {
        return customerCode;
    }

    public void setCustomerCode(String customerCode) {
        this.customerCode = customerCode;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public void setMaterialCode(String materialCode) {
        this.materialCode = materialCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getThickness() {
        return thickness;
    }

    public void setThickness(int thickness) {
        this.thickness = thickness;
    }

    public int getDemandQuantity() {
        return demandQuantity;
    }

    public void setDemandQuantity(int demandQuantity) {
        this.demandQuantity = demandQuantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getCoatingType() {
        return coatingType;
    }

    public void setCoatingType(String coatingType) {
        this.coatingType = coatingType;
    }

    public String getShippingModel() {
        return shippingModel;
    }

    public void setShippingModel(String shippingModel) {
        this.shippingModel = shippingModel;
    }

    public String getDeliveryDate() {
        return deliveryDate;
    }

    public void setDeliveryDate(String deliveryDate) {
        this.deliveryDate = deliveryDate;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }

    public boolean isModified() {
        return modified;
    }

    public void setModified(boolean modified) {
        this.modified = modified;
    }

    public int getOriginalQuantity() {
        return originalQuantity == -1 ? quantity : originalQuantity;
    }

    public void setOriginalQuantity(int originalQuantity) {
        this.originalQuantity = originalQuantity;
    }

    public void deductQuantity(int amount) {
        if (originalQuantity == -1) {
            originalQuantity = quantity;
        }
        this.quantity = Math.max(0, this.quantity - amount);
        this.modified = true;
    }
}
