package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPSolver;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ColumnPool;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ConvertedOutput;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DualVector;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.IterationTrace;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Metrics;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Phase;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Result;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.TerminationStatus;
import test.demo.apsmodule.generator.NewSolver.mip.AssignmentMIPSolver;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Orchestrates the research-only order-group column generation workflow.
 *
 * <p>The model records remain in {@link OrderGroupColumnPricingPrototype};
 * keeping this engine separate makes the pricing, restricted master and
 * incumbent seeding responsibilities independently inspectable.</p>
 */
final class OrderGroupColumnPricingEngine {

    private static final int FIRST_QUALITY_TARGET_GROUPS = 29;

    private OrderGroupColumnPricingEngine() {
    }

    static Result solve(Input input, Options options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        Loader.loadNativeLibraries();

        long totalStartedAt = System.currentTimeMillis();
        OrderGroupColumnIncumbentSeeder.SeedResult seed =
                options.automaticIncumbentSeed()
                        ? OrderGroupColumnIncumbentSeeder.seed(input, options)
                        : OrderGroupColumnIncumbentSeeder.SeedResult.disabled();
        return solveCore(input, options, seed.columns(), seed, totalStartedAt);
    }

    static Result solveCore(
            Input input,
            Options options,
            List<GroupColumn> initialColumns,
            OrderGroupColumnIncumbentSeeder.SeedResult seed,
            long totalStartedAt) {
        long coreStartedAt = System.currentTimeMillis();
        int pricingCapacity = Math.max(
                1, options.maxColumns() - initialColumns.size());
        ColumnPool pool = new ColumnPool(pricingCapacity);
        Map<String, GroupColumn> integerArchive = new LinkedHashMap<>();
        archive(integerArchive, initialColumns, options.integerRepairCandidates());
        Phase phase = Phase.FEASIBILITY;
        DualVector stableDual = null;
        OrderGroupRestrictedMaster.LpResult lastLp = null;
        TerminationStatus termination = TerminationStatus.NO_NEGATIVE_COLUMN_FOUND;
        int noColumnRounds = 0;
        int iteration = 0;
        long lpMs = 0L;
        long pricingMs = 0L;
        OrderGroupColumnPricingOracle.Diagnostics pricingDiagnostics =
                OrderGroupColumnPricingOracle.Diagnostics.empty();
        OrderGroupColumnPricingOracle oracle =
                new OrderGroupColumnPricingOracle(input, options);
        List<IterationTrace> traces = new ArrayList<>();

        long pricingDeadline = deadline(coreStartedAt, options.pricingTimeLimitMs());
        while (iteration < options.maxIterations()) {
            if (System.currentTimeMillis() >= pricingDeadline) {
                termination = TerminationStatus.TIME_LIMIT;
                break;
            }
            iteration++;

            long lpStartedAt = System.currentTimeMillis();
            lastLp = OrderGroupRestrictedMaster.solveLp(input, options, pool.columns(), phase);
            lpMs += System.currentTimeMillis() - lpStartedAt;
            if (lastLp.status() != MPSolver.ResultStatus.OPTIMAL) {
                termination = TerminationStatus.MASTER_FAILED;
                break;
            }

            if (phase == Phase.FEASIBILITY
                    && lastLp.maxArtificialValue() <= options.artificialEpsilon()) {
                phase = Phase.OPTIMIZATION;
                stableDual = null;
                noColumnRounds = 0;
                traces.add(new IterationTrace(
                        iteration,
                        Phase.FEASIBILITY,
                        pool.size(),
                        0,
                        lastLp.objectiveValue(),
                        lastLp.maxArtificialValue(),
                        0.0,
                        Double.NaN,
                        Double.NaN));
                continue;
            }

            DualVector currentDual = lastLp.dual();
            stableDual = stableDual == null
                    ? currentDual
                    : DualVector.blend(currentDual, stableDual, options.dualAlpha());

            long pricingStartedAt = System.currentTimeMillis();
            OrderGroupColumnPricingOracle.PricingResult priced =
                    oracle.price(
                            phase,
                            currentDual,
                            stableDual,
                            pool,
                            pricingDeadline);
            pricingMs += System.currentTimeMillis() - pricingStartedAt;
            pricingDiagnostics = pricingDiagnostics.plus(priced.diagnostics());
            Phase pricedPhase = phase;
            double bestRawReducedCost = priced.columns().stream()
                    .mapToDouble(column -> OrderGroupColumnPricingPrototype.rawReducedCost(
                            column, currentDual, pricedPhase))
                    .min()
                    .orElse(Double.NaN);
            double worstRawReducedCost = priced.columns().stream()
                    .mapToDouble(column -> OrderGroupColumnPricingPrototype.rawReducedCost(
                            column, currentDual, pricedPhase))
                    .max()
                    .orElse(Double.NaN);

            archive(
                    integerArchive,
                    priced.columns(),
                    options.integerRepairCandidates());
            int added = pool.addAll(priced.columns());
            double dualL1 = lastLp.previousDualL1();
            traces.add(new IterationTrace(
                    iteration,
                    phase,
                    pool.size(),
                    added,
                    lastLp.objectiveValue(),
                    lastLp.maxArtificialValue(),
                    dualL1,
                    bestRawReducedCost,
                    worstRawReducedCost));

            if (added == 0) {
                noColumnRounds++;
                if (noColumnRounds >= options.noColumnPatience()) {
                    termination = phase == Phase.FEASIBILITY
                            ? TerminationStatus.ARTIFICIAL_SLACK_REMAINS
                            : TerminationStatus.NO_NEGATIVE_COLUMN_FOUND;
                    break;
                }
            } else {
                noColumnRounds = 0;
            }

            if (pool.size() >= pricingCapacity) {
                Set<String> keep = new LinkedHashSet<>();
                lastLp.columnValues().forEach((signature, value) -> {
                    if (value > 1e-8) {
                        keep.add(signature);
                    }
                });
                for (GroupColumn column : priced.columns()) {
                    if (pool.contains(column.signature())) {
                        keep.add(column.signature());
                    }
                }
                int beforePrune = pool.size();
                pool.retain(keep);
                if (pool.size() >= beforePrune) {
                    termination = TerminationStatus.COLUMN_CAP_REACHED;
                    break;
                }
            }
        }

        if (iteration >= options.maxIterations()
                && termination == TerminationStatus.NO_NEGATIVE_COLUMN_FOUND) {
            termination = TerminationStatus.ITERATION_LIMIT;
        }

        boolean pricingMasterReady = phase == Phase.OPTIMIZATION
                && lastLp != null
                && lastLp.maxArtificialValue() <= options.artificialEpsilon();
        if (!pricingMasterReady && !initialColumns.isEmpty()) {
            long fallbackLpStartedAt = System.currentTimeMillis();
            lastLp = OrderGroupRestrictedMaster.solveLp(
                    input, options, initialColumns, Phase.OPTIMIZATION);
            lpMs += System.currentTimeMillis() - fallbackLpStartedAt;
            pricingMasterReady = lastLp.status() == MPSolver.ResultStatus.OPTIMAL;
        }

        if (!pricingMasterReady
                || lastLp == null
                || lastLp.maxArtificialValue() > options.artificialEpsilon()) {
            return Result.withoutIntegerSolution(
                    termination == TerminationStatus.NO_NEGATIVE_COLUMN_FOUND
                            ? TerminationStatus.ARTIFICIAL_SLACK_REMAINS
                            : termination,
                    termination,
                    pool.columns(),
                    iteration,
                    lastLp,
                    traces,
                    pricingDiagnostics,
                    lpMs,
                    pricingMs,
                    System.currentTimeMillis() - totalStartedAt,
                    seed);
        }

        if (!initialColumns.isEmpty()) {
            ColumnPool combined = new ColumnPool(options.maxColumns());
            combined.addAll(initialColumns);
            combined.addAll(pool.columns());
            pool = combined;
        }

        long mipStartedAt = System.currentTimeMillis();
        OrderGroupRestrictedMaster.IntegerResult integerResult =
                OrderGroupRestrictedMaster.solveInteger(input, options, pool.columns());
        long mipMs = System.currentTimeMillis() - mipStartedAt;
        MPSolver.ResultStatus repairStatus = MPSolver.ResultStatus.NOT_SOLVED;
        int repairCandidateCount = 0;
        int repairSelectedCount = 0;
        double repairMaxArtificial = Double.POSITIVE_INFINITY;
        long repairMs = 0L;
        if (!integerResult.feasible() && pool.size() < options.maxColumns()) {
            int room = options.maxColumns() - pool.size();
            long completionStartedAt = System.currentTimeMillis();
            long completionDeadline = deadline(
                    completionStartedAt, options.integerCompletionTimeLimitMs());
            OrderGroupColumnPricingOracle.CompletionResult completion =
                    oracle.complete(lastLp.dual(), pool, room, completionDeadline);
            archive(
                    integerArchive,
                    completion.columns(),
                    options.integerRepairCandidates());
            int completionAdded = pool.addAll(completion.columns());
            long completionMs = System.currentTimeMillis() - completionStartedAt;
            pricingMs += completionMs;
            pricingDiagnostics = pricingDiagnostics.plus(completion.diagnostics());
            if (completionAdded > 0) {
                long retryStartedAt = System.currentTimeMillis();
                integerResult = OrderGroupRestrictedMaster.solveInteger(
                        input, options, pool.columns());
                mipMs += System.currentTimeMillis() - retryStartedAt;
            }
        }
        List<GroupColumn> broadCompletionColumns = List.of();
        boolean pursueFirstQualityTarget = integerResult.feasible()
                && integerResult.selectedColumns().size()
                > FIRST_QUALITY_TARGET_GROUPS;
        int archiveRoom =
                options.integerRepairCandidates() - integerArchive.size();
        if (archiveRoom > 0
                && (!integerResult.feasible() || pursueFirstQualityTarget)) {
            long broadCompletionStartedAt = System.currentTimeMillis();
            long broadCompletionDeadline = deadline(
                    broadCompletionStartedAt, options.integerCompletionTimeLimitMs());
            OrderGroupColumnPricingOracle.CompletionResult broadCompletion =
                    oracle.complete(
                            lastLp.dual(),
                            pool,
                            archiveRoom,
                            broadCompletionDeadline);
            pricingMs += System.currentTimeMillis() - broadCompletionStartedAt;
            pricingDiagnostics = pricingDiagnostics.plus(
                    broadCompletion.diagnostics());
            broadCompletionColumns = broadCompletion.columns();
            archive(
                    integerArchive,
                    broadCompletionColumns,
                    options.integerRepairCandidates());
        }

        boolean archiveHasAdditionalColumns = false;
        for (String signature : integerArchive.keySet()) {
            if (!pool.contains(signature)) {
                archiveHasAdditionalColumns = true;
                break;
            }
        }
        if (!integerResult.feasible()
                || (pursueFirstQualityTarget && archiveHasAdditionalColumns)) {
            Map<String, GroupColumn> repairCandidates = new LinkedHashMap<>();
            pool.columns().forEach(column ->
                    repairCandidates.put(column.signature(), column));
            integerArchive.values().forEach(column ->
                    repairCandidates.putIfAbsent(column.signature(), column));
            broadCompletionColumns.forEach(column ->
                    repairCandidates.putIfAbsent(column.signature(), column));
            List<GroupColumn> repairCandidateList =
                    repairCandidates.values().stream()
                            .sorted(Comparator.comparing(GroupColumn::signature))
                            .toList();
            repairCandidateCount = repairCandidateList.size();

            long repairStartedAt = System.currentTimeMillis();
            OrderGroupRestrictedMaster.IntegerRepairResult repair =
                    OrderGroupRestrictedMaster.solveIntegerRepair(
                            input, options, repairCandidateList);
            repairMs = System.currentTimeMillis() - repairStartedAt;
            mipMs += repairMs;
            repairStatus = repair.status();
            repairSelectedCount = repair.selectedColumns().size();
            repairMaxArtificial = repair.maxArtificial();

            boolean repairImproves = repair.exact(options.artificialEpsilon())
                    && (!integerResult.feasible()
                    || repair.objectiveValue()
                    < integerResult.objectiveValue() - options.reducedCostEpsilon());
            if (repairImproves) {
                ColumnPool rebuilt = new ColumnPool(options.maxColumns());
                rebuilt.addAll(repair.selectedColumns());
                rebuilt.addAll(pool.columns());
                rebuilt.addAll(integerArchive.values());
                rebuilt.addAll(broadCompletionColumns);
                pool = rebuilt;
                long retryStartedAt = System.currentTimeMillis();
                integerResult = OrderGroupRestrictedMaster.solveInteger(
                        input, options, pool.columns());
                mipMs += System.currentTimeMillis() - retryStartedAt;
            }
        }
        if (!integerResult.feasible()) {
            return Result.withoutIntegerSolution(
                    TerminationStatus.INTEGER_MASTER_INFEASIBLE,
                    termination,
                    pool.columns(),
                    iteration,
                    lastLp,
                    traces,
                    pricingDiagnostics,
                    lpMs,
                    pricingMs,
                    mipMs,
                    System.currentTimeMillis() - totalStartedAt,
                    integerResult,
                    repairStatus,
                    repairCandidateCount,
                    repairSelectedCount,
                    repairMaxArtificial,
                    repairMs,
                    seed);
        }

        List<GroupColumn> selected = integerResult.selectedColumns().stream()
                .sorted(Comparator.comparing(GroupColumn::signature))
                .toList();
        ConvertedOutput converted = convertSelected(input, selected);
        Metrics direct = Metrics.fromColumns(selected);
        SequenceGroupPostProcessor.GroupStats outputStats =
                SequenceGroupPostProcessor.computeGroupStats(converted.instructions());
        Metrics displayed = new Metrics(
                outputStats.groups(),
                outputStats.oddCarGroups(),
                outputStats.oneCarGroups(),
                outputStats.smallCarGroups(),
                direct.cars(),
                direct.waste());
        boolean semanticConsistent = direct.groups() == displayed.groups()
                && direct.oddGroups() == displayed.oddGroups()
                && direct.oneCarGroups() == displayed.oneCarGroups()
                && direct.smallGroups() == displayed.smallGroups();

        TerminationStatus finalStatus = semanticConsistent
                ? (integerResult.status() == MPSolver.ResultStatus.OPTIMAL
                        ? TerminationStatus.INTEGER_MASTER_OPTIMAL
                        : TerminationStatus.INTEGER_MASTER_FEASIBLE)
                : TerminationStatus.SEMANTIC_MISMATCH;
        return new Result(
                finalStatus,
                termination,
                List.copyOf(pool.columns()),
                selected,
                converted.solution(),
                converted.assignments(),
                converted.instructions(),
                direct,
                displayed,
                semanticConsistent,
                iteration,
                lastLp.objectiveValue(),
                lastLp.maxArtificialValue(),
                integerResult.status(),
                integerResult.objectiveValue(),
                integerResult.nodes(),
                List.copyOf(traces),
                pricingDiagnostics,
                lpMs,
                pricingMs,
                mipMs,
                System.currentTimeMillis() - totalStartedAt,
                repairStatus,
                repairCandidateCount,
                repairSelectedCount,
                repairMaxArtificial,
                repairMs,
                seed);
    }

