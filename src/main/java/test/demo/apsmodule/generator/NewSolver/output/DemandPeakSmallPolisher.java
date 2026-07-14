package test.demo.apsmodule.generator.NewSolver.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakColumnPoolBuilder;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * One-shot residual-column polish after demand-peak LNS.
 *
 * <p>Only one small target and at most three related donor blocks enter the local MIP; every
 * other block remains fixed. Released blocks are an exact feasible warm start. The local
 * solution must reduce small blocks without worsening group, odd or one-car caps, then pass
 * full-plan conservation checks. Every timeout or non-improving result is a normal fallback.</p>
 */
public final class DemandPeakSmallPolisher {

    private static final Logger log = LoggerFactory.getLogger(DemandPeakSmallPolisher.class);
    private static final long DEFAULT_TIME_LIMIT_MS = 3_000L;
    private static final int SMALL_MAX_CARS = 5;

    private final SolverParameters params;

    public DemandPeakSmallPolisher(SolverParameters params) {
        this.params = Objects.requireNonNull(params, "params").copy();
        this.params.sanitize();
    }

    public static boolean isEnabled() {
        return SolverRuntimeProperties.getBoolean(
                "cutting.demandPeak.smallPolish.enabled", true);
    }

    public PolishResult polish(List<CuttingInstruction> instructions,
            List<SolverOrderItem> groupItems,
            Collection<Column> demandPeakColumns) {
        long startedAt = System.currentTimeMillis();
        if (!isEnabled()) {
            return fallback(instructions, "disabled", startedAt, null, 0, 0);
        }
        if (instructions == null || instructions.isEmpty()
                || groupItems == null || groupItems.isEmpty()) {
            return fallback(instructions, "empty-input", startedAt, null, 0, 0);
        }

        SequenceGroupPostProcessor.GroupStats beforeStats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);
        long timeLimitMs = SolverRuntimeProperties.getLong(
                "cutting.demandPeak.smallPolish.timeMs", DEFAULT_TIME_LIMIT_MS);
        if (timeLimitMs < 1) {
            return fallback(instructions, "no-time-budget", startedAt, beforeStats, 0, 0);
        }
        long deadline = startedAt + timeLimitMs;

        List<ColumnUse> currentUses = SetPartitionRefiner.extractColumnUses(instructions);
        if (currentUses == null || currentUses.isEmpty()) {
            return fallback(instructions, "unreconstructible-input", startedAt,
                    beforeStats, 0, 0);
        }
        int maxDonors = Math.min(3,
                positiveInt("cutting.demandPeak.smallPolish.maxDonors", 3));
        Neighborhood neighborhood = selectNeighborhood(currentUses, maxDonors);
        if (neighborhood == null) {
            return fallback(instructions, "no-small-blocks", startedAt,
                    beforeStats, 0, 0);
        }

        Map<String, Integer> demand = demandOf(groupItems);
        if (!demand.equals(producedBy(currentUses))) {
            return fallback(instructions, "input-demand-mismatch", startedAt,
                    beforeStats, 0, 0);
        }

        List<ColumnUse> releasedUses = neighborhood.uses();
        Set<String> releasedSignatures = releasedUses.stream()
                .map(use -> use.column().signature())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ColumnUse> fixedUses = currentUses.stream()
                .filter(use -> !releasedSignatures.contains(use.column().signature()))
                .toList();
        Map<String, Integer> localDemand = producedBy(releasedUses);

        Map<Integer, Map<String, Integer>> residualDemand =
                demandByWidth(releasedUses);
        List<Map<Integer, Integer>> shapes = collectResidualShapes(
                demandPeakColumns, currentUses, residualDemand.keySet());
        if (shapes.isEmpty()) {
            return fallback(instructions, "no-residual-shapes", startedAt,
                    beforeStats, 0, 0);
        }

