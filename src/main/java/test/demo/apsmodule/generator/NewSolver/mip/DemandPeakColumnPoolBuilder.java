package test.demo.apsmodule.generator.NewSolver.mip;

import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one executable-column pool around demand-supported pattern-width peaks.
 *
 * <p>This is deliberately a pool-construction policy, not an extra objective in
 * the master MIP. The master still decides groups/odd/one-car/small under the
 * exact car-count and waste constraints. Center-outward exploration only decides
 * which message-aware blocks deserve scarce column-pool capacity.</p>
 */
public final class DemandPeakColumnPoolBuilder {

    public record Config(int maxMessagesPerWidth,
                         int maxOptionsPerWidth,
                         int maxConfigsPerPattern,
                         int maxColumnsPerDistanceBand,
                         int peakCount,
                         int peakSeparationSteps,
                         int stepSize) {

        public Config {
            if (maxMessagesPerWidth < 1 || maxOptionsPerWidth < 1 || maxConfigsPerPattern < 1
                    || maxColumnsPerDistanceBand < 1 || peakCount < 1
                    || peakSeparationSteps < 0 || stepSize < 1) {
                throw new IllegalArgumentException("Demand-peak pool configuration must be positive");
            }
        }

        public static Config defaults(int stepSize) {
            return new Config(3, 12, 96, 96, 2, 3, stepSize);
        }
    }

    public record Peak(int patternWidth, long supportScore) {
    }

    public record Stats(int generatedColumns,
                        int protectedColumns,
                        int retainedColumns,
                        int distanceBands) {
    }

    public record Result(List<Column> columns, List<Peak> peaks, Stats stats) {
    }

    /**
     * Generates message-aware columns from width-only shapes and then retains a
     * center-outward, diversity-preserving subset in one pass.
     */
    public Result build(Collection<Map<Integer, Integer>> shapes,
                        Map<Integer, Map<String, Integer>> demandByWidth,
                        Collection<Column> protectedColumns,
                        Config config) {
        List<Column> generated = UnifiedSetPartitionSolver.structuredColumns(
                shapes,
                demandByWidth,
                config.maxMessagesPerWidth(),
                config.maxOptionsPerWidth(),
                config.maxConfigsPerPattern());
        return select(generated, flattenDemand(demandByWidth), protectedColumns, config);
    }

    /**
     * Selects from already generated executable columns. Exposed for experiment
     * harnesses so a pool can be scored without regenerating its columns.
     */
    public Result select(Collection<Column> generatedColumns,
                         Map<String, Integer> demand,
                         Collection<Column> protectedColumns,
                         Config config) {
        Map<String, Column> protectedBySignature = bySignature(protectedColumns);
        Map<String, Column> candidates = bySignature(generatedColumns);
        candidates.keySet().removeAll(protectedBySignature.keySet());

        Map<Integer, Long> widthScores = new LinkedHashMap<>();
        for (Column column : candidates.values()) {
            widthScores.merge(column.patternWidth(), score(column, demand), Long::sum);
        }
        List<Peak> peaks = selectPeaks(widthScores, config);

        List<Column> ranked = new ArrayList<>(candidates.values());
        ranked.sort(Comparator
                .comparingInt((Column column) -> distanceBand(column.patternWidth(), peaks, config))
                .thenComparing(Comparator.comparingLong((Column column) -> score(column, demand)).reversed())
                .thenComparing(Column::signature));

        Map<Integer, Integer> retainedByBand = new LinkedHashMap<>();
        Map<String, Column> retained = new LinkedHashMap<>(protectedBySignature);
        for (Column column : ranked) {
            int band = distanceBand(column.patternWidth(), peaks, config);
            if (retainedByBand.getOrDefault(band, 0) >= config.maxColumnsPerDistanceBand()) {
                continue;
            }
            retained.putIfAbsent(column.signature(), column);
            retainedByBand.merge(band, 1, Integer::sum);
        }

        List<Column> ordered = new ArrayList<>(retained.values());
        ordered.sort(Comparator.comparing(Column::signature));
        return new Result(List.copyOf(ordered), peaks,
                new Stats(candidates.size(), protectedBySignature.size(), ordered.size(), retainedByBand.size()));
    }

    private static List<Peak> selectPeaks(Map<Integer, Long> widthScores, Config config) {
        List<Map.Entry<Integer, Long>> ranked = new ArrayList<>(widthScores.entrySet());
        ranked.sort(Map.Entry.<Integer, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        List<Peak> peaks = new ArrayList<>();
        for (Map.Entry<Integer, Long> candidate : ranked) {
            boolean separated = peaks.stream().allMatch(peak ->
                    Math.abs(peak.patternWidth() - candidate.getKey())
                            >= config.peakSeparationSteps() * config.stepSize());
            if (separated) {
                peaks.add(new Peak(candidate.getKey(), candidate.getValue()));
            }
            if (peaks.size() == config.peakCount()) {
                break;
            }
        }
        return List.copyOf(peaks);
    }

    private static int distanceBand(int patternWidth, List<Peak> peaks, Config config) {
        if (peaks.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int distance = peaks.stream()
                .mapToInt(peak -> Math.abs(patternWidth - peak.patternWidth()))
                .min()
                .orElse(Integer.MAX_VALUE);
        return distance / config.stepSize();
    }

    /**
     * High support, exact exhaustion and even reuse all make a column more likely
     * to become a large, non-odd sequence group before any repair phase runs.
     */
    private static long score(Column column, Map<String, Integer> demand) {
        int support = supportOf(column, demand);
        if (support < 1) {
            return Long.MIN_VALUE / 4;
        }
        int exactExhaustions = 0;
        for (Map.Entry<String, Integer> use : column.demandUse().entrySet()) {
            if (demand.getOrDefault(use.getKey(), 0) == support * use.getValue()) {
                exactExhaustions++;
            }
        }
        return support * 1_000L + exactExhaustions * 100L + (support % 2 == 0 ? 10L : 0L);
    }

    private static int supportOf(Column column, Map<String, Integer> demand) {
        int support = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> use : column.demandUse().entrySet()) {
            int available = demand.getOrDefault(use.getKey(), 0);
            if (use.getValue() < 1 || available < use.getValue()) {
                return 0;
            }
            support = Math.min(support, available / use.getValue());
        }
        return support == Integer.MAX_VALUE ? 0 : support;
    }

    private static Map<String, Integer> flattenDemand(Map<Integer, Map<String, Integer>> demandByWidth) {
        Map<String, Integer> flat = new LinkedHashMap<>();
        demandByWidth.forEach((width, messages) -> messages.forEach((message, demand) ->
                flat.put(width + "|" + message, demand)));
        return flat;
    }

    private static Map<String, Column> bySignature(Collection<Column> columns) {
        Map<String, Column> result = new LinkedHashMap<>();
        if (columns != null) {
            for (Column column : columns) {
                if (column != null) {
                    result.putIfAbsent(column.signature(), column);
                }
            }
        }
        return result;
    }
}
