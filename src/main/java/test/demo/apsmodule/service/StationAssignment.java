package test.demo.apsmodule.service;

/**
 * 工位分配信息
 * 确保一个序号（消息文本）对应一个提供者，解决导出时的混乱问题。
 */
public class StationAssignment {

    private int width;
    private String messageText;
    private ProductionOrder orderItem;

    public StationAssignment() {
    }

    public StationAssignment(int width, ProductionOrder orderItem) {
        this.width = width;
        this.orderItem = orderItem;
        this.messageText = orderItem != null ? orderItem.getMessageText() : null;
    }

    public StationAssignment(int width, String messageText) {
        this.width = width;
        this.messageText = messageText;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    public ProductionOrder getOrderItem() {
        return orderItem;
    }

    public void setOrderItem(ProductionOrder orderItem) {
        this.orderItem = orderItem;
        if (orderItem != null && this.messageText == null) {
            this.messageText = orderItem.getMessageText();
        }
    }
}
