package test.demo.apsmodule.generator.NewSolver.pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministically enumerates every feasible pattern for a quality solve.
 *
 * <p>The regular {@link PatternGenerator} remains the bounded fast candidate
 * source. This enumerator is intentionally complete inside the configured
 * width, station and distinct-width limits so quality diagnostics can prove
 * whether candidate coverage is the limiting stage.</p>
 */
public final class CompletePatternEnumerator {

    private static final Logger log = LoggerFactory.getLogger(CompletePatternEnumerator.class);

    private final SolverParameters params;
    private final int maxDistinctWidths;

    public CompletePatternEnumerator(SolverParameters params, int maxDistinctWidths) {
        this.params = params.copy();
        this.params.sanitize();
        this.maxDistinctWidths = Math.max(1, maxDistinctWidths);
    }

    public List<PatternCandidate> generate(Map<Integer, Integer> demands) {
        long startedAt = System.currentTimeMillis();
        List<Integer> widths = demands.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (widths.isEmpty()) {
            return List.of();
        }

        int maxStations = params.getMaxRollWidth() / widths.get(0);
        List<PatternCandidate> patterns = new ArrayList<>();
        enumerate(widths, 0, 0, 0, maxStations, new TreeMap<>(), patterns);

        Map<Integer, Integer> distinctDistribution = new TreeMap<>();
        Map<Integer, Integer> stationDistribution = new TreeMap<>();
        for (PatternCandidate pattern : patterns) {
            distinctDistribution.merge(pattern.getWidthCount(), 1, Integer::sum);
            int stations = pattern.getPattern().values().stream().mapToInt(Integer::intValue).sum();
            stationDistribution.merge(stations, 1, Integer::sum);
        }
        log.info("Complete pattern enumeration: patterns={}, elapsedMs={}, maxStations={}, "
                        + "maxDistinct={}, distinctDistribution={}, stationDistribution={}",
                patterns.size(),
                System.currentTimeMillis() - startedAt,
                maxStations,
                maxDistinctWidths,
                distinctDistribution,
                stationDistribution);
        return patterns;
    }

    private void enumerate(
            List<Integer> widths,
            int startIndex,
            int patternWidth,
            int stations,
            int maxStations,
            TreeMap<Integer, Integer> counts,
            List<PatternCandidate> patterns) {
        if (patternWidth >= params.getMinRollWidth()) {
            int rollWidth = ceilToStep(patternWidth, params.getStepSize());
            if (rollWidth <= params.getMaxRollWidth()) {
                patterns.add(new PatternCandidate(counts, rollWidth));
            }
        }
        if (stations >= maxStations) {
            return;
        }

        for (int index = startIndex; index < widths.size(); index++) {
            int width = widths.get(index);
            int nextPatternWidth = patternWidth + width;
            if (nextPatternWidth > params.getMaxRollWidth()) {
                break;
            }
            boolean existingWidth = counts.containsKey(width);
            if (!existingWidth && counts.size() >= maxDistinctWidths) {
                continue;
            }

            counts.merge(width, 1, Integer::sum);
            enumerate(
                    widths,
                    index,
                    nextPatternWidth,
                    stations + 1,
                    maxStations,
                    counts,
                    patterns);
            if (counts.get(width) == 1) {
                counts.remove(width);
            } else {
                counts.put(width, counts.get(width) - 1);
            }
        }
    }

    private int ceilToStep(int value, int step) {
        return ((value + step - 1) / step) * step;
    }
}
