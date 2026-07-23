package test.demo.apsmodule.generator.NewSolver;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Finite pricing oracle: scans every available cutting-pattern skeleton but
 * materializes only a bounded number of order-level group columns.
 */
final class OrderGroupColumnPricingOracle {

    private static final List<Integer> PREFERRED_CARS = List.of(10, 8, 6, 4, 3, 2);

    private final Input input;
    private final Options options;
    private final Map<LocalCacheKey, List<LocalStructure>> localStructureCache =
            new HashMap<>();
    private final Map<String, List<Integer>> carCandidateCache = new HashMap<>();

    OrderGroupColumnPricingOracle(Input input, Options options) {
        this.input = Objects.requireNonNull(input, "input");
        this.options = Objects.requireNonNull(options, "options");
    }

    PricingResult price(
            Phase phase,
            DualVector rawDual,
            DualVector stableDual,
            ColumnPool pool,
            long deadlineMs) {
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
            diagnostics.carCandidates += cars.size();
            List<ScoredColumn> patternCandidates = new ArrayList<>();
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
                    List<LocalOption> localOptions = localOptions(
                            cut.getKey(),
                            cut.getValue(),
                            carCount,
                            rawDual,
                            stableDual);
                    diagnostics.localConfigurations += localOptions.size();
                    if (localOptions.isEmpty()) {
                        feasible = false;
                        break;
                    }
                    beam = extendBeam(beam, cut.getKey(), localOptions);
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
                    if (pool.contains(column.signature())) {
                        diagnostics.duplicateSignatures++;
                        continue;
                    }
                    double rawReducedCost =
                            OrderGroupColumnPricingPrototype.rawReducedCost(
                                    column, rawDual, phase);
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
            for (int index = 0; index < patternLimit; index++) {
                ScoredColumn scored = patternCandidates.get(index);
                collector.offer(scored);
                rawReducedCosts.put(scored.column().signature(), scored.rawReducedCost());
            }
        }

