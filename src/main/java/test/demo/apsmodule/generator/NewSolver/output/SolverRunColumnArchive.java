package test.demo.apsmodule.generator.NewSolver.output;

import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.service.CuttingInstruction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Opt-in request-scoped capture of executable columns discovered by the solver pipeline.
 * Production calls pay only a ThreadLocal lookup; experiment harnesses explicitly open a capture.
 */
public final class SolverRunColumnArchive {

    private static final ThreadLocal<Collector> ACTIVE = new ThreadLocal<>();

    private SolverRunColumnArchive() {
    }

    public record Captured<T>(T value, List<Column> columns) {
    }

    public static boolean isCaptureActive() {
        return ACTIVE.get() != null;
    }

    public static <T> Captured<T> capture(Supplier<T> operation) {
        Collector parent = ACTIVE.get();
        Collector collector = new Collector();
        ACTIVE.set(collector);
        try {
            T value = operation.get();
            List<Column> columns = collector.snapshot();
            if (parent != null) {
                parent.addAll(columns);
            }
            return new Captured<>(value, columns);
        } finally {
            if (parent == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(parent);
            }
        }
    }

    static void recordInstructions(List<CuttingInstruction> instructions) {
        Collector collector = ACTIVE.get();
        if (collector == null || instructions == null || instructions.isEmpty()) {
            return;
        }
        List<ColumnUse> uses = SetPartitionRefiner.extractColumnUses(instructions);
        if (uses != null) {
            collector.addAll(uses.stream().map(ColumnUse::column).toList());
        }
    }

    static void recordColumns(Iterable<Column> columns) {
        Collector collector = ACTIVE.get();
        if (collector != null && columns != null) {
            collector.addAll(columns);
        }
    }

    private static final class Collector {
        private final ConcurrentMap<String, Column> bySignature = new ConcurrentHashMap<>();

        private void addAll(Iterable<Column> columns) {
            for (Column column : columns) {
                if (column != null) {
                    bySignature.putIfAbsent(column.signature(), column);
                }
            }
        }

        private List<Column> snapshot() {
            List<Column> columns = new ArrayList<>(bySignature.values());
            columns.sort(Comparator.comparing(Column::signature));
            return List.copyOf(columns);
        }
    }
}
