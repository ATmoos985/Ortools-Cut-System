package test.demo.apsmodule.generator.NewSolver.config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Request-scoped solver property overrides.
 *
 * <p>System properties are still honored as the process-wide default, but API
 * callers can set quality/LNS/SPR flags for one solve without leaking them into
 * concurrent requests.
 */
public final class SolverRuntimeProperties {

    private static final ThreadLocal<Map<String, String>> OVERRIDES = new ThreadLocal<>();

    private SolverRuntimeProperties() {
    }

    public static <T> T withOverrides(Map<String, String> overrides, Supplier<T> supplier) {
        if (overrides == null || overrides.isEmpty()) {
            return supplier.get();
        }
        Map<String, String> previous = OVERRIDES.get();
        Map<String, String> merged = new HashMap<>();
        if (previous != null) {
            merged.putAll(previous);
        }
        merged.putAll(overrides);
        OVERRIDES.set(Map.copyOf(merged));
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                OVERRIDES.remove();
            } else {
                OVERRIDES.set(previous);
            }
        }
    }

    public static String get(String key) {
        Map<String, String> overrides = OVERRIDES.get();
        if (overrides != null && overrides.containsKey(key)) {
            return overrides.get(key);
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