        List<GroupColumn> retained = collector.values().stream()
                .map(ScoredColumn::column)
                .toList();
        List<GroupColumn> selected =
                OrderGroupColumnPricingPrototype.roleDiverseSelection(
                        retained,
                        options.maxAddedPerIteration(),
                        options.maxPerPatternPerIteration(),
                        rawReducedCosts);
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
                    List<LocalOption> localOptions = localOptions(
                            cut.getKey(),
                            cut.getValue(),
                            carCount,
                            referenceDual,
                            referenceDual);
                    diagnostics.localConfigurations += localOptions.size();
                    if (localOptions.isEmpty()) {
                        feasible = false;
                        break;
                    }
                    beam = extendBeam(beam, cut.getKey(), localOptions);
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
        return carCandidateCache.computeIfAbsent(
                pattern.signature(), ignored -> buildCandidateCars(pattern));
    }

    private List<Integer> buildCandidateCars(PatternCandidate pattern) {
        TreeSet<Integer> candidates = new TreeSet<>();
        int globalMax = input.exactCars();
        for (int width : pattern.getPattern().keySet()) {
            int widthMax = input.ordersByWidth().get(width).stream()
                    .mapToInt(key -> input.demand().get(key))
                    .max()
                    .orElse(0);
            globalMax = Math.min(globalMax, widthMax);
        }
        if (input.exactOneCarGroups() > 0) {
            candidates.add(1);
        }
        for (int preferred : PREFERRED_CARS) {
            addCandidate(candidates, preferred, globalMax);
        }

        for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
            int slots = cut.getValue();
            for (DemandKey order : input.ordersByWidth().get(cut.getKey())) {
                int demand = input.demand().get(order);
                for (int multiplicity = 1; multiplicity <= slots; multiplicity++) {
                    int quotient = demand / multiplicity;
                    addNeighborhood(candidates, quotient, globalMax);
                    if (demand % multiplicity == 0) {
                        addNeighborhood(candidates, demand / multiplicity, globalMax);
                    }
                }
            }
        }
        addNeighborhood(candidates, globalMax, globalMax);
        if (input.exactOneCarGroups() == 0) {
            candidates.remove(1);
        }
        if (input.exactOddGroups() == 0) {
            candidates.removeIf(cars -> cars % 2 != 0);
        }

        if (candidates.size() <= options.maxCarCandidatesPerPattern()) {
            return List.copyOf(candidates);
        }

        List<Integer> ranked = candidates.stream()
                .sorted(Comparator
                        .comparingInt((Integer cars) ->
                                -closurePotential(pattern, cars))
                        .thenComparingInt(cars -> preferredRank(cars))
                        .thenComparingInt(cars -> cars % 2)
                        .thenComparing(Comparator.reverseOrder()))
                .limit(options.maxCarCandidatesPerPattern())
                .sorted()
                .toList();
        return List.copyOf(ranked);
    }

    private int closurePotential(PatternCandidate pattern, int cars) {
        int potential = 0;
        for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
            for (DemandKey key : input.ordersByWidth().get(cut.getKey())) {
                int demand = input.demand().get(key);
                for (int multiplicity = 1; multiplicity <= cut.getValue(); multiplicity++) {
                    if (multiplicity * cars == demand) {
                        potential++;
                    }
                }
            }
        }
        return potential;
    }

    private static int preferredRank(int cars) {
        int index = PREFERRED_CARS.indexOf(cars);
        return index < 0 ? PREFERRED_CARS.size() : index;
    }

    private static void addNeighborhood(
            Set<Integer> candidates, int value, int max) {
        addCandidate(candidates, value, max);
        addCandidate(candidates, value - 1, max);
        addCandidate(candidates, value + 1, max);
        if (value % 2 == 0) {
            addCandidate(candidates, value, max);
        } else {
            addCandidate(candidates, value - 1, max);
            addCandidate(candidates, value + 1, max);
        }
    }

    private static void addCandidate(Set<Integer> candidates, int value, int max) {
        if (value >= 1 && value <= max) {
            candidates.add(value);
        }
    }

    private List<LocalOption> localOptions(
            int width,
            int slots,
            int cars,
            DualVector rawDual,
            DualVector stableDual) {
        List<LocalStructure> structures = localStructureCache.computeIfAbsent(
                new LocalCacheKey(width, slots, cars),
                ignored -> enumerateLocalStructures(width, slots, cars));
        if (structures.isEmpty()) {
            return List.of();
        }

        List<LocalOption> scored = new ArrayList<>(structures.size());
        for (LocalStructure structure : structures) {
            double rawScore = 0.0;
            double stableScore = 0.0;
            for (Map.Entry<DemandKey, Integer> entry : structure.multiplicity().entrySet()) {
                int coverage = Math.multiplyExact(entry.getValue(), cars);
                rawScore += rawDual.demand().getOrDefault(entry.getKey(), 0.0) * coverage;
                stableScore += stableDual.demand().getOrDefault(entry.getKey(), 0.0)
                        * coverage;
            }
            scored.add(new LocalOption(
                    structure.messages(),
                    rawScore,
                    stableScore,
                    structure.closureCount(),
                    structure.distinctMessages(),
                    structure.signature()));
        }

        Comparator<LocalOption> stableOrder = Comparator
                .comparingDouble(LocalOption::stableScore).reversed()
                .thenComparing(Comparator.comparingInt(
                        LocalOption::closureCount).reversed())
                .thenComparingInt(LocalOption::distinctMessages)
                .thenComparing(LocalOption::signature);
        Comparator<LocalOption> rawOrder = Comparator
                .comparingDouble(LocalOption::rawScore).reversed()
                .thenComparing(stableOrder);
        Comparator<LocalOption> closureOrder = Comparator
                .comparingInt(LocalOption::closureCount).reversed()
                .thenComparing(stableOrder);

        LinkedHashMap<String, LocalOption> selected = new LinkedHashMap<>();
        addFirst(selected, scored.stream().sorted(rawOrder).toList());
        addFirst(selected, scored.stream().sorted(closureOrder).toList());
        addFirst(selected, scored.stream()
                .filter(option -> option.distinctMessages() == 1)
                .sorted(stableOrder)
                .toList());
        for (LocalOption option : scored.stream().sorted(stableOrder).toList()) {
            selected.putIfAbsent(option.signature(), option);
            if (selected.size() >= options.maxLocalConfigsPerWidth()) {
                break;
            }
        }
        return selected.values().stream()
                .limit(options.maxLocalConfigsPerWidth())
                .sorted(stableOrder)
                .toList();
    }

    private static void addFirst(
            Map<String, LocalOption> selected, List<LocalOption> ordered) {
        if (!ordered.isEmpty()) {
            LocalOption first = ordered.get(0);
            selected.putIfAbsent(first.signature(), first);
        }
    }

    private List<LocalStructure> enumerateLocalStructures(
            int width, int slots, int cars) {
        List<DemandKey> orders = input.ordersByWidth().getOrDefault(width, List.of());
        if (orders.isEmpty()) {
            return List.of();
        }
        List<LocalStructure> result = new ArrayList<>();
        int[] counts = new int[orders.size()];
        enumerateCounts(orders, cars, 0, slots, counts, result);
        result.sort(Comparator.comparing(LocalStructure::signature));
        return List.copyOf(result);
    }

    private void enumerateCounts(
            List<DemandKey> orders,
            int cars,
            int orderIndex,
            int remainingSlots,
            int[] counts,
            List<LocalStructure> result) {
        if (orderIndex == orders.size() - 1) {
            counts[orderIndex] = remainingSlots;
            addLocalStructureIfFeasible(orders, cars, counts, result);
            counts[orderIndex] = 0;
            return;
        }
        DemandKey key = orders.get(orderIndex);
        int maxByDemand = input.demand().get(key) / cars;
        int max = Math.min(remainingSlots, maxByDemand);
        for (int count = 0; count <= max; count++) {
            counts[orderIndex] = count;
            enumerateCounts(
                    orders,
                    cars,
                    orderIndex + 1,
                    remainingSlots - count,
                    counts,
                    result);
        }
        counts[orderIndex] = 0;
    }

    private void addLocalStructureIfFeasible(
            List<DemandKey> orders,
            int cars,
            int[] counts,
            List<LocalStructure> result) {
        Map<DemandKey, Integer> multiplicity = new TreeMap<>();
        List<String> messages = new ArrayList<>();
        int closureCount = 0;
        for (int index = 0; index < orders.size(); index++) {
            int count = counts[index];
            if (count <= 0) {
                continue;
            }
            DemandKey key = orders.get(index);
            int coverage = Math.multiplyExact(count, cars);
            if (coverage > input.demand().get(key)) {
                return;
            }
            multiplicity.put(key, count);
            if (coverage == input.demand().get(key)) {
                closureCount++;
            }
            for (int occurrence = 0; occurrence < count; occurrence++) {
                messages.add(key.messageText());
            }
        }
        if (messages.isEmpty()) {
            return;
        }
        messages.sort(String::compareTo);
        String signature = String.join("\u001f", messages);
        result.add(new LocalStructure(
                List.copyOf(messages),
                Collections.unmodifiableMap(multiplicity),
                closureCount,
                multiplicity.size(),
                signature));
    }

    private List<BeamState> extendBeam(
            List<BeamState> current,
            int width,
            List<LocalOption> localOptions) {
        List<BeamState> expanded = new ArrayList<>(
                current.size() * localOptions.size());
        for (BeamState state : current) {
            for (LocalOption option : localOptions) {
                Map<Integer, List<String>> config =
                        new TreeMap<>(state.stationConfig());
                config.put(width, option.messages());
                expanded.add(new BeamState(
                        Collections.unmodifiableMap(config),
                        state.rawScore() + option.rawScore(),
                        state.stableScore() + option.stableScore(),
                        state.closureCount() + option.closureCount(),
                        state.signature() + "|" + width + "=" + option.signature()));
            }
        }
        return expanded.stream()
                .sorted(BeamState.ORDER)
                .limit(options.beamWidth())
                .toList();
    }

    private record LocalCacheKey(int width, int slots, int cars) {
    }

    private record LocalStructure(
            List<String> messages,
            Map<DemandKey, Integer> multiplicity,
            int closureCount,
            int distinctMessages,
            String signature) {
    }

    private record LocalOption(
            List<String> messages,
            double rawScore,
            double stableScore,
            int closureCount,
            int distinctMessages,
            String signature) {
    }

    private record BeamState(
            Map<Integer, List<String>> stationConfig,
            double rawScore,
            double stableScore,
            int closureCount,
            String signature) {

        private static final Comparator<BeamState> ORDER = Comparator
                .comparingDouble(BeamState::stableScore).reversed()
                .thenComparing(Comparator.comparingInt(
                        BeamState::closureCount).reversed())
                .thenComparing(BeamState::signature);

        static BeamState empty() {
            return new BeamState(Map.of(), 0.0, 0.0, 0, "");
        }
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
