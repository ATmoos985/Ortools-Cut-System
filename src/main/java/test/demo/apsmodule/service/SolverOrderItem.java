package test.demo.apsmodule.service;

/**
 * 求解器输入数据模型
 * 干净的求解器输入结构，不包含Excel导入的冗余字段
 */
public class SolverOrderItem {
    
    private String messageText;
    private int width;
    private int demand;
    private int length;
    private String surfaceTreatment;
    private String groupKey;
    
    // 不参与求解，但是导出时需要
    private String salesperson;
    private int thickness;
    private String description;
    
    // Getters and Setters
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
    
    public int getDemand() {
        return demand;
    }
    
    public void setDemand(int demand) {
        this.demand = demand;
    }
    
    public int getLength() {
        return length;
    }
    
    public void setLength(int length) {
        this.length = length;
    }
    
    public String getSurfaceTreatment() {
        return surfaceTreatment;
    }
    
    public void setSurfaceTreatment(String surfaceTreatment) {
        this.surfaceTreatment = surfaceTreatment;
    }
    
    public String getGroupKey() {
        return groupKey;
    }
    
    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }
    
    public String getSalesperson() {
        return salesperson;
    }
    
    public void setSalesperson(String salesperson) {
        this.salesperson = salesperson;
    }
    
    public int getThickness() {
        return thickness;
    }
    
    public void setThickness(int thickness) {
        this.thickness = thickness;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
}

