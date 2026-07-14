package test.demo.apsmodule.generator.NewSolver.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakColumnPoolBuilder;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakInitialSolutionSolver;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Time-boxed demand-peak reconstruction between Stage5 and LNS.
 *
 * <p>The Stage5 instructions are protected as a feasible warm start. A candidate is returned
 * only when exact demand, car count and waste are conserved and the real sequence metrics are
 * strictly better. Every other outcome is a normal fallback to the original instructions.</p>
 */
public final class DemandPeakFastStage {

    private static final Logger log = LoggerFactory.getLogger(DemandPeakFastStage.class);
    private static final long DEFAULT_TIME_LIMIT_MS = 5_000L;

    private final SolverParameters params;

    public DemandPeakFastStage(SolverParameters params) {
        this.params = Objects.requireNonNull(params, "params").copy();
        this.params.sanitize();
    }

    public static boolean isEnabled() {
        return SolverRuntimeProperties.getBoolean("cutting.demandPeak.enabled", false);
    }

    public StageResult improve(List<CuttingInstruction> stage5Instructions,
            List<SolverOrderItem> groupItems) {
        long startedAt = System.currentTimeMillis();
        if (!isEnabled()) {
            return fallback(stage5Instructions, "disabled", startedAt, null, null);
        }
        if (stage5Instructions == null || stage5Instructions.isEmpty()
                || groupItems == null || groupItems.isEmpty()) {
            return fallback(stage5Instructions, "empty-input", startedAt, null, null);
        }

        SequenceGroupPostProcessor.GroupStats beforeStats =
                SequenceGroupPostProcessor.computeGroupStats(stage5Instructions);
        if (beforeStats.groups() <= 1) {
            return fallback(stage5Instructions, "group-lower-bound", startedAt,
                    beforeStats, null);
        }

        long timeLimitMs = SolverRuntimeProperties.getLong(
                "cutting.demandPeak.timeMs", DEFAULT_TIME_LIMIT_MS);
        if (timeLimitMs < 1) {
            return fallback(stage5Instructions, "no-time-budget", startedAt,
                    beforeStats, null);
        }

        List<ColumnUse> protectedUses = SetPartitionRefiner.extractColumnUses(stage5Instructions);
        if (protectedUses == null || protectedUses.size() < 2) {
            return fallback(stage5Instructions, "unreconstructible-stage5", startedAt,
                    beforeStats, null);
        }

        Map<String, Integer> demand = demandOf(groupItems);
        if (!demand.equals(producedBy(protectedUses))) {
            return fallback(stage5Instructions, "stage5-demand-mismatch", startedAt,
                    beforeStats, null);
        }

        int exactCars = protectedUses.stream().mapToInt(ColumnUse::count).sum();
        int wasteCap = wasteOf(protectedUses);
        List<Column> protectedColumns = protectedUses.stream().map(ColumnUse::column).toList();
        List<Map<Integer, Integer>> shapes = stage5Instructions.stream()
                .map(CuttingInstruction::getSubRolls)
                .filter(shape -> shape != null && !shape.isEmpty())
                .<Map<Integer, Integer>>map(LinkedHashMap::new)
                .toList();
        if (shapes.isEmpty()) {
            return fallback(stage5Instructions, "empty-shape-pool", startedAt,
                    beforeStats, null);
        }

        DemandPeakColumnPoolBuilder.Config poolConfig = poolConfig();
        DemandPeakColumnPoolBuilder.Result pool = new DemandPeakColumnPoolBuilder().build(
                shapes, demandByWidth(groupItems), protectedColumns, poolConfig);
        DemandPeakInitialSolutionSolver.InitialSolution initial =
                new DemandPeakInitialSolutionSolver().solve(
                        pool.columns(), demand, protectedColumns, poolConfig,
                        exactCars, wasteCap, params.getTotalWidth(), timeLimitMs,
                        protectedUses);

        if (!initial.feasible()) {
            String status = initial.solution() == null ? "null" : initial.solution().status();
            return fallback(stage5Instructions, "solver-" + status, startedAt,
                    beforeStats, initial);
        }
        if (!demand.equals(producedBy(initial.solution().uses()))
                || initial.solution().cars() != exactCars
                || initial.solution().waste() > wasteCap) {
            return fallback(stage5Instructions, "solver-conservation-failed", startedAt,
                    beforeStats, initial);
        }

        List<CuttingInstruction> candidate = SetPartitionRefiner.toInstructions(
                initial.solution().uses(), stage5Instructions.get(0), params);
        List<ColumnUse> rebuiltUses = SetPartitionRefiner.extractColumnUses(candidate);
        if (rebuiltUses == null
                || !demand.equals(producedBy(rebuiltUses))
                || rebuiltUses.stream().mapToInt(ColumnUse::count).sum() != exactCars
                || wasteOf(rebuiltUses) > wasteCap) {
            return fallback(stage5Instructions, "instruction-conservation-failed", startedAt,
                    beforeStats, initial);
        }

        SequenceGroupPostProcessor.GroupStats afterStats =
                SequenceGroupPostProcessor.computeGroupStats(candidate);
        if (afterStats.groups() != initial.solution().groups()
                || afterStats.oddCarGroups() != initial.solution().oddBlocks()
                || afterStats.oneCarGroups() != initial.solution().oneCarBlocks()
                || afterStats.smallCarGroups() != initial.solution().smallBlocks()) {
            return fallback(stage5Instructions, "metric-mismatch", startedAt,
                    beforeStats, initial);
        }
        if (compare(afterStats, beforeStats) >= 0) {
            return fallback(stage5Instructions, "not-better", startedAt,
                    beforeStats, initial);
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("Demand-peak fast stage accepted: groups {}->{}, odd {}->{}, one {}->{}, "
                        + "small {}->{}, cars={}, waste={}, pool={}, peaks={}, status={}, "
                        + "gap={}, elapsedMs={}",
                beforeStats.groups(), afterStats.groups(),
                beforeStats.oddCarGroups(), afterStats.oddCarGroups(),
                beforeStats.oneCarGroups(), afterStats.oneCarGroups(),
                beforeStats.smallCarGroups(), afterStats.smallCarGroups(),
                exactCars, initial.solution().waste(), initial.pool().columns().size(),
                initial.pool().peaks(), initial.solution().status(),
                initial.solution().relativeGap(), elapsedMs);
        return new StageResult(true, true, List.copyOf(candidate), "accepted", elapsedMs,
                beforeStats, afterStats, initial);
    }

    private DemandPeakColumnPoolBuilder.Config poolConfig() {
        return new DemandPeakColumnPoolBuilder.Config(
                positiveInt("cutting.demandPeak.maxMessagesPerWidth", 3),
                positiveInt("cutting.demandPeak.maxOptionsPerWidth", 12),
                positiveInt("cutting.demandPeak.maxConfigsPerPattern", 96),
                positiveInt("cutting.demandPeak.bandLimit", 96),
                positiveInt("cutting.demandPeak.peakCount", 2),
                Math.max(0, SolverRuntimeProperties.getInt(
                        "cutting.demandPeak.peakSeparationSteps", 3)),
                Math.max(1, params.getStepSize()));
    }

    private int positiveInt(String key, int defaultValue) {
        return Math.max(1, SolverRuntimeProperties.getInt(key, defaultValue));
    }

    private Map<Integer, Map<String, Integer>> demandByWidth(List<SolverOrderItem> items) {
        Map<Integer, Map<String, Integer>> result = new TreeMap<>();
        for (SolverOrderItem item : items) {
            result.computeIfAbsent(item.getWidth(), ignored -> new TreeMap<>())
                    .merge(Objects.toString(item.getMessageText(), ""),
                            item.getDemand(), Integer::sum);
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

    private StageResult fallback(List<CuttingInstruction> instructions, String reason,
            long startedAt, SequenceGroupPostProcessor.GroupStats beforeStats,
            DemandPeakInitialSolutionSolver.InitialSolution initial) {
        long elapsedMs = System.currentTimeMillis() - startedAt;
        if (!"disabled".equals(reason)) {
            log.info("Demand-peak fast stage fallback: reason={}, elapsedMs={}, status={}, pool={}",
                    reason, elapsedMs,
                    initial == null || initial.solution() == null
                            ? "n/a" : initial.solution().status(),
                    initial == null || initial.pool() == null
                            ? 0 : initial.pool().columns().size());
        }
        List<CuttingInstruction> safe = instructions == null ? List.of() : instructions;
        return new StageResult(!"disabled".equals(reason), false, safe, reason, elapsedMs,
                beforeStats, beforeStats, initial);
    }

    public record StageResult(boolean executed,
                              boolean improved,
                              List<CuttingInstruction> instructions,
                              String reason,
                              long elapsedMs,
                              SequenceGroupPostProcessor.GroupStats beforeStats,
                              SequenceGroupPostProcessor.GroupStats afterStats,
                              DemandPeakInitialSolutionSolver.InitialSolution initialSolution) {
    }
}
