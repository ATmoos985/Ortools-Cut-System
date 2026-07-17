package test.demo.apsmodule.generator.NewSolver.config;

import java.util.Map;
import java.util.function.Supplier;

import test.demo.apsmodule.solver.kernel.execution.SolverExecutionContext;

/**
 * Request-scoped solver property overrides.
 *
 * <p>System properties are still honored as the process-wide default, but API
 * callers can set quality/LNS/SPR flags for one solve without leaking them into
 * concurrent requests.
 */
public final class SolverRuntimeProperties {

    private SolverRuntimeProperties() {
    }

    public static <T> T withOverrides(Map<String, String> overrides, Supplier<T> supplier) {
        if (overrides == null || overrides.isEmpty()) {
            return supplier.get();
        }
        SolverExecutionContext merged = SolverExecutionContext.current().withOverrides(overrides);
        return SolverExecutionContext.callWith(merged, supplier);
    }

    public static <T> T withContext(SolverExecutionContext context, Supplier<T> supplier) {
        return SolverExecutionContext.callWith(context, supplier);
    }

    public static SolverExecutionContext captureContext() {
        return SolverExecutionContext.current();
    }

    public static String get(String key) {
        String override = SolverExecutionContext.current().get(key);
        if (override != null) {
            return override;
        }
        return System.getProperty(key);
    }

    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : value;
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    public static int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static long getLong(String key, long defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static double getDouble(String key, double defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