        int maxResidualColumns = positiveInt(
                "cutting.demandPeak.smallPolish.maxResidualColumns", 120);
        DemandPeakColumnPoolBuilder.Config poolConfig =
                new DemandPeakColumnPoolBuilder.Config(
                        positiveInt("cutting.demandPeak.maxMessagesPerWidth", 3),
                        positiveInt("cutting.demandPeak.maxOptionsPerWidth", 12),
                        positiveInt("cutting.demandPeak.maxConfigsPerPattern", 96),
                        maxResidualColumns,
                        positiveInt("cutting.demandPeak.peakCount", 2),
                        Math.max(0, SolverRuntimeProperties.getInt(
                                "cutting.demandPeak.peakSeparationSteps", 3)),
                        Math.max(1, params.getStepSize()));
        DemandPeakColumnPoolBuilder.Result generated =
                new DemandPeakColumnPoolBuilder().build(
                        shapes, residualDemand, List.of(), poolConfig);

        LinkedHashMap<String, Column> pool = new LinkedHashMap<>();
        releasedUses.forEach(use ->
                pool.putIfAbsent(use.column().signature(), use.column()));
        if (demandPeakColumns != null) {
            demandPeakColumns.stream().filter(Objects::nonNull)
                    .filter(column -> supportOf(column, localDemand) > 0)
                    .forEach(column -> pool.putIfAbsent(column.signature(), column));
        }
        Set<String> baseSignatures = new LinkedHashSet<>(pool.keySet());
        List<Column> residualColumns = generated.columns().stream()
                .filter(column -> !baseSignatures.contains(column.signature()))
                .filter(column -> supportOf(column, localDemand) > 0)
                .sorted(Comparator
                        .comparingInt((Column column) ->
                                supportOf(column, localDemand)).reversed()
                        .thenComparing(Column::signature))
                .limit(maxResidualColumns)
                .toList();
        if (residualColumns.isEmpty()) {
            return fallback(instructions, "no-residual-columns", startedAt,
                    beforeStats, pool.size(), 0);
        }
        residualColumns.forEach(column -> pool.putIfAbsent(column.signature(), column));

        long remainingMs = deadline - System.currentTimeMillis();
        if (remainingMs < 1_000L) {
            return fallback(instructions, "budget-exhausted-before-solve", startedAt,
                    beforeStats, pool.size(), residualColumns.size());
        }

        UseStats localBefore = statsOf(releasedUses);
        int exactLocalCars = localBefore.cars();
        int localWasteCap = wasteOf(releasedUses);
        UnifiedSetPartitionSolver.MetricCapResult capResult =
                new UnifiedSetPartitionSolver().checkMetricCaps(
                        List.copyOf(pool.values()), localDemand, exactLocalCars,
                        localWasteCap, params.getTotalWidth(), localBefore.groups(),
                        localBefore.odd(), localBefore.one(), localBefore.small() - 1,
                        remainingMs, releasedUses);
        UnifiedSetPartitionSolver.Result solution =
                capResult == null ? null : capResult.result();
        if (solution == null || !("OPTIMAL".equals(solution.status())
                || "FEASIBLE".equals(solution.status()))) {
            String status = solution == null ? "null" : solution.status();
            return fallback(instructions, "solver-" + status, startedAt,
                    beforeStats, pool.size(), residualColumns.size());
        }
        if (!localDemand.equals(producedBy(solution.uses()))
                || solution.cars() != exactLocalCars
                || solution.waste() > localWasteCap) {
            return fallback(instructions, "solver-conservation-failed", startedAt,
                    beforeStats, pool.size(), residualColumns.size());
        }

