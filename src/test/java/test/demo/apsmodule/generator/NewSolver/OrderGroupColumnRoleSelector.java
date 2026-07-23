package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ColumnRole;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies deterministic role diversity and per-pattern caps to priced columns.
 */
final class OrderGroupColumnRoleSelector {

    private OrderGroupColumnRoleSelector() {
    }

    static List<GroupColumn> select(
            Collection<GroupColumn> candidates,
            int limit,
            int maxPerPattern,
            Map<String, Double> rawReducedCosts) {
        Comparator<GroupColumn> order = Comparator
                .comparingDouble((GroupColumn column) ->
                        rawReducedCosts.getOrDefault(
                                column.signature(), Double.POSITIVE_INFINITY))
                .thenComparing(GroupColumn::signature);
        Map<ColumnRole, List<GroupColumn>> byRole = new EnumMap<>(ColumnRole.class);
        for (GroupColumn candidate : candidates) {
            byRole.computeIfAbsent(
                    candidate.role(), ignored -> new ArrayList<>()).add(candidate);
        }
        byRole.values().forEach(values -> values.sort(order));

        List<GroupColumn> selected = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        Map<String, Integer> patternCounts = new LinkedHashMap<>();
        for (ColumnRole role : ColumnRole.values()) {
            if (selected.size() >= limit) {
                break;
            }
            List<GroupColumn> roleCandidates = byRole.getOrDefault(role, List.of());
            for (GroupColumn candidate : roleCandidates) {
                if (canSelect(candidate, signatures, patternCounts, maxPerPattern)) {
                    accept(candidate, selected, signatures, patternCounts);
                    break;
                }
            }
        }

        List<GroupColumn> ordered = candidates.stream().sorted(order).toList();
        for (GroupColumn candidate : ordered) {
            if (selected.size() >= limit) {
                break;
            }
            if (canSelect(candidate, signatures, patternCounts, maxPerPattern)) {
                accept(candidate, selected, signatures, patternCounts);
            }
        }
        return List.copyOf(selected);
    }

    private static boolean canSelect(
            GroupColumn candidate,
            Set<String> signatures,
            Map<String, Integer> patternCounts,
            int maxPerPattern) {
        return !signatures.contains(candidate.signature())
                && patternCounts.getOrDefault(
                        candidate.pattern().signature(), 0) < maxPerPattern;
    }

    private static void accept(
            GroupColumn candidate,
            List<GroupColumn> selected,
            Set<String> signatures,
            Map<String, Integer> patternCounts) {
        selected.add(candidate);
        signatures.add(candidate.signature());
        patternCounts.merge(candidate.pattern().signature(), 1, Integer::sum);
    }
}
