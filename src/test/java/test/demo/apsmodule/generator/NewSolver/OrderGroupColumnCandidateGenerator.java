package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DualVector;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Generates bounded car-count, local order-config and beam candidates.
 */
final class OrderGroupColumnCandidateGenerator {

    private static final List<Integer> PREFERRED_CARS =
            List.of(10, 8, 6, 4, 3, 2);

    private final Input input;
    private final Options options;
    private final Map<LocalCacheKey, List<LocalStructure>> localStructureCache =
            new HashMap<>();
    private final Map<String, List<Integer>> carCandidateCache =
            new HashMap<>();

    OrderGroupColumnCandidateGenerator(Input input, Options options) {
        this.input = input;
        this.options = options;
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

        for (Map.Entry<Integer, Integer> cut :
                pattern.getPattern().entrySet()) {
            int slots = cut.getValue();
            for (DemandKey order : input.ordersByWidth().get(cut.getKey())) {
                int demand = input.demand().get(order);
                for (int multiplicity = 1;
                        multiplicity <= slots;
                        multiplicity++) {
                    int quotient = demand / multiplicity;
                    addNeighborhood(candidates, quotient, globalMax);
                    if (demand % multiplicity == 0) {
                        addNeighborhood(
                                candidates, demand / multiplicity, globalMax);
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
                        .thenComparingInt(
                                OrderGroupColumnCandidateGenerator::preferredRank)
                        .thenComparingInt(cars -> cars % 2)
                        .thenComparing(Comparator.reverseOrder()))
                .limit(options.maxCarCandidatesPerPattern())
                .sorted()
                .toList();
        return List.copyOf(ranked);
    }

    private int closurePotential(PatternCandidate pattern, int cars) {
        int potential = 0;
        for (Map.Entry<Integer, Integer> cut :
                pattern.getPattern().entrySet()) {
            for (DemandKey key : input.ordersByWidth().get(cut.getKey())) {
                int demand = input.demand().get(key);
                for (int multiplicity = 1;
                        multiplicity <= cut.getValue();
                        multiplicity++) {
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

    private static void addCandidate(
            Set<Integer> candidates, int value, int max) {
        if (value >= 1 && value <= max) {
            candidates.add(value);
        }
    }

    LocalOptionSelection localOptionSelection(
            int width,
            int slots,
            int cars,
            DualVector rawDual,
            DualVector stableDual) {
        List<LocalStructure> structures = localStructureCache.computeIfAbsent(
                new LocalCacheKey(width, slots, cars),
                ignored -> enumerateLocalStructures(width, slots, cars));
        if (structures.isEmpty()) {
            return new LocalOptionSelection(List.of(), List.of());
        }

        List<LocalOption> scored = new ArrayList<>(structures.size());
        for (LocalStructure structure : structures) {
            double rawScore = 0.0;
            double stableScore = 0.0;
            for (Map.Entry<DemandKey, Integer> entry :
                    structure.multiplicity().entrySet()) {
                int coverage = Math.multiplyExact(entry.getValue(), cars);
                rawScore += rawDual.demand()
                        .getOrDefault(entry.getKey(), 0.0) * coverage;
                stableScore += stableDual.demand()
                        .getOrDefault(entry.getKey(), 0.0) * coverage;
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
        List<LocalOption> stableOrdered =
                scored.stream().sorted(stableOrder).toList();
        for (LocalOption option : stableOrdered) {
            selected.putIfAbsent(option.signature(), option);
            if (selected.size() >= options.maxLocalConfigsPerWidth()) {
                break;
            }
        }
        List<LocalOption> retained = selected.values().stream()
                .limit(options.maxLocalConfigsPerWidth())
                .sorted(stableOrder)
                .toList();
        return new LocalOptionSelection(stableOrdered, retained);
    }

    private static void addFirst(
            Map<String, LocalOption> selected,
            List<LocalOption> ordered) {
        if (!ordered.isEmpty()) {
            LocalOption first = ordered.get(0);
            selected.putIfAbsent(first.signature(), first);
        }
    }

    private List<LocalStructure> enumerateLocalStructures(
            int width, int slots, int cars) {
        List<DemandKey> orders =
                input.ordersByWidth().getOrDefault(width, List.of());
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

    List<BeamState> extendBeam(
            List<BeamState> current,
            int width,
            List<LocalOption> localOptions) {
        return extendBeamDetailed(current, width, localOptions).selected();
    }

    BeamExpansion extendBeamDetailed(
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
                        state.signature() + "|" + width
                                + "=" + option.signature()));
            }
        }
        List<BeamState> ordered = expanded.stream()
                .sorted(BeamState.ORDER)
                .toList();
        List<BeamState> selected = ordered.stream()
                .limit(options.beamWidth())
                .toList();
        return new BeamExpansion(ordered, selected);
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

    record LocalOption(
            List<String> messages,
            double rawScore,
            double stableScore,
            int closureCount,
            int distinctMessages,
            String signature) {
    }

    record LocalOptionSelection(
            List<LocalOption> allStableOrdered,
            List<LocalOption> selected) {
    }

    record BeamState(
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

    record BeamExpansion(
            List<BeamState> allOrdered,
            List<BeamState> selected) {
    }
}
