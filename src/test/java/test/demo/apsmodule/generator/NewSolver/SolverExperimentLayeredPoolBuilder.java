package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Builds deterministic, nested column pools for the frozen t9 one-car experiment. */
final class SolverExperimentLayeredPoolBuilder {

    private SolverExperimentLayeredPoolBuilder() {
    }

    record Layer(String name, List<Column> columns, String hash) {
    }

    static List<Layer> build(List<ColumnUse> incumbent,
            List<Column> historicalCandidates,
            List<Column> archive,
            Map<String, Integer> demand,
            int... relatedCaps) {
        List<Column> incumbentColumns = unique(
                incumbent.stream().map(ColumnUse::column).toList());
        List<Column> candidateUnion = unique(concat(incumbentColumns, historicalCandidates));
        List<Column> fullPool = unique(concat(candidateUnion, archive));

        Set<String> targetKeys = new TreeSet<>();
        incumbent.stream().filter(use -> use.count() == 1)
                .forEach(use -> targetKeys.addAll(use.column().demandUse().keySet()));
        Set<String> neighborKeys = new TreeSet<>(targetKeys);
        for (Column column : fullPool) {
            if (overlap(column, targetKeys) > 0) {
                neighborKeys.addAll(column.demandUse().keySet());
            }
        }

        List<Column> ranked = new ArrayList<>(fullPool);
        ranked.sort(Comparator
                .<Column>comparingInt(column -> overlap(column, targetKeys)).reversed()
                .thenComparing(Comparator
                        .comparingInt((Column column) -> overlap(column, neighborKeys)).reversed())
                .thenComparing(Comparator
                        .comparingInt((Column column) -> support(column, demand)).reversed())
                .thenComparing(Comparator.comparingInt(Column::patternWidth).reversed())
                .thenComparing(Column::signature));

        List<Layer> layers = new ArrayList<>();
        layers.add(layer("L0-incumbent", incumbentColumns));
        layers.add(layer("L1-candidate-union", candidateUnion));
        Set<Integer> seenCaps = new LinkedHashSet<>();
        for (int cap : relatedCaps) {
            if (cap <= 0 || !seenCaps.add(cap)) {
                continue;
            }
            layers.add(layer("L2-related-" + cap,
                    select(candidateUnion, ranked, cap)));
        }
        layers.add(layer("L3-full-archive", fullPool));
        return List.copyOf(layers);
    }

    private static List<Column> select(List<Column> required, List<Column> ranked, int cap) {
        Map<String, Column> selected = new LinkedHashMap<>();
        required.forEach(column -> selected.putIfAbsent(column.signature(), column));
        for (Column column : ranked) {
            if (selected.size() >= cap) {
                break;
            }
            selected.putIfAbsent(column.signature(), column);
        }
        return unique(new ArrayList<>(selected.values()));
    }

    private static Layer layer(String name, List<Column> columns) {
        List<Column> stable = unique(columns);
        return new Layer(name, stable, hash(stable));
    }

    private static List<Column> unique(List<Column> columns) {
        Map<String, Column> unique = new LinkedHashMap<>();
        columns.stream().sorted(Comparator.comparing(Column::signature))
                .forEach(column -> unique.putIfAbsent(column.signature(), column));
        return List.copyOf(unique.values());
    }

    private static List<Column> concat(List<Column> first, List<Column> second) {
        List<Column> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static int overlap(Column column, Set<String> keys) {
        int overlap = 0;
        for (String key : column.demandUse().keySet()) {
            if (keys.contains(key)) {
                overlap++;
            }
        }
        return overlap;
    }

    private static int support(Column column, Map<String, Integer> demand) {
        int support = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> use : column.demandUse().entrySet()) {
            support = Math.min(support,
                    demand.getOrDefault(use.getKey(), 0) / Math.max(1, use.getValue()));
        }
        return support == Integer.MAX_VALUE ? 0 : support;
    }

    private static String hash(List<Column> columns) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Column column : columns) {
                digest.update(column.signature().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
