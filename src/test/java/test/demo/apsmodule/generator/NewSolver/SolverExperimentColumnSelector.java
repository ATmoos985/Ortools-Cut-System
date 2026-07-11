package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable shadow-only pool reduction that always preserves the incumbent columns. */
final class SolverExperimentColumnSelector {

    private SolverExperimentColumnSelector() {
    }

    static List<Column> select(List<Column> pool, List<Column> incumbent,
            Map<String, Integer> demand, int maxColumns, int columnsPerDemandKey) {
        Map<String, Column> unique = new LinkedHashMap<>();
        pool.stream().sorted(Comparator.comparing(Column::signature))
                .forEach(column -> unique.putIfAbsent(column.signature(), column));
        if (maxColumns <= 0 || unique.size() <= maxColumns) {
            return List.copyOf(unique.values());
        }

        Map<String, Column> selected = new LinkedHashMap<>();
        incumbent.stream().sorted(Comparator.comparing(Column::signature))
                .forEach(column -> selected.putIfAbsent(column.signature(), column));
        List<Column> ranked = new ArrayList<>(unique.values());
        ranked.sort(Comparator
                .<Column>comparingInt(column -> support(column, demand)).reversed()
                .thenComparing(Comparator.comparingInt(Column::patternWidth).reversed())
                .thenComparing(Column::signature));

        int perKey = Math.max(0, columnsPerDemandKey);
        for (String demandKey : demand.keySet().stream().sorted().toList()) {
            int added = 0;
            for (Column column : ranked) {
                if (selected.size() >= maxColumns) {
                    break;
                }
                if (!selected.containsKey(column.signature())
                        && column.demandUse().containsKey(demandKey)) {
                    selected.put(column.signature(), column);
                    added++;
                    if (added >= perKey) {
                        break;
                    }
                }
            }
        }
        for (Column column : ranked) {
            if (selected.size() >= maxColumns) {
                break;
            }
            selected.putIfAbsent(column.signature(), column);
        }
        return List.copyOf(selected.values());
    }

    private static int support(Column column, Map<String, Integer> demand) {
        int support = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> use : column.demandUse().entrySet()) {
            support = Math.min(support,
                    demand.getOrDefault(use.getKey(), 0) / Math.max(1, use.getValue()));
        }
        return support == Integer.MAX_VALUE ? 0 : support;
    }
}