        List<ColumnUse> combinedUses = new ArrayList<>(fixedUses.size() + solution.uses().size());
        combinedUses.addAll(fixedUses);
        combinedUses.addAll(solution.uses());
        int exactCars = currentUses.stream().mapToInt(ColumnUse::count).sum();
        int wasteCap = wasteOf(currentUses);
        if (!demand.equals(producedBy(combinedUses))
                || combinedUses.stream().mapToInt(ColumnUse::count).sum() != exactCars
                || wasteOf(combinedUses) > wasteCap) {
            return fallback(instructions, "global-conservation-failed", startedAt,
                    beforeStats, pool.size(), residualColumns.size());
        }
        List<CuttingInstruction> candidate = SetPartitionRefiner.toInstructions(
                combinedUses, instructions.get(0), params);
        List<ColumnUse> rebuiltUses = SetPartitionRefiner.extractColumnUses(candidate);
        if (rebuiltUses == null
                || !demand.equals(producedBy(rebuiltUses))
                || rebuiltUses.stream().mapToInt(ColumnUse::count).sum() != exactCars
                || wasteOf(rebuiltUses) > wasteCap) {
            return fallback(instructions, "instruction-conservation-failed", startedAt,
                    beforeStats, pool.size(), residualColumns.size());
        }

        SequenceGroupPostProcessor.GroupStats afterStats =
                SequenceGroupPostProcessor.computeGroupStats(candidate);
        UseStats fixedStats = statsOf(fixedUses);
        boolean metricsMatch = afterStats.groups() == fixedStats.groups() + solution.groups()
                && afterStats.oddCarGroups() == fixedStats.odd() + solution.oddBlocks()
                && afterStats.oneCarGroups() == fixedStats.one() + solution.oneCarBlocks()
                && afterStats.smallCarGroups() == fixedStats.small() + solution.smallBlocks();
        boolean capsHold = afterStats.groups() <= beforeStats.groups()
                && afterStats.oddCarGroups() <= beforeStats.oddCarGroups()
                && afterStats.oneCarGroups() <= beforeStats.oneCarGroups();
        if (!metricsMatch || !capsHold || compare(afterStats, beforeStats) >= 0) {
            return fallback(instructions,
                    !metricsMatch ? "metric-mismatch"
                            : !capsHold ? "metric-cap-regression" : "not-better",
                    startedAt, beforeStats, pool.size(), residualColumns.size());
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("Demand-peak small polish accepted: groups {}->{}, odd {}->{}, one {}->{}, "
                        + "small {}->{}, cars={}, waste={}, pool={}, residual={}, status={}, "
                        + "gap={}, elapsedMs={}",
                beforeStats.groups(), afterStats.groups(),
                beforeStats.oddCarGroups(), afterStats.oddCarGroups(),
                beforeStats.oneCarGroups(), afterStats.oneCarGroups(),
                beforeStats.smallCarGroups(), afterStats.smallCarGroups(),
                exactCars, wasteOf(combinedUses), pool.size(), residualColumns.size(),
                solution.status(), solution.relativeGap(), elapsedMs);
        return new PolishResult(true, true, List.copyOf(candidate), "accepted", elapsedMs,
                beforeStats, afterStats, pool.size(), residualColumns.size(), solution);
    }

    private Neighborhood selectNeighborhood(List<ColumnUse> currentUses, int maxDonors) {
        return currentUses.stream()
                .filter(use -> use.count() <= SMALL_MAX_CARS)
                .map(target -> buildNeighborhood(target, currentUses, maxDonors))
                .filter(Objects::nonNull)
                .max(Comparator
                        .comparingInt(Neighborhood::cars)
                        .thenComparingInt(neighborhood ->
                                maxSharedKeys(neighborhood.target(), currentUses))
                        .thenComparing(neighborhood ->
                                neighborhood.target().column().signature(),
                                Comparator.reverseOrder()))
                .orElse(null);
    }

    private Neighborhood buildNeighborhood(ColumnUse target,
            List<ColumnUse> currentUses, int maxDonors) {
        List<ColumnUse> sameShape = currentUses.stream()
                .filter(use -> !sameColumn(use, target))
                .filter(use -> use.column().pattern().equals(target.column().pattern()))
                .sorted(donorComparator(target))
                .toList();
        if (sameShape.isEmpty()) {
            return null;
        }

        ColumnUse primary = sameShape.get(0);
        List<ColumnUse> selected = new ArrayList<>();
        selected.add(target);
        selected.add(primary);
        if (maxDonors > 1) {
            Set<String> pivotKeys = new LinkedHashSet<>(target.column().demandUse().keySet());
            pivotKeys.removeAll(primary.column().demandUse().keySet());
            List<ColumnUse> secondary = bestSecondaryFamily(
                    target, primary, currentUses, pivotKeys, maxDonors - 1);
            selected.addAll(secondary);
        }
        return new Neighborhood(target, List.copyOf(selected),
                selected.stream().mapToInt(ColumnUse::count).sum());
    }

