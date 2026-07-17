package test.demo.apsmodule.solver.kernel.execution;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable request-owned properties that can be installed explicitly on worker threads.
 */
public record SolverExecutionContext(Map<String, String> properties) {

    private static final SolverExecutionContext EMPTY = new SolverExecutionContext(Map.of());
    private static final ThreadLocal<SolverExecutionContext> CURRENT = new ThreadLocal<>();

    public SolverExecutionContext {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static SolverExecutionContext empty() {
        return EMPTY;
    }

    public static SolverExecutionContext of(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return EMPTY;
        }
        return new SolverExecutionContext(properties);
    }

    public static SolverExecutionContext current() {
        SolverExecutionContext context = CURRENT.get();
        return context == null ? EMPTY : context;
    }

    public SolverExecutionContext withOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new HashMap<>(properties);
        merged.putAll(overrides);
        return new SolverExecutionContext(merged);
    }

    public String get(String key) {
        return properties.get(key);
    }

    public boolean isEmpty() {
        return properties.isEmpty();
    }

    public static <T> T callWith(SolverExecutionContext context, Supplier<T> supplier) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(supplier, "supplier");
        SolverExecutionContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
