package test.demo.rest.dto;

import java.util.Map;

public class ValidationReportRequest {
    private Map<String, Object> validationResult;
    private String fileName;

    public Map<String, Object> getValidationResult() {
        return validationResult;
    }

    public void setValidationResult(Map<String, Object> validationResult) {
        this.validationResult = validationResult;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
}