    static ConvertedOutput convertSelected(Input input, List<GroupColumn> selected) {
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> assignments =
                new LinkedHashMap<>();
        Map<PatternCandidate, List<GroupColumn>> byPattern = new TreeMap<>(
                Comparator.comparing(PatternCandidate::signature));
        for (GroupColumn column : selected) {
            solution.merge(column.pattern(), column.cars(), Math::addExact);
            assignments.computeIfAbsent(column.pattern(), ignored -> new ArrayList<>())
                    .add(AssignmentMIPSolver.AssignmentBlock.fromStationConfig(
                            column.stationConfig(), column.cars()));
            byPattern.computeIfAbsent(column.pattern(), ignored -> new ArrayList<>()).add(column);
        }

        List<CuttingInstruction> instructions = new ArrayList<>();
        SolverOrderItem sample = input.items().get(0);
        for (Map.Entry<PatternCandidate, List<GroupColumn>> entry : byPattern.entrySet()) {
            PatternCandidate pattern = entry.getKey();
            List<GroupColumn> columns = entry.getValue().stream()
                    .sorted(Comparator.comparing(GroupColumn::signature))
                    .toList();
            CuttingInstruction instruction = new CuttingInstruction();
            instruction.setGroupKey(sample.getGroupKey());
            instruction.setRollWidth(pattern.getRollWidth());
            instruction.setLength(sample.getLength());
            instruction.setSurfaceTreatment(sample.getSurfaceTreatment());
            instruction.setThickness(sample.getThickness());
            instruction.setSubRolls(new TreeMap<>(pattern.getPattern()));
            instruction.setUsageCount(columns.stream().mapToInt(GroupColumn::cars).sum());
            instruction.setPatternWidth(pattern.getPatternWidth());
            instruction.setWaste(pattern.getRealWaste(input.params().getTotalWidth()));

            List<StationAssignment> stationAssignments = new ArrayList<>();
            for (GroupColumn column : columns) {
                for (int car = 0; car < column.cars(); car++) {
                    for (Map.Entry<Integer, List<String>> config :
                            column.stationConfig().entrySet()) {
                        for (String message : config.getValue()) {
                            stationAssignments.add(new StationAssignment(config.getKey(), message));
                        }
                    }
                }
            }
            instruction.setStationAssignments(stationAssignments);
            instructions.add(instruction);
        }

        Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> immutableAssignments =
                new LinkedHashMap<>();
        assignments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(PatternCandidate::signature)))
                .forEach(entry -> immutableAssignments.put(
                        entry.getKey(), List.copyOf(entry.getValue())));
        return new ConvertedOutput(
                Collections.unmodifiableMap(new LinkedHashMap<>(solution)),
                Collections.unmodifiableMap(immutableAssignments),
                List.copyOf(instructions));
    }

    private static long deadline(long startedAt, long durationMs) {
        if (durationMs >= Long.MAX_VALUE - startedAt) {
            return Long.MAX_VALUE;
        }
        return startedAt + durationMs;
    }

    private static void archive(
            Map<String, GroupColumn> target,
            Collection<GroupColumn> columns,
            int capacity) {
        for (GroupColumn column : columns) {
            if (target.containsKey(column.signature())) {
                continue;
            }
            if (target.size() >= capacity) {
                return;
            }
            target.put(column.signature(), column);
        }
    }
}
