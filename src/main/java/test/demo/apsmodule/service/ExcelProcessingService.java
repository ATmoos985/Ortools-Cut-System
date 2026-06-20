package test.demo.apsmodule.service;

import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.excel.ExcelImportService;
import test.demo.apsmodule.service.excel.ExcelExportService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Excel文件处理服务
 * 负责协调Excel导入和导出功能
 */
@Service
public class ExcelProcessingService {
    
    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;
    
    public ExcelProcessingService(ExcelImportService excelImportService, ExcelExportService excelExportService) {
        this.excelImportService = excelImportService;
        this.excelExportService = excelExportService;
    }
    
    /**
     * 解析Excel文件（支持Excel和CSV）
     */
    public List<ExcelImportService.OrderItem> parseExcelFile(MultipartFile file) throws IOException {
        return excelImportService.parseExcelFile(file);
    }

    public ExcelImportService.ParseResult parseExcelFileWithSource(MultipartFile file) throws IOException {
        return excelImportService.parseExcelFileWithSource(file);
    }
    
    /**
     * 导出优化结果为Excel文件
     */
    public String exportOptimizationResult(
            CuttingOptimizationResult result,
            String fileName) throws IOException {
        return excelExportService.exportOptimizationResult(result, fileName);
    }
    
    /**
     * 导出验证报告为Excel文件
     */
    public String exportValidationReport(
            java.util.Map<String, Object> validationResult,
            String fileName) throws IOException {
        return excelExportService.exportValidationReport(validationResult, fileName);
    }
    
    /**
     * 解析Excel文件并返回列映射信息
     */
    public java.util.Map<String, Object> parseExcelFileWithMapping(MultipartFile file) throws IOException {
        return excelImportService.parseExcelFileWithMapping(file);
    }
}
