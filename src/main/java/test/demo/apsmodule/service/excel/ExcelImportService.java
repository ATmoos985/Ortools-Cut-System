package test.demo.apsmodule.service.excel;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Excel文件导入服务
 * 负责解析Excel/CSV文件并提取订单数据
 */
@Service
public class ExcelImportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelImportService.class);

    /**
     * 订单项数据类
     */
    public static class OrderItem {
        // 核心字段
        private String messageText; // 消息文本 - 唯一标识
        private int width; // 宽度mm
        private int quantity; // 卷数

        // 分组字段
        private int length; // 长度m
        private String surfaceTreatment; // 表面处理

        // 业务信息字段
        private String importDate; // 导入日期
        private String importTime; // 导入时间
        private String consignmentDate; // 委托日期
        private String salesperson; // 业务员
        private String customerCode; // 客户编码
        private String customerName; // 客户名称
        private String materialCode; // 物料编码
        private String description; // 物料描述
        private int thickness; // 厚度µm
        private int demandQuantity; // 需求量
        private String unit; // 单位
        private String coatingType; // 涂布类型
        private String shippingModel; // 出货型号
        private String deliveryDate; // 交货日期

        // 分组键（自动生成）
        private String groupKey; // 长度m+表面处理的组合

        // 删除标记（二次搭切使用）
        private boolean modified = false; // 需求量是否被修改过
        private int originalQuantity = -1; // 原始需求量（-1表示未修改）

        // 默认构造函数（Jackson需要）
        public OrderItem() {
        }

        // 构造函数
        public OrderItem(String messageText, int width, int quantity, int length, String surfaceTreatment) {
            this.messageText = messageText;
            this.width = width;
            this.quantity = quantity;
            this.length = length;
            this.surfaceTreatment = surfaceTreatment;
            this.groupKey = generateGroupKey(length, surfaceTreatment);
        }

        // 兼容构造函数（用于CSV等简单格式）
        public OrderItem(int width, int quantity, String description) {
            this.messageText = "CSV_" + System.currentTimeMillis(); // 生成临时消息文本
            this.width = width;
            this.quantity = quantity;
            this.description = description;
            this.length = 1000; // 默认长度
            this.surfaceTreatment = "不电晕/不涂布"; // 默认表面处理
            this.groupKey = generateGroupKey(this.length, this.surfaceTreatment);
        }

        // 生成分组键
        private String generateGroupKey(int length, String surfaceTreatment) {
            return length + "m+" + surfaceTreatment;
        }

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

        public String getGroupKey() {
            return groupKey;
        }

        public void setGroupKey(String groupKey) {
            this.groupKey = groupKey;
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

        /**
         * 扣减需求量并标记为已修改
         */
        public void deductQuantity(int amount) {
            if (originalQuantity == -1) {
                originalQuantity = quantity; // 首次扣减时记录原始值
            }
            this.quantity = Math.max(0, this.quantity - amount);
            this.modified = true;
        }

        @Override
        public String toString() {
            return String.format(
                    "OrderItem{messageText='%s', width=%d, quantity=%d, length=%d, surfaceTreatment='%s', groupKey='%s'}",
                    messageText, width, quantity, length, surfaceTreatment, groupKey);
        }
    }

    /**
     * 解析Excel文件（支持Excel和CSV）
     */
    public List<OrderItem> parseExcelFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new IOException("文件名不能为空");
        }

        if (fileName.toLowerCase().endsWith(".csv")) {
            return parseCsvFile(file);
        } else {
            return parseExcelWorkbook(file);
        }
    }

    /**
     * 解析CSV文件
     */
    private List<OrderItem> parseCsvFile(MultipartFile file) throws IOException {
        List<OrderItem> orderItems = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // 跳过标题行
                if (lineNumber == 1)
                    continue;

                // 跳过空行
                if (line.trim().isEmpty())
                    continue;

                try {
                    // 解析CSV行
                    String[] fields = parseCsvLine(line);

                    if (fields.length >= 19) {
                        // 根据实际CSV格式：宽度在第15列(索引14)，卷数在第19列(索引18)，物料描述在第13列(索引12)
                        String widthStr = fields[14].trim();
                        String rollsStr = fields[18].trim(); // 卷数
                        String description = fields[12].trim();

                        if (!widthStr.isEmpty() && !rollsStr.isEmpty()) {
                            int width = Integer.parseInt(widthStr);
                            int rolls = Integer.parseInt(rollsStr); // 卷数

                            if (width > 0 && rolls > 0) {
                                orderItems.add(new OrderItem(width, rolls, description));
                                log.info("解析到订单项: 宽度=" + width + ", 卷数=" + rolls + ", 描述=" + description);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("跳过无效行 " + lineNumber + ": " + e.getMessage() + " - " + line);
                }
            }
        }

        log.info("CSV解析完成，共解析到 " + orderItems.size() + " 个订单项");
        log.info("Excel文件导入成功！");
        log.info("导入文件: " + file.getOriginalFilename());
        log.info("解析结果: " + orderItems.size() + " 个订单项");
        return orderItems;
    }

    /**
     * 解析CSV行，处理带引号的字段
     */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder currentField = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString());
                currentField = new StringBuilder();
            } else {
                currentField.append(c);
            }
        }

        fields.add(currentField.toString());
        return fields.toArray(new String[0]);
    }

    /**
     * 解析Excel工作簿 - 使用字段名识别
     */
    private List<OrderItem> parseExcelWorkbook(MultipartFile file) throws IOException {
        List<OrderItem> orderItems = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            // 获取标题行，建立字段名到列索引的映射
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IOException("Excel文件没有标题行");
            }

            Map<String, Integer> columnIndexMap = buildColumnIndexMap(headerRow);
            log.info("字段映射: " + columnIndexMap);

            // 🔥 特别检查关键列的映射
            log.info("========== 关键列映射检查 ==========");
            log.info("长度m列索引: " + columnIndexMap.get("长度m"));
            log.info("宽度mm列索引: " + columnIndexMap.get("宽度mm"));
            log.info("卷数列索引: " + columnIndexMap.get("卷数"));
            log.info("表面处理列索引: " + columnIndexMap.get("表面处理"));

            // 打印标题行的所有列
            log.info("========== 标题行所有列 ==========");
            for (int j = 0; j < headerRow.getLastCellNum(); j++) {
                Cell cell = headerRow.getCell(j);
                String headerName = cell != null ? getCellValueAsString(cell).trim() : "";
                log.info("列" + j + ": '" + headerName + "'");
            }

            // 从第二行开始读取数据
            int skippedCount = 0;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null)
                    continue;

                try {
                    OrderItem orderItem = parseOrderItemFromRow(row, columnIndexMap);
                    if (orderItem != null) {
                        orderItems.add(orderItem);
                    } else {
                        skippedCount++;
                    }
                } catch (Exception e) {
                    String msgText = getCellValueAsString(row, columnIndexMap, "消息文本");
                    log.error("❌ 异常跳过第 " + (i + 1) + "行（消息文本=" + msgText + "): " + e.getMessage());
                    skippedCount++;
                }
            }

            if (skippedCount > 0) {
                log.info("⚠️ 共跳过 " + skippedCount + " 行数据");
            }
        }

        log.info("Excel解析完成，共解析到 " + orderItems.size() + " 个订单项");
        log.info("Excel文件导入成功！");
        log.info("导入文件: " + file.getOriginalFilename());
        log.info("解析结果: " + orderItems.size() + " 个订单项");

        // 按分组键分组显示
        Map<String, List<OrderItem>> groupedItems = orderItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getGroupKey));
        log.info("========== 导入数据分组汇总 ==========");
        log.info("共 " + groupedItems.size() + " 个分组: " + groupedItems.keySet());

        for (Map.Entry<String, List<OrderItem>> entry : groupedItems.entrySet()) {
            String groupKey = entry.getKey();
            List<OrderItem> items = entry.getValue();

            // 统计该分组下每个宽度的总卷数
            Map<Integer, Integer> widthToTotalQuantity = new HashMap<>();
            for (OrderItem item : items) {
                widthToTotalQuantity.put(item.getWidth(),
                        widthToTotalQuantity.getOrDefault(item.getWidth(), 0) + item.getQuantity());
            }

            // 计算总卷数
            int totalRolls = widthToTotalQuantity.values().stream().mapToInt(Integer::intValue).sum();

            log.info("  分组 [" + groupKey + "]:");
            log.info("    订单项数: " + items.size());
            log.info("    需求总量: " + totalRolls + "卷");
            log.info("    宽度汇总:");
            for (Map.Entry<Integer, Integer> widthEntry : widthToTotalQuantity.entrySet()) {
                log.info("      宽度" + widthEntry.getKey() + "mm: 总需求=" + widthEntry.getValue() + "卷");
            }

            // 🔥 添加详细的行级检查 - 用于诊断数据分组问题
            log.info("    【详细订单项列表】:");
            for (OrderItem item : items) {
                log.info("      → 消息文本=" + item.getMessageText()
                        + ", 宽度=" + item.getWidth() + "mm"
                        + ", 卷数=" + item.getQuantity()
                        + ", 长度=" + item.getLength() + "m"
                        + ", 表面处理=" + item.getSurfaceTreatment()
                        + ", 业务员=" + item.getSalesperson());
            }
        }

        return orderItems;
    }

    /**
     * 构建字段名到列索引的映射
     */
    private Map<String, Integer> buildColumnIndexMap(Row headerRow) {
        Map<String, Integer> columnIndexMap = new HashMap<>();

        for (Cell cell : headerRow) {
            if (cell != null) {
                String headerName = getCellValueAsString(cell).trim();
                columnIndexMap.put(headerName, cell.getColumnIndex());
            }
        }

        return columnIndexMap;
    }

    /**
     * 从行数据解析订单项
     */
    private OrderItem parseOrderItemFromRow(Row row, Map<String, Integer> columnIndexMap) {
        int rowNum = row.getRowNum() + 1; // Excel行号（从1开始）

        // 获取核心字段
        String messageText = getCellValueAsString(row, columnIndexMap, "消息文本");
        Integer width = getCellNumericValue(row, columnIndexMap, "宽度mm");
        Integer quantity = getCellNumericValue(row, columnIndexMap, "卷数");
        Integer length = getCellNumericValue(row, columnIndexMap, "长度m");
        String surfaceTreatment = getCellValueAsString(row, columnIndexMap, "表面处理");

        // 🔧 规范化表面处理字段（统一全角/半角、去除空格）
        if (surfaceTreatment != null && !surfaceTreatment.isEmpty()) {
            surfaceTreatment = surfaceTreatment
                    .replaceAll("[／/]", "/") // 统一全角/半角斜杠
                    .replaceAll("\\s+", "") // 去除所有空格
                    .trim();
        }

        // 🔍 针对消息文本71和78添加详细调试
        if ("71".equals(messageText) || "78".equals(messageText)) {
            log.info("========== 【关键调试】消息文本" + messageText + "（第" + rowNum + "行）==========");
            log.info("  宽度mm: " + width);
            log.info("  卷数: " + quantity);
            log.info("  长度m: " + length);
            log.info("  表面处理: " + surfaceTreatment);

            // 打印原始单元格数据
            Integer widthColIdx = findColumnIndex(columnIndexMap, "宽度mm");
            Integer qtyColIdx = findColumnIndex(columnIndexMap, "卷数");
            Integer lengthColIdx = findColumnIndex(columnIndexMap, "长度m");
            Integer surfaceColIdx = findColumnIndex(columnIndexMap, "表面处理");

            log.info("  原始单元格数据:");
            if (widthColIdx != null) {
                Cell cell = row.getCell(widthColIdx);
                log.info("    宽度mm列(" + widthColIdx + "): " + getCellDebugInfo(cell));
            }
            if (qtyColIdx != null) {
                Cell cell = row.getCell(qtyColIdx);
                log.info("    卷数列(" + qtyColIdx + "): " + getCellDebugInfo(cell));
            }
            if (lengthColIdx != null) {
                Cell cell = row.getCell(lengthColIdx);
                log.info("    长度m列(" + lengthColIdx + "): " + getCellDebugInfo(cell));
            }
            if (surfaceColIdx != null) {
                Cell cell = row.getCell(surfaceColIdx);
                log.info("    表面处理列(" + surfaceColIdx + "): " + getCellDebugInfo(cell));
            }
        }

        // 🔥 特别关注卷数=52的订单
        if (width != null && width == 1100 && quantity != null && quantity == 52) {
            log.info("========== 【关键】第" + (row.getRowNum() + 1) + "行 ==========");
            log.info("  宽度=1100mm, 卷数=52");
            log.info("  读取到的长度=" + length + "m");
            log.info("  表面处理=" + surfaceTreatment);

            // 打印该行的完整信息
            Integer lengthColIndex = findColumnIndex(columnIndexMap, "长度m");
            Integer widthColIndex = findColumnIndex(columnIndexMap, "宽度mm");
            Integer quantityColIndex = findColumnIndex(columnIndexMap, "卷数");

            log.info("  列索引: 长度=" + lengthColIndex + ", 宽度=" + widthColIndex + ", 卷数=" + quantityColIndex);

            if (lengthColIndex != null) {
                Cell cell = row.getCell(lengthColIndex);
                log.info("  长度列(" + lengthColIndex + ")原始值: " + getCellDebugInfo(cell));
            }
        }

        // 数据验证和调试信息（改进版）
        if (length != null
                && (length != 1000 && length != 1100 && length != 1200 && length != 1300 && length != 1350)) {
            log.info(
                    "⚠️ 【数据异常】第" + (row.getRowNum() + 1) + "行: 长度值异常 = " + length + "m (常见值为1000/1100/1200/1300/1350)");
        }

        // ✅ 严格验证所有必要字段（保证数据完整性）
        if (messageText == null || messageText.trim().isEmpty() ||
                width == null || width <= 0 ||
                quantity == null || quantity <= 0 ||
                length == null || length <= 0 ||
                surfaceTreatment == null || surfaceTreatment.trim().isEmpty()) {

            // 🔍 详细诊断信息 - 只在字段缺失时打印
            log.info("⚠️ 【跳过行】第" + (row.getRowNum() + 1) + "行 - 必要字段缺失或无效:");
            log.info(
                    "  消息文本: " + (messageText == null || messageText.trim().isEmpty() ? "❌ 缺失" : "✓ " + messageText));
            log.info("  宽度mm: " + (width == null || width <= 0 ? "❌ 缺失或无效" : "✓ " + width));
            log.info("  卷数: " + (quantity == null || quantity <= 0 ? "❌ 缺失或无效" : "✓ " + quantity));
            log.info("  长度m: " + (length == null || length <= 0 ? "❌ 缺失或无效" : "✓ " + length));
            log.info("  表面处理: " + (surfaceTreatment == null || surfaceTreatment.trim().isEmpty() ? "❌ 缺失"
                    : "✓ " + surfaceTreatment));

            // 打印该行的原始数据以便人工检查
            System.out.print("  原始数据: [");
            for (int colIdx = 0; colIdx < Math.min(row.getLastCellNum(), 25); colIdx++) {
                Cell cell = row.getCell(colIdx);
                if (cell != null) {
                    String cellValue = getCellValueAsString(cell);
                    if (cellValue.length() > 20) {
                        cellValue = cellValue.substring(0, 20) + "...";
                    }
                    System.out.print(cellValue + " | ");
                }
            }
            log.info("]");

            return null;
        }

        // 创建订单项
        OrderItem orderItem = new OrderItem(messageText.trim(), width, quantity, length, surfaceTreatment.trim());

        // 填充其他字段
        orderItem.setImportDate(getCellValueAsString(row, columnIndexMap, "导入日期"));
        orderItem.setImportTime(getCellValueAsString(row, columnIndexMap, "导入时间"));
        orderItem.setConsignmentDate(getCellValueAsString(row, columnIndexMap, "委托日期"));
        orderItem.setSalesperson(getCellValueAsString(row, columnIndexMap, "业务员"));
        orderItem.setCustomerCode(getCellValueAsString(row, columnIndexMap, "客户编码"));
        orderItem.setCustomerName(getCellValueAsString(row, columnIndexMap, "客户名称"));
        orderItem.setMaterialCode(getCellValueAsString(row, columnIndexMap, "物料编码"));
        orderItem.setDescription(getCellValueAsString(row, columnIndexMap, "物料描述"));

        Integer thickness = getCellNumericValue(row, columnIndexMap, "厚度µm");
        if (thickness != null)
            orderItem.setThickness(thickness);

        Integer demandQuantity = getCellNumericValue(row, columnIndexMap, "需求量");
        if (demandQuantity != null)
            orderItem.setDemandQuantity(demandQuantity);

        orderItem.setUnit(getCellValueAsString(row, columnIndexMap, "单位"));
        orderItem.setCoatingType(getCellValueAsString(row, columnIndexMap, "涂布类型"));
        orderItem.setShippingModel(getCellValueAsString(row, columnIndexMap, "出货型号"));
        orderItem.setDeliveryDate(getCellValueAsString(row, columnIndexMap, "交货日期"));

        return orderItem;
    }

    /**
     * 获取单元格的字符串值
     */
    private String getCellValueAsString(Row row, Map<String, Integer> columnIndexMap, String columnName) {
        Integer columnIndex = findColumnIndex(columnIndexMap, columnName);
        if (columnIndex == null) {
            return "";
        }

        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return "";
        }

        // 对消息文本字段特殊处理，确保返回整数
        if ("消息文本".equals(columnName) && cell.getCellType() == CellType.NUMERIC) {
            double numericValue = cell.getNumericCellValue();
            return String.valueOf((int) Math.round(numericValue));
        }

        return getCellValueAsString(cell);
    }

    /**
     * 获取单元格的数值（增强版：支持文本、公式、混合格式）
     */
    private Integer getCellNumericValue(Row row, Map<String, Integer> columnIndexMap, String columnName) {
        Integer columnIndex = findColumnIndex(columnIndexMap, columnName);
        if (columnIndex == null) {
            return null;
        }

        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }

        try {
            switch (cell.getCellType()) {
                case NUMERIC:
                    return (int) cell.getNumericCellValue();

                case STRING:
                    String val = cell.getStringCellValue()
                            .replaceAll("[^0-9.-]", "") // 移除非数字字符（包括中文、空格等）
                            .trim();
                    return val.isEmpty() ? null : (int) Double.parseDouble(val);

                case FORMULA:
                    try {
                        // 尝试按数值型公式解析
                        return (int) cell.getNumericCellValue();
                    } catch (Exception e) {
                        // 公式结果可能是字符串，尝试转换
                        String fval = cell.getStringCellValue()
                                .replaceAll("[^0-9.-]", "")
                                .trim();
                        return fval.isEmpty() ? null : (int) Double.parseDouble(fval);
                    }

                default:
                    return null;
            }
        } catch (Exception e) {
            log.error("⚠️ 解析失败 [" + columnName + "] 列" + columnIndex + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 灵活的列查找方法，支持多种匹配方式
     */
    private Integer findColumnIndex(Map<String, Integer> columnIndexMap, String columnName) {
        // 1. 精确匹配
        if (columnIndexMap.containsKey(columnName)) {
            return columnIndexMap.get(columnName);
        }

        // 2. 定义字段映射规则
        Map<String, String[]> fieldMappings = new HashMap<>();
        fieldMappings.put("厚度µm", new String[] { "厚度µm", "厚度um", "厚度", "厚度μm", "厚度?m" });
        fieldMappings.put("消息文本", new String[] { "消息文本", "消息", "文本" });
        fieldMappings.put("宽度mm", new String[] { "宽度mm", "宽度", "宽度(mm)" });
        fieldMappings.put("卷数", new String[] { "卷数", "数量", "卷" });
        fieldMappings.put("长度m", new String[] { "长度m", "长度", "长度(m)" });
        fieldMappings.put("表面处理", new String[] { "表面处理", "表面", "处理" });
        fieldMappings.put("业务员", new String[] { "业务员", "销售员", "负责人" });
        fieldMappings.put("交货日期", new String[] { "交货日期", "交货", "日期", "交期" });
        fieldMappings.put("客户名称", new String[] { "客户名称", "客户", "客户名" });
        fieldMappings.put("物料描述", new String[] { "物料描述", "描述", "物料" });
        fieldMappings.put("导入日期", new String[] { "导入日期", "导入", "日期" });
        fieldMappings.put("委托日期", new String[] { "委托日期", "委托", "委托日" });

        // 3. 使用映射规则查找
        String[] possibleNames = fieldMappings.get(columnName);
        if (possibleNames != null) {
            for (String possibleName : possibleNames) {
                if (columnIndexMap.containsKey(possibleName)) {
                    log.info("字段映射: " + columnName + " -> " + possibleName + " (列"
                            + columnIndexMap.get(possibleName) + ")");
                    return columnIndexMap.get(possibleName);
                }
            }
        }

        // 4. 模糊匹配（包含关键词）
        for (Map.Entry<String, Integer> entry : columnIndexMap.entrySet()) {
            String headerName = entry.getKey();
            if (headerName.contains(columnName) || columnName.contains(headerName)) {
                log.info("模糊匹配: " + columnName + " -> " + headerName + " (列" + entry.getValue() + ")");
                return entry.getValue();
            }
        }

        log.info("未找到字段: " + columnName + ", 可用字段: " + columnIndexMap.keySet());
        return null;
    }

    /**
     * 获取单元格调试信息
     */
    private String getCellDebugInfo(Cell cell) {
        if (cell == null) {
            return "null";
        }

        String cellType = cell.getCellType().toString();
        String value = "";

        try {
            switch (cell.getCellType()) {
                case STRING:
                    value = "\"" + cell.getStringCellValue() + "\"";
                    break;
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        value = cell.getDateCellValue().toString();
                    } else {
                        value = String.valueOf(cell.getNumericCellValue());
                    }
                    break;
                case BOOLEAN:
                    value = String.valueOf(cell.getBooleanCellValue());
                    break;
                case FORMULA:
                    value = "公式[" + cell.getCellFormula() + "]";
                    break;
                default:
                    value = "空";
            }
        } catch (Exception e) {
            value = "错误: " + e.getMessage();
        }

        return "类型=" + cellType + ", 值=" + value;
    }

    /**
     * 获取单元格的字符串值
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
    }

    /**
     * 解析Excel文件并返回列映射信息
     */
    public Map<String, Object> parseExcelFileWithMapping(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new IOException("文件名不能为空");
        }

        if (fileName.toLowerCase().endsWith(".csv")) {
            // CSV文件暂时不支持列映射
            List<OrderItem> orderItems = parseCsvFile(file);
            Map<String, Object> result = new HashMap<>();
            result.put("orderItems", orderItems);
            result.put("columnMapping", new HashMap<String, Integer>());
            return result;
        } else {
            return parseExcelWorkbookWithMapping(file);
        }
    }

    /**
     * 解析Excel工作簿并返回列映射信息
     */
    private Map<String, Object> parseExcelWorkbookWithMapping(MultipartFile file) throws IOException {
        List<OrderItem> orderItems = new ArrayList<>();
        Map<String, Integer> columnMapping = new HashMap<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            // 获取标题行，建立字段名到列索引的映射
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IOException("Excel文件没有标题行");
            }

            columnMapping = buildColumnIndexMap(headerRow);
            log.info("字段映射: " + columnMapping);

            // 从第二行开始读取数据
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null)
                    continue;

                try {
                    OrderItem orderItem = parseOrderItemFromRow(row, columnMapping);
                    if (orderItem != null) {
                        orderItems.add(orderItem);
                        log.info("解析到订单项: " + orderItem);
                    }
                } catch (Exception e) {
                    log.error("跳过无效行 " + (i + 1) + ": " + e.getMessage());
                }
            }
        }

        log.info("Excel解析完成，共解析到 " + orderItems.size() + " 个订单项");
        log.info("Excel文件导入成功！");
        log.info("导入文件: " + file.getOriginalFilename());
        log.info("解析结果: " + orderItems.size() + " 个订单项");

        // 按分组键分组显示
        Map<String, List<OrderItem>> groupedItems = orderItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getGroupKey));
        log.info("分组结果: " + groupedItems.keySet());

        Map<String, Object> result = new HashMap<>();
        result.put("orderItems", orderItems);
        result.put("columnMapping", columnMapping);
        return result;
    }
}
