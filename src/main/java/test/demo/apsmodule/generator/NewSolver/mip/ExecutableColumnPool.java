package test.demo.apsmodule.generator.NewSolver.mip;

import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes executable columns before they enter the strict research master.
 * Every rejection is feasibility-preserving for the supplied demand and caps.
 */
public final class ExecutableColumnPool {

    private ExecutableColumnPool() {
    }

    public record Stats(int inputColumns,
                        int retainedColumns,
                        int duplicateColumns,
                        int unsupportedColumns,
                        int invalidWidthColumns,
                        int overWasteCapColumns) {

        public int rejectedColumns() {
            return inputColumns - retainedColumns;
        }
    }

    public record Normalized(List<Column> columns, Stats stats) {
    }

    public static Normalized normalize(List<Column> pool,
            Map<String, Integer> demand,
            int wasteCap,
            int totalWidth) {
        Map<String, Column> bySignature = new LinkedHashMap<>();
        int duplicates = 0;
        int unsupported = 0;
        int invalidWidth = 0;
        int overWasteCap = 0;

        for (Column column : pool) {
            if (!hasConsistentPhysicalWidth(column, totalWidth)) {
                invalidWidth++;
                continue;
            }
            if (supportOf(column, demand) < 1) {
                unsupported++;
                continue;
            }
            if (totalWidth - column.patternWidth() > wasteCap) {
                overWasteCap++;
                continue;
            }
            if (bySignature.putIfAbsent(column.signature(), column) != null) {
                duplicates++;
            }
        }

        List<Column> columns = new ArrayList<>(bySignature.values());
        columns.sort(Comparator.comparing(Column::signature));
        return new Normalized(
                List.copyOf(columns),
                new Stats(pool.size(), columns.size(), duplicates, unsupported,
                        invalidWidth, overWasteCap));
    }

    private static boolean hasConsistentPhysicalWidth(Column column, int totalWidth) {
        if (column.patternWidth() < 0 || column.patternWidth() > totalWidth) {
            return false;
        }
        int calculatedWidth = column.pattern().entrySet().stream()
                .mapToInt(entry -> entry.getKey() * entry.getValue())
                .sum();
        if (calculatedWidth != column.patternWidth()) {
            return false;
        }
        for (Map.Entry<Integer, Integer> entry : column.pattern().entrySet()) {
            if (entry.getKey() <= 0 || entry.getValue() <= 0
                    || column.config().getOrDefault(entry.getKey(), List.of()).size()
                    != entry.getValue()) {
                return false;
            }
        }
        return column.config().keySet().equals(column.pattern().keySet());
    }

    private static int supportOf(Column column, Map<String, Integer> demand) {
        int support = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> use : column.demandUse().entrySet()) {
            Integer quantity = demand.get(use.getKey());
            if (quantity == null || use.getValue() <= 0 || quantity < use.getValue()) {
                return 0;
            }
            support = Math.min(support, quantity / use.getValue());
        }
        return support == Integer.MAX_VALUE ? 0 : support;
    }
}
