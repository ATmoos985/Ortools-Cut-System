package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateGenerator.BeamExpansion;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateGenerator.BeamState;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateGenerator.LocalOption;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateGenerator.LocalOptionSelection;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ColumnPool;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ColumnRole;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DualVector;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Phase;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finite pricing oracle: scans every available cutting-pattern skeleton but
 * materializes only a bounded number of order-level group columns.
 */
final class OrderGroupColumnPricingOracle {

    private final Input input;
    private final Options options;
    private final OrderGroupColumnCandidateGenerator candidateGenerator;

    OrderGroupColumnPricingOracle(Input input, Options options) {
        this.input = Objects.requireNonNull(input, "input");
        this.options = Objects.requireNonNull(options, "options");
        this.candidateGenerator =
                new OrderGroupColumnCandidateGenerator(input, options);
    }

    PricingResult price(
            Phase phase,
            DualVector rawDual,
            DualVector stableDual,
            ColumnPool pool,
            long deadlineMs) {
        return price(phase, rawDual, stableDual, pool, deadlineMs, null);
    }

    OrderGroupColumnCandidateAuditTracker.Replay auditTargets(
            Collection<GroupColumn> targets,
            DualVector rawDual,
            ColumnPool pool,
            long deadlineMs) {
        OrderGroupColumnCandidateAuditTracker tracker =
                new OrderGroupColumnCandidateAuditTracker(targets, pool);
        PricingResult replay = price(
                Phase.OPTIMIZATION,
                rawDual,
                rawDual,
                pool,
                deadlineMs,
                tracker);
        return tracker.freeze(
                replay.diagnostics().deadlineReached(),
                options.reducedCostEpsilon(),
                replay.diagnostics());
    }

