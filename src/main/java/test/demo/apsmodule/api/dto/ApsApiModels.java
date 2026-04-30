package test.demo.apsmodule.api.dto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApsApiModels {

    private ApsApiModels() {
    }

    public record ApiResponse<T>(T data, Map<String, Object> meta) {
    }

    public record ApiErrorResponse(ApiError error) {
    }

    public record ApiError(String code, String message, List<FieldError> details) {
    }

    public record FieldError(String field, String message) {
    }

    public static <T> ApiResponse<T> success(T data, String requestId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (requestId != null && !requestId.isBlank()) {
            meta.put("requestId", requestId);
        }
        meta.put("generatedAt", Instant.now().toString());
        return new ApiResponse<>(data, meta);
    }

    public static ApiErrorResponse error(String code, String message) {
        return error(code, message, List.of());
    }

    public static ApiErrorResponse error(String code, String message, List<FieldError> details) {
        return new ApiErrorResponse(new ApiError(code, message, details));
    }
}
