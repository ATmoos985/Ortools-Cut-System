package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic incumbent-only destroy-set planner for large-neighborhood
 * residual feasibility search.
 *
 * <p>The score uses only structural information present in the current
 * incumbent. No manual solution, automatic22 witness or historical good
 * signature is accepted as input.</p>
 */
final class OrderGroupDestroySetPlanner {

    private OrderGroupDestroySetPlanner() {
    }

    record Options(
            List<Integer> destroySizes,
            int maxSetsPerSize,
            int beamWidth) {

        Options {
            destroySizes = destroySizes.stream()
                    .distinct()
                    .sorted()
                    .toList();
            if (destroySizes.isEmpty()
                    || destroySizes.stream().anyMatch(size -> size < 2)
                    || maxSetsPerSize <= 0
                    || beamWidth < maxSetsPerSize) {
                throw new IllegalArgumentException(
                        "invalid destroy-set planner options");
            }
        }

        static Options target24Defaults() {
            return new Options(
                    List.of(7, 8, 9, 10, 12, 15, 17),
                    100,
                    800);
        }
    }

    record DestroySet(
            List<Integer> incumbentIndices,
            List<String> signatures,
            int size,
            long score,
            int demandKeyCount,
            int widthCount,
            String reason) {

        DestroySet {
            incumbentIndices = List.copyOf(incumbentIndices);
            signatures = List.copyOf(signatures);
        }
    }

    static List<DestroySet> plan(
            List<GroupColumn> incumbent,
            Options options) {
        Objects.requireNonNull(incumbent, "incumbent");
        Objects.requireNonNull(options, "options");
        if (incumbent.size() < 2) {
            return List.of();
        }
        if (new HashSet<>(incumbent.stream()
                .map(GroupColumn::signature)
                .toList()).size() != incumbent.size()) {
            throw new IllegalArgumentException(
                    "incumbent contains duplicate signatures");
        }

        List<ColumnRef> columns = new ArrayList<>();
        for (int index = 0; index < incumbent.size(); index++) {
            columns.add(new ColumnRef(index, incumbent.get(index)));
        }
        columns.sort(Comparator.comparing(
                ref -> ref.column().signature()));

        int maxSize = Math.min(
                incumbent.size(),
                options.destroySizes().get(
                        options.destroySizes().size() - 1));
        Set<Integer> requested = new HashSet<>(options.destroySizes());
        List<Partial> frontier = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            frontier.add(new Partial(List.of(index), 0L));
        }

        List<DestroySet> result = new ArrayList<>();
        for (int size = 2; size <= maxSize; size++) {
            Map<String, Partial> expanded = new LinkedHashMap<>();
            for (Partial partial : frontier) {
                for (int next = 0; next < columns.size(); next++) {
                    if (partial.ordinals().contains(next)) {
                        continue;
                    }
                    List<Integer> ordinals =
                            new ArrayList<>(partial.ordinals());
                    ordinals.add(next);
                    ordinals.sort(Integer::compareTo);
                    String key = ordinalKey(ordinals);
                    expanded.computeIfAbsent(
                            key,
                            ignored -> new Partial(
                                    List.copyOf(ordinals),
                                    setScore(ordinals, columns)));
                }
            }
            frontier = expanded.values().stream()
                    .sorted(partialComparator())
                    .limit(options.beamWidth())
                    .toList();

            if (requested.contains(size)) {
                frontier.stream()
                        .limit(options.maxSetsPerSize())
                        .map(partial -> toDestroySet(partial, columns))
                        .forEach(result::add);
            }
        }
        return List.copyOf(result);
    }

    private static DestroySet toDestroySet(
            Partial partial,
            List<ColumnRef> columns) {
        List<ColumnRef> selected = partial.ordinals().stream()
                .map(columns::get)
                .toList();
        Set<DemandKey> demandKeys = new TreeSet<>();
        Set<Integer> widths = new TreeSet<>();
        selected.forEach(ref -> {
            demandKeys.addAll(ref.column().coverage().keySet());
            widths.addAll(ref.column().stationConfig().keySet());
        });
        return new DestroySet(
                selected.stream()
                        .map(ColumnRef::originalIndex)
                        .sorted()
                        .toList(),
                selected.stream()
                        .map(ref -> ref.column().signature())
                        .sorted()
                        .toList(),
                selected.size(),
                partial.score(),
                demandKeys.size(),
                widths.size(),
                "CONNECTED_INCUMBENT_OVERLAP");
    }

    private static Comparator<Partial> partialComparator() {
        return Comparator.comparingLong(Partial::score)
                .reversed()
                .thenComparing(partial ->
                        ordinalKey(partial.ordinals()));
    }

    private static long setScore(
            List<Integer> ordinals,
            List<ColumnRef> columns) {
        long score = 0L;
        for (int left = 0; left < ordinals.size(); left++) {
            GroupColumn first =
                    columns.get(ordinals.get(left)).column();
            for (int right = left + 1;
                    right < ordinals.size();
                    right++) {
                GroupColumn second =
                        columns.get(ordinals.get(right)).column();
                score = Math.addExact(score, edgeScore(first, second));
            }
        }
        return score;
    }

    private static long edgeScore(
            GroupColumn first,
            GroupColumn second) {
        long sharedCoverage = 0L;
        for (Map.Entry<DemandKey, Integer> entry
                : first.coverage().entrySet()) {
            Integer other = second.coverage().get(entry.getKey());
            if (other != null) {
                sharedCoverage += Math.min(entry.getValue(), other);
            }
        }
        int sharedWidths = 0;
        for (Integer width : first.stationConfig().keySet()) {
            if (second.stationConfig().containsKey(width)) {
                sharedWidths++;
            }
        }
        long carAffinity =
                Math.max(0, 24 - Math.abs(first.cars() - second.cars()));
        long wasteAffinity = Math.max(
                0, 2_000L - Math.abs(
                        (long) first.totalWaste()
                                - second.totalWaste())) / 20L;
        long parityAnchor =
                first.odd() != second.odd() ? 30L : 0L;
        long familyPenalty =
                first.familySignature().equals(second.familySignature())
                        ? -1_000_000L
                        : 0L;
        return sharedCoverage * 100L
                + sharedWidths * 50L
                + carAffinity * 3L
                + wasteAffinity
                + parityAnchor
                + familyPenalty;
    }

    private static String ordinalKey(List<Integer> ordinals) {
        return ordinals.stream()
                .map(Object::toString)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private record ColumnRef(int originalIndex, GroupColumn column) {
    }

    private record Partial(List<Integer> ordinals, long score) {

        Partial {
            ordinals = List.copyOf(ordinals);
        }
    }
}