    private PricingResult price(
            Phase phase,
            DualVector rawDual,
            DualVector stableDual,
            ColumnPool pool,
            long deadlineMs,
            OrderGroupColumnCandidateAuditTracker tracker) {
        int retainedPerRole = Math.max(40, options.maxAddedPerIteration() * 20);
        BoundedCollector collector = new BoundedCollector(retainedPerRole);
        MutableDiagnostics diagnostics = new MutableDiagnostics();
        Map<String, Double> rawReducedCosts = new LinkedHashMap<>();

        for (PatternCandidate pattern : input.universe()) {
            if (System.currentTimeMillis() >= deadlineMs) {
                diagnostics.deadlineReached = true;
                break;
            }
            diagnostics.scannedPatterns++;

            List<Integer> cars = candidateCars(pattern);
            if (tracker != null) {
                tracker.observePattern(pattern);
            }
            diagnostics.carCandidates += cars.size();
            List<ScoredColumn> patternCandidates = new ArrayList<>();
            for (int carCount : cars) {
                if (input.exactOneCarGroups() == 0 && carCount == 1) {
                    continue;
                }
                if (input.exactOddGroups() == 0 && carCount % 2 != 0) {
                    continue;
                }
                List<GroupColumn> watched = tracker == null
                        ? List.of()
                        : tracker.targetsFor(pattern, carCount);
                if (tracker != null) {
                    tracker.observeCar(pattern, carCount);
                }

                List<BeamState> beam = List.of(BeamState.empty());
                Set<Integer> processedWidths = new LinkedHashSet<>();
                boolean feasible = true;
                for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                    LocalOptionSelection localSelection =
                            candidateGenerator.localOptionSelection(
                                    cut.getKey(),
                                    cut.getValue(),
                                    carCount,
                                    rawDual,
                                    stableDual);
                    List<LocalOption> localOptions = localSelection.selected();
                    if (tracker != null && !watched.isEmpty()) {
                        observeLocalTargets(
                                tracker,
                                watched,
                                cut.getKey(),
                                localSelection);
                    }
                    diagnostics.localConfigurations += localOptions.size();
                    if (localOptions.isEmpty()) {
                        feasible = false;
                        break;
                    }
                    BeamExpansion beamExpansion =
                            candidateGenerator.extendBeamDetailed(
                                    beam, cut.getKey(), localOptions);
                    processedWidths.add(cut.getKey());
                    if (tracker != null && !watched.isEmpty()) {
                        observeBeamTargets(
                                tracker,
                                watched,
                                cut.getKey(),
                                processedWidths,
                                beamExpansion);
                    }
                    beam = beamExpansion.selected();
                    if (beam.isEmpty()) {
                        feasible = false;
                        break;
                    }
                }
                if (!feasible) {
                    continue;
                }
                diagnostics.beamConfigurations += beam.size();

                for (BeamState state : beam) {
                    GroupColumn column;
                    try {
                        column = GroupColumn.create(
                                input, pattern, state.stationConfig(), carCount);
                    } catch (IllegalArgumentException invalid) {
                        diagnostics.invalidConfigurations++;
                        continue;
                    }
                    diagnostics.pricedCandidates++;
                    double rawReducedCost =
                            OrderGroupColumnPricingPrototype.rawReducedCost(
                                    column, rawDual, phase);
                    if (tracker != null) {
                        tracker.observeMaterialized(column, rawReducedCost);
                    }
                    if (pool.contains(column.signature())) {
                        diagnostics.duplicateSignatures++;
                        continue;
                    }
                    if (rawReducedCost >= -options.reducedCostEpsilon()) {
                        continue;
                    }
                    diagnostics.negativeReducedCostColumns++;
                    double stableReducedCost =
                            OrderGroupColumnPricingPrototype.rawReducedCost(
                                    column, stableDual, phase);
                    patternCandidates.add(new ScoredColumn(
                            column, rawReducedCost, stableReducedCost));
                }
            }

            patternCandidates.sort(ScoredColumn.STABLE_ORDER);
            int patternLimit = Math.min(
                    options.maxColumnsPerPattern(), patternCandidates.size());
            if (tracker != null) {
                tracker.observePatternRanking(
                        pattern,
                        patternCandidates.stream()
                                .map(scored -> scored.column().signature())
                                .toList(),
                        patternLimit);
            }
            for (int index = 0; index < patternLimit; index++) {
                ScoredColumn scored = patternCandidates.get(index);
                collector.offer(scored);
                rawReducedCosts.put(scored.column().signature(), scored.rawReducedCost());
            }
        }

