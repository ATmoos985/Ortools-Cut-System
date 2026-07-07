package test.demo.apsmodule.service.excel;

import test.demo.apsmodule.service.CuttingOptimizationResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Excel文件导出服务
 * 负责将切割优化结果导出为Excel文件（基于模板）
 */
@Service
public class ExcelExportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelExportService.class);

    private static final String TEMPLATE_PATH = "【输出】模板.xlsx";

    // 总宽度，用于Excel公式计算（线程局部变量，避免并发问题）
    private ThreadLocal<Integer> totalWidthHolder = new ThreadLocal<>();

    /**
     * 导出优化结果为Excel文件
     */
    public String exportOptimizationResult(
            CuttingOptimizationResult result,
            String fileName) throws IOException {

        // 设置总宽度到ThreadLocal
        int totalWidth = result.getTotalWidth();
        log.info("========== Excel导出服务 ==========");
        log.info("📏 接收到的总宽度: " + totalWidth + "mm");
        totalWidthHolder.set(totalWidth);

        try {
            return doExportOptimizationResult(result, fileName);
        } finally {
            // 清理ThreadLocal
            totalWidthHolder.remove();
            log.info("========== Excel导出完成 ==========");
        }
    }

    /**
     * 实际执行导出操作
     */
    private String doExportOptimizationResult(
            CuttingOptimizationResult result,
            String fileName) throws IOException {

        // 创建导出目录
        Path exportDir = Paths.get("exports");
        if (!Files.exists(exportDir)) {
            Files.createDirectories(exportDir);
        }

        String filePath = exportDir.resolve(fileName).toString();

        // 加载模板文件
        Path templatePath = Paths.get(TEMPLATE_PATH);
        if (!Files.exists(templatePath)) {
            throw new IOException("模板文件不存在: " + TEMPLATE_PATH);
        }

        try (InputStream templateInput = Files.newInputStream(templatePath);
                Workbook workbook = new XSSFWorkbook(templateInput)) {

            Sheet sheet = workbook.getSheetAt(0);

            // 准备数据：按组分组并排序
            // 使用新的 V2 版本，支持 StationAssignment 和统一结构
            List<GroupData> groups = prepareGroupedDataV2(result);

            // 先保存模板行的样式信息（在删除前）
            Row originalTemplateRow = sheet.getRow(5);
            if (originalTemplateRow == null) {
                throw new IOException("模板文件缺少第6行样板");
            }

            // 复制模板行的高度
            short templateRowHeight = originalTemplateRow.getHeight();

            // 保存模板单元格的样式和公式
            Map<Integer, CellStyle> templateStyles = new HashMap<>();
            Map<Integer, String> templateFormulas = new HashMap<>();
            for (int i = 0; i < 35; i++) {
                Cell cell = originalTemplateRow.getCell(i);
                if (cell != null) {
                    templateStyles.put(i, cell.getCellStyle());
                    if (cell.getCellType() == CellType.FORMULA) {
                        templateFormulas.put(i, cell.getCellFormula());
                    }
                }
            }

            // 删除所有现有的合并单元格区域（避免冲突）
            int numMergedRegions = sheet.getNumMergedRegions();
            for (int i = numMergedRegions - 1; i >= 0; i--) {
                sheet.removeMergedRegion(i);
            }

            // 删除旧数据（从第6行开始）
            int lastRow = sheet.getLastRowNum();
            if (lastRow >= 5) {
                for (int i = lastRow; i >= 5; i--) {
                    Row row = sheet.getRow(i);
                    if (row != null) {
                        sheet.removeRow(row);
                    }
                }
            }

            // 写入数据
            int currentRowIndex = 5; // 从第6行（索引5）开始
            int groupSeq = 1;

            log.info("========== 开始写入Excel ==========");
            log.info("总共要写入 " + groups.size() + " 个GroupData");

            for (GroupData group : groups) {
                int groupStartRow = currentRowIndex;

                // 为组内每个宽度创建一行
                for (WidthData widthData : group.widths) {
                    Row dataRow = sheet.getRow(currentRowIndex);
                    if (dataRow == null) {
                        dataRow = sheet.createRow(currentRowIndex);
                    }

                    // 设置行高
                    dataRow.setHeight(templateRowHeight);

                    // 复制模板样式和公式
                    for (Map.Entry<Integer, CellStyle> entry : templateStyles.entrySet()) {
                        int colIndex = entry.getKey();
                        Cell cell = dataRow.createCell(colIndex);
                        cell.setCellStyle(entry.getValue());

                        // 如果有公式，复制并调整
                        if (templateFormulas.containsKey(colIndex)) {
                            String formula = templateFormulas.get(colIndex);
                            int rowOffset = currentRowIndex - 5; // 相对于模板行的偏移
                            String adjustedFormula = adjustFormulaReferences(formula, rowOffset);

                            // 🔥 替换公式中的硬编码宽度为用户设置的总宽度（totalWidth）
                            // 注意：这里使用totalWidth而不是实际的rollWidth
                            // totalWidth是用户在前端设置的标准宽度，用于Excel公式统一计算
                            adjustedFormula = replaceTemplateWidthConstants(adjustedFormula, totalWidthHolder.get());

                            try {
                                cell.setCellFormula(adjustedFormula);
                            } catch (Exception e) {
                                log.error("公式设置失败 [行" + (currentRowIndex + 1) + ", 列" + colIndex + "]: "
                                        + e.getMessage());
                            }
                        }
                    }

                    // 填写数据
                    fillRowData(dataRow, widthData, group, groupSeq, currentRowIndex == groupStartRow);

                    currentRowIndex++;
                }

                int groupEndRow = currentRowIndex - 1;

                // 合并A列（序号）
                if (groupEndRow > groupStartRow) {
                    mergeAndStyleColumn(sheet, 0, groupStartRow, groupEndRow);
                }

                // 合并O列（电晕处理）
                if (groupEndRow > groupStartRow) {
                    mergeAndStyleColumn(sheet, 14, groupStartRow, groupEndRow);
                }

                // 在合并后填写A列和O列的值
                Row firstRow = sheet.getRow(groupStartRow);
                if (firstRow != null) {
                    Cell cellA = firstRow.getCell(0);
                    if (cellA == null)
                        cellA = firstRow.createCell(0);
                    cellA.setCellValue(groupSeq);

                    Cell cellO = firstRow.getCell(14);
                    if (cellO == null)
                        cellO = firstRow.createCell(14);
                    cellO.setCellValue(group.surfaceTreatment);
                }

                groupSeq++;
            }

            // 设置强制重算公式
            workbook.setForceFormulaRecalculation(true);

            // 写入文件
            try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
                workbook.write(fileOut);
            }
        }

        return filePath;
    }

    /**
     * 准备分组数据 V2 - 使用新的 CuttingInstruction 和 StationAssignment 结构
     * 
     * 核心改进：
     * 1. 使用 StationAssignment 确保一个序号对应一个人
     * 2. 自动验证每个 pattern 不会混入多个业务员
     * 3. 简化数据准备逻辑，减少 80% 代码复杂度
     * 4. 固定/可变模式完全统一
     * 
     * @param result 优化结果
     * @return 分组数据列表
     */
    private List<GroupData> prepareGroupedDataV2(CuttingOptimizationResult result) {
        log.info("========== ExcelExportService V2: 开始准备分组数据 ==========");

        if (result.getCuttingInstructions() == null || result.getCuttingInstructions().isEmpty()) {
            log.error("❌ 错误：getCuttingInstructions() 为空！");
            return new ArrayList<>();
        }

        log.info("收到的CuttingInstructions数量: " + result.getCuttingInstructions().size());

        List<GroupData> list = new ArrayList<>();

        for (CuttingOptimizationResult.CuttingInstruction instr : result.getCuttingInstructions()) {

            log.info("\n处理 Instruction: groupKey=" + instr.getGroupKey()
                    + ", rollWidth=" + instr.getRollWidth() + "mm"
                    + ", usageCount=" + instr.getUsageCount());

            GroupData g = new GroupData();
            g.length = instr.getLength();
            g.surfaceTreatment = instr.getSurfaceTreatment();
            g.thickness = instr.getThickness();
            g.rollWidth = instr.getRollWidth();
            g.totalUsageCount = instr.getUsageCount();
            g.subRolls = new HashMap<>(instr.getSubRolls());

            // ====== StationAssignment 校验（关键）======
            List<CuttingOptimizationResult.StationAssignment> assignments = instr.getStationAssignments();

            if (assignments == null || assignments.isEmpty()) {
                log.error("⚠️ 警告：该 instruction 没有 stationAssignments，跳过");
                continue;
            }

            // 按 messageText 分组
            Map<String, List<CuttingOptimizationResult.StationAssignment>> byMessage = new HashMap<>();
            for (CuttingOptimizationResult.StationAssignment assign : assignments) {
                if (assign.getOrderItem() == null) {
                    log.error("⚠️ 警告：StationAssignment 的 OrderItem 为 null");
                    continue;
                }
                String messageText = assign.getOrderItem().getMessageText();
                byMessage.computeIfAbsent(messageText, k -> new ArrayList<>()).add(assign);
            }

            log.info("  该 pattern 包含 " + byMessage.size() + " 个不同的 messageText");

            // 验证：一个 pattern 内所有订单必须来自同一个业务员
            Set<String> salespersons = new HashSet<>();
            for (List<CuttingOptimizationResult.StationAssignment> assignList : byMessage.values()) {
                if (!assignList.isEmpty()) {
                    String salesperson = assignList.get(0).getOrderItem().getSalesperson();
                    salespersons.add(salesperson);
                }
            }

            if (salespersons.size() > 1) {
                log.error("⚠️⚠️⚠️ 严重警告：单个切割模式内出现多个业务员的订单！");
                log.error("  业务员列表: " + salespersons);
                log.error("  这可能导致 Excel 导出混乱！");
                // 这里可以选择抛出异常或继续（取决于业务需求）
                // throw new RuntimeException("单个切割模式内出现多个业务员的订单，pattern=" +
                // instr.getGroupKey());
            }

            // 构建 WidthData 列表
            List<WidthData> widthRows = new ArrayList<>();

            for (Map.Entry<String, List<CuttingOptimizationResult.StationAssignment>> entry : byMessage.entrySet()) {
                String messageText = entry.getKey();
                List<CuttingOptimizationResult.StationAssignment> assignList = entry.getValue();

                if (assignList.isEmpty())
                    continue;

                CuttingOptimizationResult.StationAssignment firstAssign = assignList.get(0);

                WidthData w = new WidthData();
                w.messageText = messageText;
                w.salesperson = firstAssign.getOrderItem().getSalesperson();
                w.width = firstAssign.getWidth();
                w.length = firstAssign.getOrderItem().getLength();
                w.rolls = assignList.size();
                // 🔥 修复：stationCount应该是该订单实际占用的工位数，而不是模式中该宽度的总系数
                // 一个订单可能只占用部分工位，需要统计该订单在此宽度上实际分配了多少个
                w.stationCount = calculateStationCount(w.rolls, g.totalUsageCount);
                w.subRolls = new HashMap<>(instr.getSubRolls());

                // 组合位（展开）
                w.combo = expandCombo(instr.getSubRolls());

                // 生成 messageNumber（从 messageText 提取）
                w.messageNumber = extractMessageNumber(messageText);

                widthRows.add(w);

                log.info("    ✓ WidthData: " + messageText + " -> " + w.salesperson
                        + ", width=" + w.width + ", rolls=" + w.rolls + ", stationCount=" + w.stationCount);
            }

            g.widths = widthRows;
            list.add(g);
        }

        log.info("\n========== 数据准备完成，共 " + list.size() + " 个组 ==========");
        return list;
    }

    private List<GroupDataV2> mergeAdjacentEquivalentGroups(List<GroupDataV2> groups) {
        if (groups.isEmpty()) {
            return groups;
        }

        List<GroupDataV2> merged = new ArrayList<>();
        GroupDataV2 current = groups.get(0);

        for (int index = 1; index < groups.size(); index++) {
            GroupDataV2 next = groups.get(index);
            if (canMergeGroups(current, next)) {
                current = mergeGroups(current, next);
            } else {
                merged.add(current);
                current = next;
            }
        }

        merged.add(current);
        return merged;
    }

    private boolean canMergeGroups(GroupDataV2 left, GroupDataV2 right) {
        return Objects.equals(left.getGroupKey(), right.getGroupKey())
                && left.getLength() == right.getLength()
                && Objects.equals(left.getSurfaceTreatment(), right.getSurfaceTreatment())
                && left.getThickness() == right.getThickness()
                && left.getRollWidth() == right.getRollWidth()
                && Objects.equals(left.getSubRolls(), right.getSubRolls())
                && Objects.equals(normalizedGroupSignature(left), normalizedGroupSignature(right));
    }

    private GroupDataV2 mergeGroups(GroupDataV2 left, GroupDataV2 right) {
        GroupDataV2 merged = copyGroup(left);
        merged.setUsageCount(left.getUsageCount() + right.getUsageCount());
        merged.setNewGroup(left.isNewGroup() || right.isNewGroup());

        List<Integer> instructionIndices = new ArrayList<>();
        if (left.getInstructionIndices() != null) {
            instructionIndices.addAll(left.getInstructionIndices());
        }
        if (right.getInstructionIndices() != null) {
            for (Integer instructionIndex : right.getInstructionIndices()) {
                if (!instructionIndices.contains(instructionIndex)) {
                    instructionIndices.add(instructionIndex);
                }
            }
        }
        merged.setInstructionIndices(instructionIndices);
        if (!instructionIndices.isEmpty()) {
            merged.setInstructionIndex(instructionIndices.get(0));
        }

        LinkedHashMap<String, WidthRowV2> rowsByKey = new LinkedHashMap<>();
        List<WidthRowV2> rows = new ArrayList<>();
        for (WidthRowV2 row : merged.getRows()) {
            WidthRowV2 copy = copyRow(row);
            rowsByKey.put(widthRowKey(copy), copy);
            rows.add(copy);
        }
        for (WidthRowV2 row : right.getRows()) {
            String rowKey = widthRowKey(row);
            WidthRowV2 existing = rowsByKey.get(rowKey);
            if (existing == null) {
                WidthRowV2 copy = copyRow(row);
                rowsByKey.put(rowKey, copy);
                rows.add(copy);
            } else {
                existing.setRolls(existing.getRolls() + row.getRolls());
            }
        }
        rows.forEach(row -> row.setStationCount(calculateStationCount(row.getRolls(), merged.getUsageCount())));
        merged.setRows(rows);
        return merged;
    }

    private String normalizedGroupSignature(GroupDataV2 group) {
        if (group.getRows() == null || group.getRows().isEmpty() || group.getUsageCount() <= 0) {
            return "";
        }

        return group.getRows().stream()
                .sorted(Comparator.comparing(this::widthRowKey))
                .map(row -> {
                    int perRollCount = row.getRolls() % group.getUsageCount() == 0
                            ? row.getRolls() / group.getUsageCount()
                            : row.getRolls();
                    return widthRowKey(row) + "#" + perRollCount;
                })
                .collect(Collectors.joining("||"));
    }

    private GroupDataV2 copyGroup(GroupDataV2 source) {
        GroupDataV2 copy = new GroupDataV2();
        copy.setGroupKey(source.getGroupKey());
        copy.setLength(source.getLength());
        copy.setSurfaceTreatment(source.getSurfaceTreatment());
        copy.setThickness(source.getThickness());
        copy.setRollWidth(source.getRollWidth());
        copy.setUsageCount(source.getUsageCount());
        copy.setSubRolls(source.getSubRolls() == null ? null : new HashMap<>(source.getSubRolls()));
        copy.setRows(source.getRows() == null
                ? new ArrayList<>()
                : source.getRows().stream().map(this::copyRow).collect(Collectors.toCollection(ArrayList::new)));
        copy.setNewGroup(source.isNewGroup());
        copy.setInstructionIndex(source.getInstructionIndex());
        copy.setInstructionIndices(source.getInstructionIndices() == null
                ? new ArrayList<>()
                : new ArrayList<>(source.getInstructionIndices()));
        return copy;
    }

    private WidthRowV2 copyRow(WidthRowV2 source) {
        WidthRowV2 copy = new WidthRowV2();
        copy.setMessageText(source.getMessageText());
        copy.setSalesperson(source.getSalesperson());
        copy.setWidth(source.getWidth());
        copy.setRolls(source.getRolls());
        copy.setLength(source.getLength());
        copy.setStationCount(source.getStationCount());
        copy.setComboExpanded(source.getComboExpanded() == null ? null : new ArrayList<>(source.getComboExpanded()));
        return copy;
    }

    private String widthRowKey(WidthRowV2 row) {
        return Objects.toString(row.getSalesperson(), "") + "|"
                + Objects.toString(row.getMessageText(), "") + "|"
                + row.getWidth() + "|"
                + row.getLength();
    }

    /**
     * 展开组合位：将 subRolls 转换为展开的宽度列表
     * 例如: {980: 2, 1000: 1} -> [980, 980, 1000]
     */
    private List<Integer> expandCombo(Map<Integer, Integer> subRolls) {
        List<Integer> result = new ArrayList<>();

        // 按宽度排序
        List<Integer> sortedWidths = new ArrayList<>(subRolls.keySet());
        Collections.sort(sortedWidths);

        for (Integer width : sortedWidths) {
            int count = subRolls.get(width);
            for (int i = 0; i < count; i++) {
                result.add(width);
            }
        }

        return result;
    }

    /**
     * 准备分组数据 - 每个切割模式是一个组（旧版本，保留用于兼容）
     * 
     * @deprecated 使用 prepareGroupedDataV2() 替代
     */
    @Deprecated
    private List<GroupData> prepareGroupedData(CuttingOptimizationResult result) {

        log.info("========== ExcelExportService: 开始准备分组数据 ==========");

        if (result.getCuttingInstructions() == null || result.getCuttingInstructions().isEmpty()) {
            log.error("❌ 错误：getCuttingInstructions() 为空！");
            return new ArrayList<>();
        }

        log.info("收到的CuttingInstructions数量: " + result.getCuttingInstructions().size());

        // 输出每个指令的详细信息
        for (var instr : result.getCuttingInstructions()) {
            log.info("  Instruction: groupKey=" + instr.getGroupKey()
                    + ", length=" + instr.getLength()
                    + ", surfaceTreatment=" + instr.getSurfaceTreatment()
                    + ", widths=" + instr.getSubRolls().keySet());
        }

        // 按 groupKey 分组，然后按长度排序（1000m先，1100m后）
        Map<String, List<CuttingOptimizationResult.CuttingInstruction>> groupedByKey = result.getCuttingInstructions()
                .stream()
                .collect(Collectors.groupingBy(
                        instr -> instr.getGroupKey() != null ? instr.getGroupKey() : "default"));

        log.info("按groupKey分组后的组数: " + groupedByKey.size());
        log.info("分组详情: " + groupedByKey.keySet());

        // 按长度排序分组
        List<String> sortedGroupKeys = groupedByKey.keySet().stream()
                .sorted((k1, k2) -> {
                    // 从groupKey中提取长度进行排序
                    Integer len1 = extractLengthFromGroupKey(k1);
                    Integer len2 = extractLengthFromGroupKey(k2);
                    return len1.compareTo(len2);
                })
                .collect(Collectors.toList());

        List<GroupData> allGroups = new ArrayList<>();

        // 按排序后的顺序处理每个大组
        for (String groupKey : sortedGroupKeys) {
            List<CuttingOptimizationResult.CuttingInstruction> instructions = groupedByKey.get(groupKey);

            log.info("处理groupKey: " + groupKey + ", 该组有 " + instructions.size() + " 个instructions");

            // 每个instruction（切割模式）就是一个独立的组
            for (CuttingOptimizationResult.CuttingInstruction instruction : instructions) {
                GroupData groupData = new GroupData();

                // 从instruction获取公共信息
                groupData.surfaceTreatment = instruction.getSurfaceTreatment();
                groupData.thickness = instruction.getThickness();
                groupData.totalUsageCount = instruction.getUsageCount(); // 这个模式的使用次数
                groupData.rollWidth = instruction.getRollWidth(); // 🔥 获取实际母卷宽度

                log.info("  - instruction的母卷宽度: " + groupData.rollWidth + "mm");

                Map<Integer, Integer> subRolls = instruction.getSubRolls();

                log.info(
                        "  - instruction的subRolls: " + subRolls + ", usageCount=" + instruction.getUsageCount());

                // 组合位（按宽度从小到大排序）
                List<Integer> combo = new ArrayList<>(subRolls.keySet());
                Collections.sort(combo);

                // 统计每个宽度在组合中的工位数量
                Map<Integer, Integer> widthStationCount = new HashMap<>();
                for (Integer width : combo) {
                    int count = subRolls.get(width);
                    widthStationCount.put(width, count);
                }

                // 保存完整的subRolls数据到groupData
                groupData.subRolls = new HashMap<>(subRolls);

                // 🔥 修复：为每个宽度设置正确的长度
                Map<Integer, Integer> widthToLength = instruction.getWidthToLength();
                if (widthToLength == null || widthToLength.isEmpty()) {
                    // 回退：使用instruction的默认长度
                    groupData.length = instruction.getLength();
                } else {
                    // 使用第一个宽度的长度作为groupData的长度（用于显示）
                    groupData.length = widthToLength.values().iterator().next();
                }

                // 🔥 核心修复：使用工位分配信息，精确对应每个工位到具体订单项
                List<WidthData> widths = new ArrayList<>();

                List<CuttingOptimizationResult.StationAssignment> stationAssignments = instruction
                        .getStationAssignments();

                if (stationAssignments != null && !stationAssignments.isEmpty()) {
                    // 按订单项分组统计每个人的分切卷数
                    Map<String, WidthData> messageTextToWidthData = new LinkedHashMap<>();

                    for (CuttingOptimizationResult.StationAssignment assignment : stationAssignments) {
                        test.demo.apsmodule.service.ProductionOrder item = assignment.getOrderItem();
                        String messageText = item.getMessageText();

                        WidthData wd = messageTextToWidthData.get(messageText);
                        if (wd == null) {
                            wd = new WidthData();
                            wd.width = assignment.getWidth();
                            wd.messageText = messageText;
                            wd.messageNumber = extractMessageNumber(messageText);
                            wd.salesperson = item.getSalesperson();
                            wd.stationCount = 0;
                            wd.combo = combo;
                            wd.subRolls = new HashMap<>(subRolls);
                            wd.length = item.getLength();
                            wd.rolls = 0;
                            messageTextToWidthData.put(messageText, wd);
                        }

                        // 每个工位分配对应1卷
                        wd.rolls++;
                    }

                    messageTextToWidthData.values().forEach(
                            widthData -> widthData.stationCount = calculateStationCount(widthData.rolls,
                                    groupData.totalUsageCount));
                    widths.addAll(messageTextToWidthData.values());
                } else {
                    // 回退方案：没有工位分配信息时使用旧逻辑
                    log.info("    ⚠️ 警告：没有工位分配信息，使用回退方案");
                    for (Integer width : combo) {
                        int totalProducedRolls = subRolls.get(width) * instruction.getUsageCount();

                        WidthData wd = new WidthData();
                        wd.width = width;
                        wd.stationCount = calculateStationCount(totalProducedRolls, instruction.getUsageCount());
                        wd.combo = combo;
                        wd.subRolls = new HashMap<>(subRolls);
                        wd.length = widthToLength != null ? widthToLength.getOrDefault(width, instruction.getLength())
                                : instruction.getLength();
                        wd.rolls = totalProducedRolls;
                        log.info("    宽度" + width + "mm: 卷数=" + totalProducedRolls + " (无工位分配信息)");
                        widths.add(wd);
                    }
                }

                groupData.widths = widths;

                allGroups.add(groupData);
            }
        }

        log.info("========== 导出数据汇总 ==========");
        log.info("最终生成的GroupData数量: " + allGroups.size());

        return allGroups;
    }

    /**
     * 提取消息文本编号
     */
    private int extractMessageNumber(String messageText) {
        if (messageText == null || messageText.trim().isEmpty()) {
            return 0;
        }
        try {
            String trimmed = messageText.trim();
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("消息文本(\\d+)");
                java.util.regex.Matcher matcher = pattern.matcher(messageText);
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        } catch (Exception e) {
            log.error("无法从消息文本提取编号: " + messageText);
        }
        return 0;
    }

    /**
     * 从groupKey中提取长度
     */
    private Integer extractLengthFromGroupKey(String groupKey) {
        if (groupKey == null)
            return 0;
        try {
            // groupKey格式：1000m+不电晕/不涂布
            String[] parts = groupKey.split("m");
            if (parts.length > 0) {
                return Integer.parseInt(parts[0]);
            }
        } catch (Exception e) {
            log.error("无法从groupKey提取长度: " + groupKey);
        }
        return 0;
    }

    /**
     * 填写行数据
     */
    private void fillRowData(Row row, WidthData widthData, GroupData groupData,
            int groupSeq, boolean isFirstRowInGroup) {
        // A列：序号（在合并后单独填写）

        // B列：业务员
        setCellValue(row, 1, widthData.salesperson);

        // C列：编号（消息文本编号）
        setCellValue(row, 2, widthData.messageNumber);

        // D-G列：留空
        setCellValue(row, 3, "");
        setCellValue(row, 4, "");
        setCellValue(row, 5, "");
        setCellValue(row, 6, "");

        // H列：厚度
        setCellValue(row, 7, groupData.thickness);

        // I列：宽度
        setCellValue(row, 8, widthData.width);

        // J列：长度（🔥 使用该宽度的具体长度）
        setCellValue(row, 9, widthData.length);

        // K列：纸管内径
        setCellValue(row, 10, "6\"");

        // L列：分切卷数
        setCellValue(row, 11, widthData.rolls);

        // M列：平米（公式，已从模板复制，不要覆盖）

        // N列：分切产量（公式，已从模板复制，不要覆盖）

        // O列：电晕处理（在合并后单独填写）

        // P列：工位数量
        setCellValue(row, 15, widthData.stationCount);

        // Q列：边料（公式，只在组首行保留，其他行清空）
        if (!isFirstRowInGroup) {
            clearCellValue(row, 16);
        }

        // R-Y列：组合位（只在组首行填写，展开相同宽度）
        if (isFirstRowInGroup && widthData.subRolls != null) {
            // 展开组合位：980出现3次就填 980, 980, 980
            List<Integer> expandedCombo = new ArrayList<>();

            // 按宽度从小到大排序
            List<Integer> sortedWidths = new ArrayList<>(widthData.subRolls.keySet());
            Collections.sort(sortedWidths);

            // 展开每个宽度
            for (Integer width : sortedWidths) {
                int count = widthData.subRolls.get(width);
                for (int j = 0; j < count; j++) {
                    expandedCombo.add(width);
                }
            }

            // 填写到R-Y列（最多8个）
            for (int i = 0; i < Math.min(expandedCombo.size(), 8); i++) {
                setCellValue(row, 17 + i, expandedCombo.get(i));
            }

            // 如果超过8个，打印警告
            if (expandedCombo.size() > 8) {
                log.error("警告：组合位超过8个: " + expandedCombo);
            }
        } else {
            // 清空组合位
            for (int i = 0; i < 8; i++) {
                clearCellValue(row, 17 + i);
            }
        }

        // Z列：边料（公式，只在组首行保留）
        if (!isFirstRowInGroup) {
            clearCellValue(row, 25);
        }

        // AA列：分切车数（只在组首行填写）
        if (isFirstRowInGroup) {
            setCellValue(row, 26, groupData.totalUsageCount);
        } else {
            clearCellValue(row, 26);
        }

        // AB列：有效宽度（公式，只在组首行保留）
        if (!isFirstRowInGroup) {
            clearCellValue(row, 27);
        }

        // AC列：利用率（公式，只在组首行保留）
        if (!isFirstRowInGroup) {
            clearCellValue(row, 28);
        }
    }

    /**
     * 从模板复制行（改进版：自动调整公式引用）
     */
    private void copyRowFromTemplate(Row sourceRow, Row targetRow, Workbook workbook, int targetRowIndex) {
        targetRow.setHeight(sourceRow.getHeight());

        int rowOffset = targetRowIndex - sourceRow.getRowNum();

        for (int i = 0; i < 35; i++) { // A-AI列
            Cell sourceCell = sourceRow.getCell(i);
            if (sourceCell != null) {
                Cell targetCell = targetRow.getCell(i);
                if (targetCell == null) {
                    targetCell = targetRow.createCell(i);
                }

                // 复制样式
                CellStyle newStyle = workbook.createCellStyle();
                newStyle.cloneStyleFrom(sourceCell.getCellStyle());
                targetCell.setCellStyle(newStyle);

                // 如果是公式单元格，复制并调整公式
                if (sourceCell.getCellType() == CellType.FORMULA) {
                    String formula = sourceCell.getCellFormula();
                    try {
                        // 调整公式中的行引用
                        String adjustedFormula = adjustFormulaReferences(formula, rowOffset);
                        targetCell.setCellFormula(adjustedFormula);
                    } catch (Exception e) {
                        log.error("公式复制失败 [行" + (targetRowIndex + 1) + ", 列" + i + "]: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 调整公式中的行引用
     * 注意：宽度值的替换已移到 processSheet 中，使用 group.rollWidth 统一处理
     */
    private String adjustFormulaReferences(String formula, int rowOffset) {
        if (rowOffset == 0)
            return formula;

        // 替换公式中的行号引用，例如 L6 -> L7
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([A-Z]+)(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(formula);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String col = matcher.group(1);
            int row = Integer.parseInt(matcher.group(2));
            matcher.appendReplacement(result, col + (row + rowOffset));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 合并单元格并应用样式
     */
    private void mergeAndStyleColumn(Sheet sheet, int colIndex, int startRow, int endRow) {
        if (endRow <= startRow)
            return;

        CellRangeAddress region = new CellRangeAddress(startRow, endRow, colIndex, colIndex);
        sheet.addMergedRegion(region);

        // 应用边框
        RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet);
    }

    /**
     * 设置单元格值（数字）
     */
    private void setCell(Row row, int colIndex, Number value) {
        Cell cell = row.getCell(colIndex);
        if (cell == null)
            cell = row.createCell(colIndex);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        } else {
            cell.setBlank();
        }
    }

    /**
     * 设置单元格值（字符串）
     */
    private void setCell(Row row, int colIndex, String value) {
        Cell cell = row.getCell(colIndex);
        if (cell == null)
            cell = row.createCell(colIndex);
        if (value != null && !value.isEmpty()) {
            cell.setCellValue(value);
        } else {
            cell.setBlank();
        }
    }

    /**
     * 清空单元格（保留样式）
     */
    private void clearCell(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell != null) {
            // 保留样式，清空内容
            CellType cellType = cell.getCellType();
            if (cellType == CellType.FORMULA) {
                cell.setBlank();
            } else {
                cell.setBlank();
            }
        }
    }

    /**
     * 设置单元格值（不覆盖公式）- 数字
     */
    private void setCellValue(Row row, int colIndex, Number value) {
        if (colIndex < 0 || value == null)
            return;
        Cell cell = row.getCell(colIndex);
        if (cell == null)
            cell = row.createCell(colIndex);

        // 如果是公式单元格，不覆盖
        if (cell.getCellType() == CellType.FORMULA) {
            return;
        }
        cell.setCellValue(value.doubleValue());
    }

    /**
     * 设置单元格值（不覆盖公式）- 字符串
     */
    private void setCellValue(Row row, int colIndex, String value) {
        if (colIndex < 0)
            return;
        Cell cell = row.getCell(colIndex);
        if (cell == null)
            cell = row.createCell(colIndex);

        // 如果是公式单元格，不覆盖
        if (cell.getCellType() == CellType.FORMULA) {
            return;
        }

        if (value != null && !value.isEmpty()) {
            cell.setCellValue(value);
        } else {
            cell.setBlank();
        }
    }

    /**
     * 清空单元格值（包括公式）
     */
    private void clearCellValue(Row row, int colIndex) {
        if (colIndex < 0)
            return;
        Cell cell = row.getCell(colIndex);
        if (cell != null) {
            cell.setBlank();
        }
    }

    /**
     * 导出验证报告为Excel文件
     */
    public String exportValidationReport(
            Map<String, Object> validationResult,
            String fileName) throws IOException {

        // 创建导出目录
        Path exportDir = Paths.get("exports");
        if (!Files.exists(exportDir)) {
            Files.createDirectories(exportDir);
        }

        String filePath = exportDir.resolve(fileName).toString();

        try (Workbook workbook = new XSSFWorkbook()) {
            // 创建样式
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle dataStyle = createDataStyle(workbook);
            CellStyle errorStyle = createErrorStyle(workbook);
            CellStyle warningStyle = createWarningStyle(workbook);
            CellStyle successStyle = createSuccessStyle(workbook);

            // 创建验证报告工作表
            createValidationReportSheet(workbook, validationResult, headerStyle, dataStyle,
                    errorStyle, warningStyle, successStyle);

            // 写入文件
            try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
                workbook.write(fileOut);
            }
        }

        return filePath;
    }

    /**
     * 创建验证报告工作表
     */
    private void createValidationReportSheet(Workbook workbook,
            Map<String, Object> validationResult,
            CellStyle headerStyle,
            CellStyle dataStyle,
            CellStyle errorStyle,
            CellStyle warningStyle,
            CellStyle successStyle) {

        Sheet sheet = workbook.createSheet("验证报告");

        // 设置列宽
        sheet.setColumnWidth(0, 15000); // A列 - 验证信息
        sheet.setColumnWidth(1, 3000); // B列 - 类型

        int rowIndex = 0;

        // 创建标题行
        Row headerRow = sheet.createRow(rowIndex++);
        Cell titleCell = headerRow.createCell(0);
        titleCell.setCellValue("数据验证报告");
        titleCell.setCellStyle(headerStyle);

        // 合并标题单元格
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 1));

        // 添加验证摘要
        rowIndex++;
        Row summaryRow = sheet.createRow(rowIndex++);
        summaryRow.createCell(0).setCellValue("验证摘要");
        summaryRow.getCell(0).setCellStyle(headerStyle);

        // 统计信息
        int totalErrors = (Integer) validationResult.get("totalErrors");
        int totalWarnings = (Integer) validationResult.get("totalWarnings");
        int totalValid = (Integer) validationResult.get("totalValid");
        int totalRecords = (Integer) validationResult.get("totalRecords");

        Row statsRow1 = sheet.createRow(rowIndex++);
        statsRow1.createCell(0).setCellValue("总记录数: " + totalRecords);
        statsRow1.createCell(1).setCellValue("有效记录: " + totalValid);

        Row statsRow2 = sheet.createRow(rowIndex++);
        statsRow2.createCell(0).setCellValue("错误数量: " + totalErrors);
        statsRow2.createCell(1).setCellValue("警告数量: " + totalWarnings);

        // 应用样式
        statsRow1.getCell(0).setCellStyle(dataStyle);
        statsRow1.getCell(1).setCellStyle(dataStyle);
        statsRow2.getCell(0).setCellStyle(totalErrors > 0 ? errorStyle : successStyle);
        statsRow2.getCell(1).setCellStyle(totalWarnings > 0 ? warningStyle : successStyle);

        // 字段映射验证结果
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldMapping = (Map<String, Object>) validationResult.get("fieldMapping");
        if (fieldMapping != null && !fieldMapping.isEmpty()) {
            rowIndex++;
            Row fieldHeaderRow = sheet.createRow(rowIndex++);
            fieldHeaderRow.createCell(0).setCellValue("字段映射识别结果");
            fieldHeaderRow.getCell(0).setCellStyle(headerStyle);

            for (Map.Entry<String, Object> entry : fieldMapping.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fieldInfo = (Map<String, Object>) entry.getValue();
                Row itemRow = sheet.createRow(rowIndex++);

                String fieldName = entry.getKey();
                String status = (String) fieldInfo.get("status");
                Integer columnIndex = (Integer) fieldInfo.get("columnIndex");
                String columnName = (String) fieldInfo.get("columnName");

                itemRow.createCell(0).setCellValue(fieldName);
                itemRow.createCell(1).setCellValue("列" + (columnIndex + 1) + ": " + columnName);
                itemRow.createCell(2).setCellValue(status);

                CellStyle cellStyle = "success".equals(status) ? successStyle
                        : "warning".equals(status) ? warningStyle : errorStyle;

                itemRow.getCell(0).setCellStyle(cellStyle);
                itemRow.getCell(1).setCellStyle(cellStyle);
                itemRow.getCell(2).setCellStyle(cellStyle);
            }
        }

        // 解析验证结果
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parsingValidation = (List<Map<String, Object>>) validationResult
                .get("parsingValidation");
        if (parsingValidation != null && !parsingValidation.isEmpty()) {
            rowIndex++;
            Row parsingHeaderRow = sheet.createRow(rowIndex++);
            parsingHeaderRow.createCell(0).setCellValue("数据解析验证结果");
            parsingHeaderRow.getCell(0).setCellStyle(headerStyle);

            for (Map<String, Object> item : parsingValidation) {
                Row itemRow = sheet.createRow(rowIndex++);
                itemRow.createCell(0).setCellValue((String) item.get("message"));
                itemRow.createCell(1).setCellValue((String) item.get("type"));

                String type = (String) item.get("type");
                CellStyle cellStyle = "error".equals(type) ? errorStyle
                        : "warning".equals(type) ? warningStyle : successStyle;

                itemRow.getCell(0).setCellStyle(cellStyle);
                itemRow.getCell(1).setCellStyle(cellStyle);
            }
        }

        // 数据一致性验证结果
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataValidation = (List<Map<String, Object>>) validationResult.get("dataValidation");
        if (dataValidation != null && !dataValidation.isEmpty()) {
            rowIndex++;
            Row dataHeaderRow = sheet.createRow(rowIndex++);
            dataHeaderRow.createCell(0).setCellValue("数据一致性验证结果");
            dataHeaderRow.getCell(0).setCellStyle(headerStyle);

            for (Map<String, Object> item : dataValidation) {
                Row itemRow = sheet.createRow(rowIndex++);
                itemRow.createCell(0).setCellValue((String) item.get("message"));
                itemRow.createCell(1).setCellValue((String) item.get("type"));

                String type = (String) item.get("type");
                CellStyle cellStyle = "error".equals(type) ? errorStyle
                        : "warning".equals(type) ? warningStyle : successStyle;

                itemRow.getCell(0).setCellStyle(cellStyle);
                itemRow.getCell(1).setCellStyle(cellStyle);
            }
        }

        // 样本数据预览
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sampleData = (List<Map<String, Object>>) validationResult.get("sampleData");
        if (sampleData != null && !sampleData.isEmpty()) {
            rowIndex++;
            Row sampleHeaderRow = sheet.createRow(rowIndex++);
            sampleHeaderRow.createCell(0).setCellValue("数据预览 (前10条记录)");
            sampleHeaderRow.getCell(0).setCellStyle(headerStyle);

            // 表头
            Row sampleTableHeaderRow = sheet.createRow(rowIndex++);
            String[] headers = { "消息文本", "宽度(mm)", "卷数", "长度(m)", "表面处理", "物料描述", "分组键" };
            for (int i = 0; i < headers.length; i++) {
                Cell cell = sampleTableHeaderRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 数据行
            for (Map<String, Object> item : sampleData) {
                Row dataRow = sheet.createRow(rowIndex++);
                dataRow.createCell(0).setCellValue((String) item.get("messageText"));
                dataRow.createCell(1).setCellValue((Integer) item.get("width"));
                dataRow.createCell(2).setCellValue((Integer) item.get("quantity"));
                dataRow.createCell(3).setCellValue((Integer) item.get("length"));
                dataRow.createCell(4).setCellValue((String) item.get("surfaceTreatment"));
                dataRow.createCell(5).setCellValue((String) item.get("description"));
                dataRow.createCell(6).setCellValue((String) item.get("groupKey"));

                // 应用数据样式
                for (int i = 0; i < 7; i++) {
                    dataRow.getCell(i).setCellStyle(dataStyle);
                }
            }
        }
    }

    /**
     * 创建标题样式
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();

        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        font.setColor(IndexedColors.WHITE.getIndex());

        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        return style;
    }

    /**
     * 创建数据样式
     */
    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();

        font.setFontHeightInPoints((short) 10);

        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        return style;
    }

    /**
     * 创建错误样式
     */
    private CellStyle createErrorStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();

        font.setFontHeightInPoints((short) 10);
        font.setColor(IndexedColors.WHITE.getIndex());

        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.RED.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        return style;
    }

    /**
     * 创建警告样式
     */
    private CellStyle createWarningStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();

        font.setFontHeightInPoints((short) 10);
        font.setColor(IndexedColors.BLACK.getIndex());

        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        return style;
    }

    /**
     * 创建成功样式
     */
    private CellStyle createSuccessStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();

        font.setFontHeightInPoints((short) 10);
        font.setColor(IndexedColors.WHITE.getIndex());

        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        return style;
    }

    // 内部数据类

    /**
     * 分组数据
     */
    private static class GroupData {
        Integer length;
        String surfaceTreatment;
        Integer thickness;
        List<WidthData> widths;
        int totalUsageCount; // 分切车数
        Map<Integer, Integer> subRolls; // 完整的宽度->数量映射
        int rollWidth; // 🔥 新增：该切割指令使用的实际母卷宽度（可变宽度模式）
    }

    /**
     * 宽度数据
     */
    private static class WidthData {
        int width;
        int length; // 🔥 添加长度字段
        int messageNumber;
        String salesperson;
        String messageText; // 🔥 新增：消息文本字段
        int rolls; // 分切卷数
        int stationCount; // 工位数量
        List<Integer> combo; // 组合位（唯一宽度列表）
        Map<Integer, Integer> subRolls; // 完整的宽度->数量映射（用于展开组合位）
    }

    // ==================================================================================
    // ✨ 新增：V2 版本导出方法（简化版，基于新的统一架构）
    // ==================================================================================

    /**
     * V2 版本：简化的导出方法
     * 特点：
     * - 使用新的统一 CuttingInstruction 结构
     * - 自动验证业务员唯一性
     * - 更简洁的代码（约 200 行 vs 原来的 1500 行）
     * - 完全兼容现有模板
     */
    public String exportOptimizationResultV2(
            CuttingOptimizationResult result,
            String fileName) throws IOException {

        log.info("========== Excel导出服务 V2（简化版）==========");
        int totalWidth = result.getTotalWidth();

        Path exportDir = Paths.get("exports");
        if (!Files.exists(exportDir)) {
            Files.createDirectories(exportDir);
        }

        String filePath = exportDir.resolve(fileName).toString();

        // 加载模板文件
        Path templatePath = Paths.get(TEMPLATE_PATH);
        if (!Files.exists(templatePath)) {
            throw new IOException("模板文件不存在: " + TEMPLATE_PATH);
        }

        try (InputStream templateInput = Files.newInputStream(templatePath);
                Workbook workbook = new XSSFWorkbook(templateInput)) {

            Sheet sheet = workbook.getSheetAt(0);

            // 读取模板行样式（第六行，索引5）
            Row templateRow = sheet.getRow(5);
            if (templateRow == null) {
                throw new IOException("模板文件缺少第6行样板");
            }

            Map<Integer, CellStyle> templateStyles = extractStylesV2(templateRow);
            Map<Integer, String> templateFormulas = extractFormulasV2(templateRow);
            short templateRowHeight = templateRow.getHeight();

            // 清除旧内容
            clearOldDataV2(sheet);

            // 构建分组数据
            List<GroupDataV2> groups = buildGroupsV2(result);

            int currentRow = 5; // 从第6行开始（索引5）
            int seq = 1;

            for (GroupDataV2 g : groups) {

                int startRow = currentRow;

                for (WidthRowV2 rowInfo : g.getRows()) {

                    Row row = sheet.createRow(currentRow);
                    row.setHeight(templateRowHeight);

                    // 复制样式和公式
                    applyTemplateRowV2(row, templateStyles, templateFormulas, currentRow - 5, totalWidth);

                    // 写入业务数据
                    fillRowV2(row, g, rowInfo, currentRow == startRow);

                    // 🔥 关键修复：对于非首行，清除组级指标的公式（避免显示 0 或 0%）
                    if (currentRow > startRow) {
                        // Q列(16): 边料
                        clearCellValue(row, 16);
                        // Z列(25): 边料
                        clearCellValue(row, 25);
                        // AB列(27): 有效宽度
                        clearCellValue(row, 27);
                        // AC列(28): 利用率
                        clearCellValue(row, 28);
                    }

                    currentRow++;
                }

                // 合并 A 列（序号）和 O 列（电晕处理）
                mergeColumnV2(sheet, 0, startRow, currentRow - 1);
                mergeColumnV2(sheet, 14, startRow, currentRow - 1);

                // 写入合并列的值
                Row firstRow = sheet.getRow(startRow);
                setCellValueV2(firstRow.getCell(0), seq);
                setCellValueV2(firstRow.getCell(14), g.getSurfaceTreatment());

                seq++;
            }

            workbook.setForceFormulaRecalculation(true);

            try (FileOutputStream out = new FileOutputStream(filePath)) {
                workbook.write(out);
            }

            log.info("✅ V2 导出完成: " + filePath);
            log.info("========== Excel导出完成 ==========");
        }

        return filePath;
    }

    /**
     * 🔥 新增公共方法：供 Controller 调用，生成预览数据
     * 复用 buildGroupsV2 的分组逻辑，确保预览与 Excel 导出一致
     */
    public List<Map<String, Object>> buildPreviewGroups(CuttingOptimizationResult result) {
        List<Map<String, Object>> previewGroups = new ArrayList<>();

        // 复用现有分组逻辑
        List<GroupDataV2> groups = buildGroupsV2(result);

        int sequenceNumber = 1;
        for (GroupDataV2 g : groups) {
            Map<String, Object> group = new HashMap<>();

            group.put("sequenceNumber", sequenceNumber++);
            group.put("instructionIndex", g.getInstructionIndex());
            group.put("instructionIndices", g.getInstructionIndices());
            group.put("surfaceTreatment", g.getSurfaceTreatment());
            group.put("length", g.getLength());
            group.put("rollWidth", g.getRollWidth());
            group.put("usageCount", g.getUsageCount());
            group.put("thickness", g.getThickness());

            // 组合位展开
            List<Integer> comboExpanded = expandComboV2(g.getSubRolls());
            group.put("comboExpanded", comboExpanded);

            // 行数据
            List<Map<String, Object>> rows = new ArrayList<>();
            if (g.getRows() != null) {
                for (WidthRowV2 wr : g.getRows()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("salesperson", wr.getSalesperson());
                    row.put("messageText", wr.getMessageText());
                    row.put("width", wr.getWidth());
                    row.put("length", wr.getLength());
                    row.put("rolls", wr.getRolls());
                    row.put("stationCount", wr.getStationCount());
                    rows.add(row);
                }
            }

            group.put("rows", rows);

            // 传递 isNewGroup 标记
            if (g.isNewGroup()) {
                group.put("isNewGroup", true);
            }

            previewGroups.add(group);
        }

        return previewGroups;
    }

    /**
     * V2: 构建分组数据（基于新的 CuttingInstruction 结构）
     */
    private List<GroupDataV2> buildGroupsV2(CuttingOptimizationResult result) {

        if (result.getCuttingInstructions() == null || result.getCuttingInstructions().isEmpty()) {
            log.error("❌ 错误：CuttingInstructions 为空！");
            return new ArrayList<>();
        }

        log.info("\n📊 开始构建分组数据 V2...");
        log.info("收到的 CuttingInstructions 数量: " + result.getCuttingInstructions().size());

        List<GroupDataV2> list = new ArrayList<>();

        List<CuttingOptimizationResult.CuttingInstruction> allInstrs = result.getCuttingInstructions();
        for (int instrIdx = 0; instrIdx < allInstrs.size(); instrIdx++) {
            CuttingOptimizationResult.CuttingInstruction instr = allInstrs.get(instrIdx);

            log.info("\n处理 Instruction: groupKey=" + instr.getGroupKey()
                    + ", rollWidth=" + instr.getRollWidth() + "mm"
                    + ", usageCount=" + instr.getUsageCount());

            if (instr.getStationAssignments() == null || instr.getStationAssignments().isEmpty()) {
                // 🔥 修复：跳过没有 stationAssignments 的指令，而不是抛出异常
                log.error("⚠️ 警告：pattern 没有 stationAssignments，跳过此指令: " + instr.getGroupKey());
                continue; // 跳过此指令，继续处理下一个
            }

            // 构建分组数据（包含拆分逻辑）
            List<GroupDataV2> splitGroups = splitInstructionIntoGroups(instr);
            for (GroupDataV2 sg : splitGroups) {
                sg.setInstructionIndex(instrIdx);
                sg.setInstructionIndices(new ArrayList<>(List.of(instrIdx)));
            }
            list.addAll(splitGroups);
        }

        log.info("\n✅ 数据准备完成，共 " + list.size() + " 个组");

        // 🔥 新增：打印序号组详情到终端
        log.info("\n========== 📋 序号组详情列表 ==========");
        list = mergeAdjacentEquivalentGroups(list);
        log.info("Merged preview groups count: {}", list.size());

        int seq = 1;
        for (GroupDataV2 g : list) {
            // 构建模式字符串
            StringBuilder patternStr = new StringBuilder();
            if (g.getSubRolls() != null) {
                List<Integer> widths = new ArrayList<>(g.getSubRolls().keySet());
                Collections.sort(widths);
                for (int w : widths) {
                    if (patternStr.length() > 0)
                        patternStr.append("+");
                    int count = g.getSubRolls().get(w);
                    if (count > 1) {
                        patternStr.append(w).append("×").append(count);
                    } else {
                        patternStr.append(w);
                    }
                }
            }

            // 计算总宽度
            int totalWidth = 0;
            if (g.getSubRolls() != null) {
                for (Map.Entry<Integer, Integer> e : g.getSubRolls().entrySet()) {
                    totalWidth += e.getKey() * e.getValue();
                }
            }

            log.info("  第" + seq + "组: " + patternStr + "=" + totalWidth + "mm @" + g.getRollWidth()
                    + "mm 次数=" + g.getUsageCount());
            seq++;
        }
        log.info("========== 序号组详情结束 ==========\n");

        return list;
    }

    /**
     * 将一个切割指令拆分为多个分组
     * 逻辑：模拟每一卷的分配，将“内容相同”的卷合并为一组
     */
    private List<GroupDataV2> splitInstructionIntoGroups(CuttingOptimizationResult.CuttingInstruction instr) {
        List<GroupDataV2> result = new ArrayList<>();

        if (instr.getStationAssignments() == null || instr.getStationAssignments().isEmpty()) {
            return result;
        }

        // 1. 按宽度将分配归类到队列中
        Map<Integer, Queue<CuttingOptimizationResult.StationAssignment>> buckets = new HashMap<>();
        for (CuttingOptimizationResult.StationAssignment assign : instr.getStationAssignments()) {
            buckets.computeIfAbsent(assign.getWidth(), k -> new LinkedList<>()).add(assign);
        }

        // 2. 模拟每一卷的组成
        List<List<CuttingOptimizationResult.StationAssignment>> rolls = new ArrayList<>();
        int usageCount = instr.getUsageCount();
        Map<Integer, Integer> subRolls = instr.getSubRolls();

        for (int i = 0; i < usageCount; i++) {
            List<CuttingOptimizationResult.StationAssignment> rollAssignments = new ArrayList<>();
            int missingSlots = 0;

            // 为当前卷的每个槽位分配订单
            for (Map.Entry<Integer, Integer> entry : subRolls.entrySet()) {
                int width = entry.getKey();
                int count = entry.getValue();

                Queue<CuttingOptimizationResult.StationAssignment> bucket = buckets.get(width);
                for (int j = 0; j < count; j++) {
                    if (bucket != null && !bucket.isEmpty()) {
                        rollAssignments.add(bucket.poll());
                    } else {
                        // 🔥 修复：记录缺失但不丢弃整卷
                        missingSlots++;
                    }
                }
            }

            // 🔥 修复：只要有任何分配就保留这卷（避免数据丢失）
            if (!rollAssignments.isEmpty()) {
                rolls.add(rollAssignments);
                if (missingSlots > 0 && i == 0) {
                    // 只在第一次出现时打印警告
                    log.info("⚠️ splitInstructionIntoGroups: 卷#" + (i + 1)
                            + " 缺少 " + missingSlots + " 个槽位的分配，可能是超产或数据不一致");
                }
            }
        }

        // 3. 合并相同的卷
        if (rolls.isEmpty())
            return result;

        List<CuttingOptimizationResult.StationAssignment> currentGroupAssignments = new ArrayList<>(rolls.get(0));
        int currentGroupCount = 1;

        for (int i = 1; i < rolls.size(); i++) {
            List<CuttingOptimizationResult.StationAssignment> currentRoll = rolls.get(i);
            List<CuttingOptimizationResult.StationAssignment> previousRoll = rolls.get(i - 1);

            if (isRollContentSame(currentRoll, previousRoll)) {
                // 内容相同，合并到当前组
                currentGroupAssignments.addAll(currentRoll);
                currentGroupCount++;
            } else {
                // 内容不同，结算当前组
                result.add(createGroupFromAssignments(instr, currentGroupAssignments, currentGroupCount));

                // 开启新组
                currentGroupAssignments = new ArrayList<>(currentRoll);
                currentGroupCount = 1;
            }
        }

        // 结算最后一组
        if (currentGroupCount > 0) {
            result.add(createGroupFromAssignments(instr, currentGroupAssignments, currentGroupCount));
        }

        return result;
    }

    /**
     * 判断两卷的内容是否相同（即由相同的人/订单组成）
     */
    private boolean isRollContentSame(List<CuttingOptimizationResult.StationAssignment> roll1,
            List<CuttingOptimizationResult.StationAssignment> roll2) {
        if (roll1.size() != roll2.size())
            return false;

        // 提取特征键进行比较：Width + MessageText (不含 Salesperson)
        List<String> keys1 = roll1.stream().map(this::getAssignmentKey).sorted().collect(Collectors.toList());
        List<String> keys2 = roll2.stream().map(this::getAssignmentKey).sorted().collect(Collectors.toList());

        return keys1.equals(keys2);
    }

    private String getAssignmentKey(CuttingOptimizationResult.StationAssignment a) {
        if (a.getOrderItem() == null)
            return a.getWidth() + "_null";
        // 🔥 仅使用 Width + MessageText，确保同一搭切模式+同一消息文本视为同一组
        return a.getWidth() + "_" + a.getOrderItem().getMessageText();
    }

    /**
     * 根据分配列表创建 GroupDataV2
     */
    private GroupDataV2 createGroupFromAssignments(CuttingOptimizationResult.CuttingInstruction originalInstr,
            List<CuttingOptimizationResult.StationAssignment> assignments,
            int usageCount) {
        GroupDataV2 g = new GroupDataV2();
        g.setGroupKey(originalInstr.getGroupKey());
        g.setSurfaceTreatment(originalInstr.getSurfaceTreatment());
        g.setLength(originalInstr.getLength());
        g.setThickness(originalInstr.getThickness());
        g.setRollWidth(originalInstr.getRollWidth());
        g.setSubRolls(originalInstr.getSubRolls());
        g.setUsageCount(usageCount); // 使用拆分后的数量
        g.setNewGroup(originalInstr.isNewGroup()); // 传递新组标记

        // 构建 rows (复用原有逻辑)
        Map<String, Map<Integer, List<CuttingOptimizationResult.StationAssignment>>> grouped = new HashMap<>();
        for (CuttingOptimizationResult.StationAssignment assign : assignments) {
            if (assign.getOrderItem() == null)
                continue;
            String messageText = assign.getOrderItem().getMessageText();
            int width = assign.getWidth();
            grouped.computeIfAbsent(messageText, k -> new HashMap<>())
                    .computeIfAbsent(width, k -> new ArrayList<>())
                    .add(assign);
        }

        List<WidthRowV2> rows = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, List<CuttingOptimizationResult.StationAssignment>>> msgEntry : grouped
                .entrySet()) {
            String msg = msgEntry.getKey();
            for (Map.Entry<Integer, List<CuttingOptimizationResult.StationAssignment>> widthEntry : msgEntry.getValue()
                    .entrySet()) {
                List<CuttingOptimizationResult.StationAssignment> assigns = widthEntry.getValue();
                WidthRowV2 wr = new WidthRowV2();
                wr.setMessageText(msg);
                wr.setSalesperson(assigns.get(0).getOrderItem().getSalesperson());
                wr.setWidth(widthEntry.getKey());
                wr.setRolls(assigns.size());
                wr.setLength(assigns.get(0).getOrderItem().getLength());
                // 🔥 修复：stationCount应该是该订单在此宽度上实际分配的数量，而不是模式中该宽度的总系数
                wr.setStationCount(calculateStationCount(wr.getRolls(), usageCount));
                wr.setComboExpanded(expandComboV2(g.getSubRolls()));
                rows.add(wr);
            }
        }

        // 🔥 修复：按宽度升序排序，确保与组合位的视觉顺序一致
        rows.sort(Comparator.comparingInt(WidthRowV2::getWidth));

        g.setRows(rows);
        return g;
    }

    /**
     * V2: 展开组合位
     */
    private List<Integer> expandComboV2(Map<Integer, Integer> subRolls) {
        List<Integer> list = new ArrayList<>();
        List<Integer> sorted = new ArrayList<>(subRolls.keySet());
        Collections.sort(sorted);

        for (Integer w : sorted) {
            int count = subRolls.get(w);
            for (int i = 0; i < count; i++) {
                list.add(w);
            }
        }
        return list;
    }

    /**
     * V2: 填充数据行
     */
    private void fillRowV2(Row row, GroupDataV2 g, WidthRowV2 w, boolean isFirstRow) {

        // B：业务员
        setCellValueSafeV2(row, 1, w.getSalesperson());

        // C：订单信息。APS 回退字段可能是 PSR 前缀订单号，按原文写回。
        setCellValueSafeV2(row, 2, w.getMessageText());

        // H：厚度
        setCellValueSafeV2(row, 7, g.getThickness() > 0 ? String.valueOf(g.getThickness()) : "");

        // I：宽度
        setCellValueSafeV2(row, 8, w.getWidth());

        // J：长度
        setCellValueSafeV2(row, 9, w.getLength());

        // K：纸管内径
        setCellValueSafeV2(row, 10, "6\"");

        // L：卷数
        setCellValueSafeV2(row, 11, w.getRolls());

        // P：工位数量
        setCellValueSafeV2(row, 15, w.getStationCount());

        // AA（分切车数）- 只在首行写入
        if (isFirstRow) {
            setCellValueSafeV2(row, 26, g.getUsageCount());
        }

        // 组合位 R-Y（首行写）
        if (isFirstRow) {
            List<Integer> combo = w.getComboExpanded();
            for (int i = 0; i < combo.size() && i < 8; i++) {
                setCellValueSafeV2(row, 17 + i, combo.get(i));
            }
        }
    }

    /**
     * V2: 清除旧数据
     */
    private void clearOldDataV2(Sheet sheet) {
        // 清除所有合并区域（从后往前删除，避免索引变化）
        int numMerged = sheet.getNumMergedRegions();
        for (int i = numMerged - 1; i >= 0; i--) {
            CellRangeAddress mergedRegion = sheet.getMergedRegion(i);
            // 只清除数据区域的合并（第6行及以后，即索引>=5）
            if (mergedRegion.getFirstRow() >= 5) {
                sheet.removeMergedRegion(i);
            }
        }

        // 清除旧数据行（第6行及以后，即索引>=5）
        int last = sheet.getLastRowNum();
        for (int i = last; i >= 5; i--) {
            Row row = sheet.getRow(i);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
    }

    /**
     * V2: 提取样式
     */
    private Map<Integer, CellStyle> extractStylesV2(Row template) {
        Map<Integer, CellStyle> map = new HashMap<>();
        for (Cell c : template) {
            map.put(c.getColumnIndex(), c.getCellStyle());
        }
        return map;
    }

    /**
     * V2: 提取公式
     */
    private Map<Integer, String> extractFormulasV2(Row template) {
        Map<Integer, String> map = new HashMap<>();
        for (Cell c : template) {
            if (c.getCellType() == CellType.FORMULA) {
                map.put(c.getColumnIndex(), c.getCellFormula());
            }
        }
        return map;
    }

    /**
     * V2: 应用模板行样式和公式
     */
    private void applyTemplateRowV2(Row row, Map<Integer, CellStyle> styles,
            Map<Integer, String> formulas, int offset, int totalWidth) {

        for (Map.Entry<Integer, CellStyle> e : styles.entrySet()) {
            Cell cell = row.createCell(e.getKey());
            cell.setCellStyle(e.getValue());

            if (formulas.containsKey(e.getKey())) {
                String formula = adjustFormulaV2(formulas.get(e.getKey()), offset);
                formula = replaceTemplateWidthConstants(formula, totalWidth);
                cell.setCellFormula(formula);
            }
        }
    }

    static String replaceTemplateWidthConstants(String formula, Integer totalWidth) {
        if (formula == null || totalWidth == null || totalWidth <= 0) {
            return formula;
        }

        String width = String.valueOf(totalWidth);
        return formula.replaceAll("\\b3380\\b", width)
                .replaceAll("\\b4300\\b", width)
                .replaceAll("\\b4400\\b", width)
                .replaceAll("\\b4600\\b", width);
    }

    private int calculateStationCount(int rolls, int usageCount) {
        if (rolls <= 0) {
            return 0;
        }
        if (usageCount <= 0 || rolls % usageCount != 0) {
            return rolls;
        }
        return rolls / usageCount;
    }

    /**
     * V2: 调整公式中的行号
     */
    private String adjustFormulaV2(String formula, int offset) {
        // 使用正则表达式匹配列名+行号的模式（如 A6, AA10）
        StringBuilder result = new StringBuilder();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([A-Z]+)(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(formula);

        while (matcher.find()) {
            String colPart = matcher.group(1);
            int rowNum = Integer.parseInt(matcher.group(2));
            matcher.appendReplacement(result, colPart + (rowNum + offset));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * V2: 合并列
     */
    private void mergeColumnV2(Sheet sheet, int col, int r1, int r2) {
        if (r1 == r2)
            return;

        CellRangeAddress region = new CellRangeAddress(r1, r2, col, col);
        sheet.addMergedRegion(region);

        RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet);
    }

    /**
     * V2: 设置单元格值（简化版）
     */
    private void setCellValueV2(Cell cell, Object value) {
        if (cell == null)
            return;

        if (cell.getCellType() == CellType.FORMULA)
            return; // 不覆盖公式

        if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value == null || "".equals(value)) {
            cell.setBlank();
        } else {
            cell.setCellValue(value.toString());
        }
    }

    /**
     * V2: 安全设置单元格值（自动创建单元格）
     */
    private void setCellValueSafeV2(Row row, int col, Object value) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col);
        }
        setCellValueV2(cell, value);
    }

    // ==================================================================================
    // V2 数据类（简化版，手动 getter/setter）
    // ==================================================================================

    /**
     * V2: 分组数据
     */
    private static class GroupDataV2 {
        private String groupKey;
        private int length;
        private String surfaceTreatment;
        private int thickness;
        private int rollWidth;
        private int usageCount;
        private Map<Integer, Integer> subRolls;
        private List<WidthRowV2> rows;
        private boolean isNewGroup;
        private int instructionIndex = -1;
        private List<Integer> instructionIndices = new ArrayList<>();

        public int getInstructionIndex() {
            return instructionIndex;
        }

        public void setInstructionIndex(int instructionIndex) {
            this.instructionIndex = instructionIndex;
        }

        public List<Integer> getInstructionIndices() {
            return instructionIndices;
        }

        public void setInstructionIndices(List<Integer> instructionIndices) {
            this.instructionIndices = instructionIndices;
        }

        public String getGroupKey() {
            return groupKey;
        }

        public void setGroupKey(String groupKey) {
            this.groupKey = groupKey;
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

        public int getThickness() {
            return thickness;
        }

        public void setThickness(int thickness) {
            this.thickness = thickness;
        }

        public int getRollWidth() {
            return rollWidth;
        }

        public void setRollWidth(int rollWidth) {
            this.rollWidth = rollWidth;
        }

        public int getUsageCount() {
            return usageCount;
        }

        public void setUsageCount(int usageCount) {
            this.usageCount = usageCount;
        }

        public Map<Integer, Integer> getSubRolls() {
            return subRolls;
        }

        public void setSubRolls(Map<Integer, Integer> subRolls) {
            this.subRolls = subRolls;
        }

        public List<WidthRowV2> getRows() {
            return rows;
        }

        public boolean isNewGroup() {
            return isNewGroup;
        }

        public void setNewGroup(boolean isNewGroup) {
            this.isNewGroup = isNewGroup;
        }

        public void setRows(List<WidthRowV2> rows) {
            this.rows = rows;
        }
    }

    /**
     * V2: 宽度行数据
     */
    private static class WidthRowV2 {
        private String messageText;
        private String salesperson;
        private int width;
        private int rolls;
        private int length;
        private int stationCount;
        private List<Integer> comboExpanded;

        public String getMessageText() {
            return messageText;
        }

        public void setMessageText(String messageText) {
            this.messageText = messageText;
        }

        public String getSalesperson() {
            return salesperson;
        }

        public void setSalesperson(String salesperson) {
            this.salesperson = salesperson;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getRolls() {
            return rolls;
        }

        public void setRolls(int rolls) {
            this.rolls = rolls;
        }

        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            this.length = length;
        }

        public int getStationCount() {
            return stationCount;
        }

        public void setStationCount(int stationCount) {
            this.stationCount = stationCount;
        }

        public List<Integer> getComboExpanded() {
            return comboExpanded;
        }

        public void setComboExpanded(List<Integer> comboExpanded) {
            this.comboExpanded = comboExpanded;
        }
    }
}