    private List<ColumnUse> bestSecondaryFamily(ColumnUse target,
            ColumnUse primary, List<ColumnUse> currentUses, Set<String> pivotKeys,
            int limit) {
        if (pivotKeys.isEmpty() || limit < 1) {
            return List.of();
        }
        Map<String, List<ColumnUse>> byShape = new LinkedHashMap<>();
        currentUses.stream()
                .filter(use -> !sameColumn(use, target) && !sameColumn(use, primary))
                .filter(use -> !use.column().pattern().equals(target.column().pattern()))
                .filter(use -> sharedKeys(pivotKeys, use.column().demandUse().keySet()) > 0)
                .forEach(use -> byShape
                        .computeIfAbsent(new TreeMap<>(use.column().pattern()).toString(),
                                ignored -> new ArrayList<>())
                        .add(use));
        return byShape.values().stream()
                .map(family -> family.stream()
                        .sorted(Comparator
                                .comparingInt((ColumnUse use) -> sharedKeys(
                                        pivotKeys, use.column().demandUse().keySet())).reversed()
                                .thenComparing(Comparator.comparingInt(
                                        ColumnUse::count).reversed())
                                .thenComparing(use -> use.column().signature()))
                        .limit(limit)
                        .toList())
                .max(Comparator
                        .comparingInt((List<ColumnUse> family) -> family.stream()
                                .mapToInt(use -> sharedKeys(pivotKeys,
                                        use.column().demandUse().keySet())).sum())
                        .thenComparingInt(family -> family.stream()
                                .mapToInt(ColumnUse::count).sum()))
                .orElse(List.of());
    }

    private boolean sameColumn(ColumnUse first, ColumnUse second) {
        return first.column().signature().equals(second.column().signature());
    }

    private Comparator<ColumnUse> donorComparator(ColumnUse target) {
        Set<String> targetKeys = target.column().demandUse().keySet();
        return Comparator
                .comparingInt((ColumnUse use) -> sharedKeys(targetKeys,
                        use.column().demandUse().keySet())).reversed()
                .thenComparing(Comparator.comparingInt(ColumnUse::count).reversed())
                .thenComparing(use -> use.column().signature());
    }

    private int sharedKeys(Set<String> first, Set<String> second) {
        int shared = 0;
        for (String key : first) {
            if (second.contains(key)) {
                shared++;
            }
        }
        return shared;
    }

    private int maxSharedKeys(ColumnUse target, List<ColumnUse> uses) {
        Set<String> targetKeys = target.column().demandUse().keySet();
        return uses.stream()
                .filter(use -> !use.column().signature().equals(target.column().signature()))
                .mapToInt(use -> sharedKeys(targetKeys, use.column().demandUse().keySet()))
                .max()
                .orElse(0);
    }

    private UseStats statsOf(List<ColumnUse> uses) {
        return new UseStats(
                uses.size(),
                (int) uses.stream().filter(use -> use.count() % 2 != 0).count(),
                (int) uses.stream().filter(use -> use.count() == 1).count(),
                (int) uses.stream().filter(use -> use.count() <= SMALL_MAX_CARS).count(),
                uses.stream().mapToInt(ColumnUse::count).sum());
    }

    private List<Map<Integer, Integer>> collectResidualShapes(
            Collection<Column> demandPeakColumns,
            List<ColumnUse> currentUses,
            Set<Integer> residualWidths) {
        LinkedHashMap<String, Map<Integer, Integer>> shapes = new LinkedHashMap<>();
        List<Column> source = new ArrayList<>();
        if (demandPeakColumns != null) {
            demandPeakColumns.stream().filter(Objects::nonNull).forEach(source::add);
        }
        currentUses.forEach(use -> source.add(use.column()));
        source.stream()
                .filter(column -> residualWidths.containsAll(column.pattern().keySet()))
                .sorted(Comparator.comparing(Column::signature))
                .forEach(column -> {
                    Map<Integer, Integer> shape = new TreeMap<>(column.pattern());
                    shapes.putIfAbsent(shape.toString(), shape);
                });
        return List.copyOf(shapes.values());
    }