        List<GroupColumn> retained = collector.values().stream()
                .map(ScoredColumn::column)
                .toList();
        if (tracker != null) {
            tracker.observeGlobalRetained(retained);
        }
        List<GroupColumn> selected =
                OrderGroupColumnPricingPrototype.roleDiverseSelection(
                        retained,
                        options.maxAddedPerIteration(),
                        options.maxPerPatternPerIteration(),
                        rawReducedCosts);
        if (tracker != null) {
            tracker.observeReturned(selected);
        }
        diagnostics.retainedCandidates = retained.size();
        diagnostics.returnedColumns = selected.size();
        return new PricingResult(selected, diagnostics.freeze());
    }

    CompletionResult complete(
            DualVector referenceDual,
            ColumnPool pool,
            int limit,
            long deadlineMs) {
        if (limit <= 0) {
            return new CompletionResult(List.of(), Diagnostics.empty());
        }
        BoundedCompletionCollector collector =
                new BoundedCompletionCollector(Math.max(100, limit * 12));
        MutableDiagnostics diagnostics = new MutableDiagnostics();

        for (PatternCandidate pattern : input.universe()) {
            if (System.currentTimeMillis() >= deadlineMs) {
                diagnostics.deadlineReached = true;
                break;
            }
            diagnostics.scannedPatterns++;
            List<CompletionScored> perPattern = new ArrayList<>();
            List<Integer> cars = candidateCars(pattern);
            diagnostics.carCandidates += cars.size();
            for (int carCount : cars) {
                if (input.exactOneCarGroups() == 0 && carCount == 1) {
                    continue;
                }
                if (input.exactOddGroups() == 0 && carCount % 2 != 0) {
                    continue;
                }
                List<BeamState> beam = List.of(BeamState.empty());
                boolean feasible = true;
                for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                    List<LocalOption> localOptions =
                            candidateGenerator.localOptionSelection(
                                    cut.getKey(),
                                    cut.getValue(),
                                    carCount,
                                    referenceDual,
                                    referenceDual).selected();
                    diagnostics.localConfigurations += localOptions.size();
                    if (localOptions.isEmpty()) {
                        feasible = false;
                        break;
                    }
                    beam = candidateGenerator.extendBeam(
                            beam, cut.getKey(), localOptions);
                    if (beam.isEmpty()) {
                        feasible = false;
                        break;
                    }
                }
                if (!feasible) {
                    continue;
                }
                diagnostics.beamConfigurations += beam.size();
                for (BeamState state : beam) {
                    GroupColumn column;
                    try {
                        column = GroupColumn.create(
                                input, pattern, state.stationConfig(), carCount);
                    } catch (IllegalArgumentException invalid) {
                        diagnostics.invalidConfigurations++;
                        continue;
                    }
                    diagnostics.pricedCandidates++;
                    diagnostics.completionCandidates++;
                    if (pool.contains(column.signature())) {
                        diagnostics.duplicateSignatures++;
                        continue;
                    }
                    double reducedCost =
                            OrderGroupColumnPricingPrototype.rawReducedCost(
                                    column, referenceDual, Phase.OPTIMIZATION);
                    int closures = closureCount(column);
                    perPattern.add(new CompletionScored(
                            column, closures, reducedCost));
                }
            }
            perPattern.sort(CompletionScored.BEST_FIRST);
            perPattern.stream()
                    .limit(options.maxColumnsPerPattern())
                    .forEach(collector::offer);
        }

        List<CompletionScored> ordered = collector.values();
        List<GroupColumn> selected = completionDiverseSelection(ordered, limit);
        diagnostics.retainedCandidates = ordered.size();
        diagnostics.returnedColumns = selected.size();
        diagnostics.completionReturnedColumns = selected.size();
        return new CompletionResult(selected, diagnostics.freeze());
    }

    private int closureCount(GroupColumn column) {
        int closures = 0;
        for (Map.Entry<DemandKey, Integer> entry : column.coverage().entrySet()) {
            if (entry.getValue().equals(input.demand().get(entry.getKey()))) {
                closures++;
            }
        }
        return closures;
    }

    private List<GroupColumn> completionDiverseSelection(
            List<CompletionScored> ordered, int limit) {
        List<GroupColumn> selected = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        Map<String, Integer> perPattern = new LinkedHashMap<>();
        int perPatternLimit = Math.max(2, options.maxPerPatternPerIteration() * 2);

        for (ColumnRole role : ColumnRole.values()) {
            if (selected.size() >= limit) {
                break;
            }
            for (CompletionScored candidate : ordered) {
                if (candidate.column().role() == role
                        && acceptCompletion(
                                candidate.column(),
                                selected,
                                signatures,
                                perPattern,
                                perPatternLimit)) {
                    break;
                }
            }
        }
        for (CompletionScored candidate : ordered) {
            if (selected.size() >= limit) {
                break;
            }
            acceptCompletion(
                    candidate.column(),
                    selected,
                    signatures,
                    perPattern,
                    perPatternLimit);
        }
        return List.copyOf(selected);
    }

    private static boolean acceptCompletion(
            GroupColumn column,
            List<GroupColumn> selected,
            Set<String> signatures,
            Map<String, Integer> perPattern,
            int perPatternLimit) {
        if (signatures.contains(column.signature())
                || perPattern.getOrDefault(column.pattern().signature(), 0)
                        >= perPatternLimit) {
            return false;
        }
        selected.add(column);
        signatures.add(column.signature());
        perPattern.merge(column.pattern().signature(), 1, Integer::sum);
        return true;
    }

    List<Integer> candidateCars(PatternCandidate pattern) {
        return candidateGenerator.candidateCars(pattern);
    }

    private void observeLocalTargets(
            OrderGroupColumnCandidateAuditTracker tracker,
            List<GroupColumn> targets,
            int width,
            LocalOptionSelection selection) {
        for (GroupColumn target : targets) {
            List<String> messages = target.stationConfig().get(width);
            if (messages == null) {
                continue;
            }
            String signature = String.join("\u001f", messages);
            int fullIndex = indexOfLocal(selection.allStableOrdered(), signature);
            boolean retained = indexOfLocal(selection.selected(), signature) >= 0;
            tracker.observeLocal(
                    target,
                    width,
                    fullIndex >= 0,
                    fullIndex < 0 ? 0 : fullIndex + 1,
                    retained,
                    options.maxLocalConfigsPerWidth());
        }
    }

    private static int indexOfLocal(
            List<LocalOption> options, String signature) {
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).signature().equals(signature)) {
                return index;
            }
        }
        return -1;
    }

    private void observeBeamTargets(
            OrderGroupColumnCandidateAuditTracker tracker,
            List<GroupColumn> targets,
            int width,
            Set<Integer> processedWidths,
            BeamExpansion expansion) {
        for (GroupColumn target : targets) {
            Map<Integer, List<String>> expected = new TreeMap<>();
            target.stationConfig().forEach((candidateWidth, messages) -> {
                if (processedWidths.contains(candidateWidth)) {
                    expected.put(candidateWidth, messages);
                }
            });
            int fullIndex = indexOfBeam(expansion.allOrdered(), expected);
            boolean retained = indexOfBeam(expansion.selected(), expected) >= 0;
            tracker.observeBeam(
                    target,
                    width,
                    fullIndex < 0 ? 0 : fullIndex + 1,
                    retained,
                    options.beamWidth());
        }
    }

    private static int indexOfBeam(
            List<BeamState> states,
            Map<Integer, List<String>> expected) {
        for (int index = 0; index < states.size(); index++) {
            if (states.get(index).stationConfig().equals(expected)) {
                return index;
            }
        }
        return -1;
    }

    private record ScoredColumn(
            GroupColumn column,
            double rawReducedCost,
            double stableReducedCost) {

        private static final Comparator<ScoredColumn> STABLE_ORDER = Comparator
                .comparingDouble(ScoredColumn::stableReducedCost)
                .thenComparingDouble(ScoredColumn::rawReducedCost)
                .thenComparing(scored -> scored.column().signature());

        private static final Comparator<ScoredColumn> WORST_FIRST =
                STABLE_ORDER.reversed();
    }

    private record CompletionScored(
            GroupColumn column,
            int closureCount,
            double reducedCost) {

        private static final Comparator<CompletionScored> BEST_FIRST = Comparator
                .comparingInt(CompletionScored::closureCount).reversed()
                .thenComparingDouble(CompletionScored::reducedCost)
                .thenComparing(Comparator.comparingInt(
                        (CompletionScored scored) -> scored.column().cars()).reversed())
                .thenComparing(scored -> scored.column().signature());
        private static final Comparator<CompletionScored> WORST_FIRST =
                BEST_FIRST.reversed();
    }

    private static final class BoundedCollector {
        private final int capacityPerRole;
        private final Map<ColumnRole, PriorityQueue<ScoredColumn>> queues =
                new EnumMap<>(ColumnRole.class);

        private BoundedCollector(int capacityPerRole) {
            this.capacityPerRole = capacityPerRole;
        }

        void offer(ScoredColumn candidate) {
            PriorityQueue<ScoredColumn> queue = queues.computeIfAbsent(
                    candidate.column().role(),
                    ignored -> new PriorityQueue<>(ScoredColumn.WORST_FIRST));
            if (queue.size() < capacityPerRole) {
                queue.offer(candidate);
                return;
            }
            ScoredColumn worst = queue.peek();
            if (ScoredColumn.STABLE_ORDER.compare(candidate, worst) < 0) {
                queue.poll();
                queue.offer(candidate);
            }
        }

        Collection<ScoredColumn> values() {
            List<ScoredColumn> values = new ArrayList<>();
            for (PriorityQueue<ScoredColumn> queue : queues.values()) {
                values.addAll(queue);
            }
            values.sort(ScoredColumn.STABLE_ORDER);
            return List.copyOf(values);
        }
    }

    private static final class BoundedCompletionCollector {
        private final int capacity;
        private final PriorityQueue<CompletionScored> queue =
                new PriorityQueue<>(CompletionScored.WORST_FIRST);

        private BoundedCompletionCollector(int capacity) {
            this.capacity = capacity;
        }

        void offer(CompletionScored candidate) {
            if (queue.size() < capacity) {
                queue.offer(candidate);
                return;
            }
            CompletionScored worst = queue.peek();
            if (CompletionScored.BEST_FIRST.compare(candidate, worst) < 0) {
                queue.poll();
                queue.offer(candidate);
            }
        }

        List<CompletionScored> values() {
            return queue.stream().sorted(CompletionScored.BEST_FIRST).toList();
        }
    }

    record PricingResult(List<GroupColumn> columns, Diagnostics diagnostics) {
        PricingResult {
            columns = List.copyOf(columns);
        }
    }

    record CompletionResult(List<GroupColumn> columns, Diagnostics diagnostics) {
        CompletionResult {
            columns = List.copyOf(columns);
        }
    }

    record Diagnostics(
            long scannedPatterns,
            long carCandidates,
            long localConfigurations,
            long beamConfigurations,
            long pricedCandidates,
            long invalidConfigurations,
            long duplicateSignatures,
            long negativeReducedCostColumns,
            long retainedCandidates,
            long returnedColumns,
            long completionCandidates,
            long completionReturnedColumns,
            boolean deadlineReached) {

        static Diagnostics empty() {
            return new Diagnostics(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }

        Diagnostics plus(Diagnostics other) {
            return new Diagnostics(
                    scannedPatterns + other.scannedPatterns,
                    carCandidates + other.carCandidates,
                    localConfigurations + other.localConfigurations,
                    beamConfigurations + other.beamConfigurations,
                    pricedCandidates + other.pricedCandidates,
                    invalidConfigurations + other.invalidConfigurations,
                    duplicateSignatures + other.duplicateSignatures,
                    negativeReducedCostColumns + other.negativeReducedCostColumns,
                    retainedCandidates + other.retainedCandidates,
                    returnedColumns + other.returnedColumns,
                    completionCandidates + other.completionCandidates,
                    completionReturnedColumns + other.completionReturnedColumns,
                    deadlineReached || other.deadlineReached);
        }
    }

    private static final class MutableDiagnostics {
        long scannedPatterns;
        long carCandidates;
        long localConfigurations;
        long beamConfigurations;
        long pricedCandidates;
        long invalidConfigurations;
        long duplicateSignatures;
        long negativeReducedCostColumns;
        long retainedCandidates;
        long returnedColumns;
        long completionCandidates;
        long completionReturnedColumns;
        boolean deadlineReached;

        Diagnostics freeze() {
            return new Diagnostics(
                    scannedPatterns,
                    carCandidates,
                    localConfigurations,
                    beamConfigurations,
                    pricedCandidates,
                    invalidConfigurations,
                    duplicateSignatures,
                    negativeReducedCostColumns,
                    retainedCandidates,
                    returnedColumns,
                    completionCandidates,
                    completionReturnedColumns,
                    deadlineReached);
        }
    }
}
