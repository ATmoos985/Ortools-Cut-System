package test.demo.apsmodule.service.excel;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelImportServiceTest {

    @Test
    void parseExcelFileWithSourceDetectsApsTemplateAndMapsFields() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        var sheet = workbook.createSheet("分卷收集明细");
        writeRow(sheet.createRow(0),
                "*业务员编号", "*业务员姓名", "*物料编号", "物料名称", "型号",
                "*最终数量", "*最终期望交期", "物料组", "单位", "出货型号",
                "*厚度(μm)", "*宽度(mm)", "*长度(m)", "*卷数", "*包装形式",
                "涂布类型", "表面处理", "客户名称", "客户编号", "备注",
                "来源销售分卷ID", "分卷收集订单号");
        writeRow(sheet.createRow(1),
                "03354", "杨传志", "30012543", "反射膜_T10_ESY_188_990_1350_B2-61A1D", "T10_ESY188",
                66825, "2026-06-01 00:00:00", "31001", "M2", "SDY188",
                188, "990.000000", 1350, 50, "B2-61A1D",
                "", "不电晕/不涂布", "广东瑞捷新材料股份有限公司", "10031", "",
                "2066782195533422594", "2090446662663374409");

        ExcelImportService.ParseResult result = new ExcelImportService()
                .parseExcelFileWithSource(workbookFile(workbook));
        List<ExcelImportService.OrderItem> items = result.getOrderItems();
        ExcelImportService.OrderItem item = items.get(0);

        assertEquals("aps", result.getTemplateType());
        assertEquals("APS模板", result.getTemplateSource());
        assertEquals(1, items.size());
        assertEquals("2066782195533422594", item.getMessageText());
        assertEquals("杨传志", item.getSalesperson());
        assertEquals("30012543", item.getMaterialCode());
        assertEquals("反射膜_T10_ESY_188_990_1350_B2-61A1D", item.getDescription());
        assertEquals(990, item.getWidth());
        assertEquals(1350, item.getLength());
        assertEquals(50, item.getQuantity());
        assertEquals("不电晕/不涂布", item.getSurfaceTreatment());
        assertEquals(188, item.getThickness());
        assertEquals(66825, item.getDemandQuantity());
        assertEquals("M2", item.getUnit());
        assertEquals("SDY188", item.getShippingModel());
        assertEquals("广东瑞捷新材料股份有限公司", item.getCustomerName());
        assertEquals("10031", item.getCustomerCode());
        assertEquals("2026-06-01 00:00:00", item.getDeliveryDate());
    }

    @Test
    void parseExcelFileWithSourceKeepsOfflineTemplateCompatible() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        var sheet = workbook.createSheet("订单导入");
        writeRow(sheet.createRow(0), "消息文本", "宽度mm", "卷数", "长度m", "表面处理", "业务员");
        writeRow(sheet.createRow(1), "MSG-1", 1200, 8, 1350, "不电晕/不涂布", "张三");

        ExcelImportService.ParseResult result = new ExcelImportService()
                .parseExcelFileWithSource(workbookFile(workbook));
        ExcelImportService.OrderItem item = result.getOrderItems().get(0);

        assertEquals("offline", result.getTemplateType());
        assertEquals("线下模板", result.getTemplateSource());
        assertEquals("MSG-1", item.getMessageText());
        assertEquals(1200, item.getWidth());
        assertEquals(8, item.getQuantity());
        assertEquals(1350, item.getLength());
        assertEquals("张三", item.getSalesperson());
    }

    private static void writeRow(Row row, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Number number) {
                row.createCell(i).setCellValue(number.doubleValue());
            } else {
                row.createCell(i).setCellValue(String.valueOf(values[i]));
            }
        }
    }

    private static MockMultipartFile workbookFile(Workbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();
        return new MockMultipartFile(
                "file",
                "import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray());
    }
}