    private Map<Integer, Map<String, Integer>> demandByWidth(Collection<ColumnUse> uses) {
        Map<Integer, Map<String, Integer>> result = new TreeMap<>();
        for (ColumnUse use : uses) {
            for (Map.Entry<Integer, List<String>> entry : use.column().config().entrySet()) {
                for (String message : entry.getValue()) {
                    result.computeIfAbsent(entry.getKey(), ignored -> new TreeMap<>())
                            .merge(message, use.count(), Integer::sum);
                }
            }
        }
        return result;
    }

    private Map<String, Integer> demandOf(List<SolverOrderItem> items) {
        Map<String, Integer> result = new TreeMap<>();
        for (SolverOrderItem item : items) {
            result.merge(item.getWidth() + "|" + Objects.toString(item.getMessageText(), ""),
                    item.getDemand(), Integer::sum);
        }
        return result;
    }

    private Map<String, Integer> producedBy(List<ColumnUse> uses) {
        Map<String, Integer> result = new TreeMap<>();
        for (ColumnUse use : uses) {
            for (Map.Entry<String, Integer> entry : use.column().demandUse().entrySet()) {
                result.merge(entry.getKey(), entry.getValue() * use.count(), Integer::sum);
            }
        }
        return result;
    }

    private int wasteOf(List<ColumnUse> uses) {
        return uses.stream().mapToInt(use ->
                (params.getTotalWidth() - use.column().patternWidth()) * use.count()).sum();
    }

    private int supportOf(Column column, Map<String, Integer> demand) {
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

    private int compare(SequenceGroupPostProcessor.GroupStats first,
            SequenceGroupPostProcessor.GroupStats second) {
        int groups = Integer.compare(first.groups(), second.groups());
        if (groups != 0) {
            return groups;
        }
        int odd = Integer.compare(first.oddCarGroups(), second.oddCarGroups());
        if (odd != 0) {
            return odd;
        }
        int one = Integer.compare(first.oneCarGroups(), second.oneCarGroups());
        return one != 0 ? one : Integer.compare(first.smallCarGroups(), second.smallCarGroups());
    }

    private int positiveInt(String key, int defaultValue) {
        return Math.max(1, SolverRuntimeProperties.getInt(key, defaultValue));
    }

    private PolishResult fallback(List<CuttingInstruction> instructions, String reason,
            long startedAt, SequenceGroupPostProcessor.GroupStats beforeStats,
            int poolSize, int residualColumns) {
        long elapsedMs = System.currentTimeMillis() - startedAt;
        if (!"disabled".equals(reason)) {
            log.info("Demand-peak small polish fallback: reason={}, elapsedMs={}, "
                            + "pool={}, residual={}",
                    reason, elapsedMs, poolSize, residualColumns);
        }
        List<CuttingInstruction> safe = instructions == null ? List.of() : instructions;
        return new PolishResult(!"disabled".equals(reason), false, safe, reason, elapsedMs,
                beforeStats, beforeStats, poolSize, residualColumns, null);
    }

    public record PolishResult(boolean executed,
                               boolean improved,
                               List<CuttingInstruction> instructions,
                               String reason,
                               long elapsedMs,
                               SequenceGroupPostProcessor.GroupStats beforeStats,
                               SequenceGroupPostProcessor.GroupStats afterStats,
                               int poolSize,
                               int residualColumns,
                               UnifiedSetPartitionSolver.Result solution) {
    }

    private record Neighborhood(ColumnUse target, List<ColumnUse> uses, int cars) {
    }

    private record UseStats(int groups, int odd, int one, int small, int cars) {
    }
}
