package test.demo.apsmodule.generator.NewSolver.mip;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Estimates the size of a future (pattern, whole-roll configuration) assignment
 * model after pruning configurations that exceed width-message demand.
 */
final class AssignmentConfigurationEstimator {

    private AssignmentConfigurationEstimator() {
    }

    static Estimate estimate(Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> groupItems) {
        Map<Integer, List<MessageDemand>> demandsByWidth = buildDemandsByWidth(groupItems);
        List<PatternEstimate> patternEstimates = new ArrayList<>();

        long totalConfigurations = 0;
        long maxConfigurationsForPattern = 0;
        int maxWidthOptions = 0;
        PatternCandidate largestPattern = null;

        for (Map.Entry<PatternCandidate, Integer> solutionEntry : solution.entrySet()) {
            PatternCandidate pattern = solutionEntry.getKey();
            long configurationsForPattern = 1;
            Map<Integer, Integer> widthOptionCounts = new LinkedHashMap<>();
            boolean feasible = true;

            for (Map.Entry<Integer, Integer> slotEntry : pattern.getPattern().entrySet()) {
                int width = slotEntry.getKey();
                int slots = slotEntry.getValue();
                int optionCount = countDemandPrunedMultisets(
                        demandsByWidth.getOrDefault(width, List.of()), slots);
                widthOptionCounts.put(width, optionCount);
                maxWidthOptions = Math.max(maxWidthOptions, optionCount);

                if (optionCount <= 0) {
                    feasible = false;
                    configurationsForPattern = 0;
                    break;
                }
                configurationsForPattern = saturatedMultiply(configurationsForPattern, optionCount);
            }

            if (feasible) {
                totalConfigurations = saturatedAdd(totalConfigurations, configurationsForPattern);
            }
            if (configurationsForPattern > maxConfigurationsForPattern) {
                maxConfigurationsForPattern = configurationsForPattern;
                largestPattern = pattern;
            }

            patternEstimates.add(new PatternEstimate(
                    pattern,
                    solutionEntry.getValue(),
                    widthOptionCounts,
                    configurationsForPattern,
                    feasible));
        }

        return new Estimate(
                solution.size(),
                totalConfigurations,
                maxConfigurationsForPattern,
                maxWidthOptions,
                largestPattern,
                patternEstimates);
    }

    private static Map<Integer, List<MessageDemand>> buildDemandsByWidth(List<SolverOrderItem> groupItems) {
        Map<Integer, Map<String, Integer>> demandMap = new LinkedHashMap<>();
        for (SolverOrderItem item : groupItems) {
            demandMap
                    .computeIfAbsent(item.getWidth(), ignored -> new LinkedHashMap<>())
                    .merge(item.getMessageText(), item.getDemand(), Integer::sum);
        }

        Map<Integer, List<MessageDemand>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Integer>> widthEntry : demandMap.entrySet()) {
            List<MessageDemand> demands = widthEntry.getValue().entrySet().stream()
                    .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new MessageDemand(entry.getKey(), entry.getValue()))
                    .toList();
            result.put(widthEntry.getKey(), demands);
        }
        return result;
    }

    private static int countDemandPrunedMultisets(List<MessageDemand> demands, int slots) {
        if (slots <= 0 || demands.isEmpty()) {
            return 0;
        }
        return countDemandPrunedMultisets(demands, 0, slots);
    }

    private static int countDemandPrunedMultisets(List<MessageDemand> demands, int index, int remainingSlots) {
        if (index == demands.size()) {
            return remainingSlots == 0 ? 1 : 0;
        }

        MessageDemand demand = demands.get(index);
        int maxCount = Math.min(demand.count(), remainingSlots);
        int total = 0;
        for (int count = 0; count <= maxCount; count++) {
            total += countDemandPrunedMultisets(demands, index + 1, remainingSlots - count);
        }
        return total;
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left != 0 && right > Long.MAX_VALUE / left) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    record Estimate(
            int patternCount,
            long totalConfigurations,
            long maxConfigurationsForPattern,
            int maxWidthOptions,
            PatternCandidate largestPattern,
            List<PatternEstimate> patternEstimates) {

        String scaleBand() {
            if (totalConfigurations < 5_000) {
                return "small";
            }
            if (totalConfigurations <= 50_000) {
                return "medium";
            }
            return "large";
        }

        List<PatternEstimate> largestPatterns(int limit) {
            return patternEstimates.stream()
                    .sorted(Comparator.comparingLong(PatternEstimate::configurationCount).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    record PatternEstimate(
            PatternCandidate pattern,
            int usageCount,
            Map<Integer, Integer> widthOptionCounts,
            long configurationCount,
            boolean feasible) {
    }

    private record MessageDemand(String messageText, int count) {
    }
}
