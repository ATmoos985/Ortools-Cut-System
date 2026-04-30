package test.demo.apsmodule.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Greedy assignment optimizer used by the NewSolver output path.
 *
 * The main objective is to reduce exported sequence groups without breaking the
 * width+message demand totals. It does this by:
 * 1. selecting the largest supportable batch first
 * 2. preferring configurations that have already been used
 * 3. merging identical configuration blocks before materializing assignments
 */
public class OrderAssignmentOptimizer {

    private static final Logger log = LoggerFactory.getLogger(OrderAssignmentOptimizer.class);
    private static final int MAX_CANDIDATES_PER_WIDTH = 3;
    private static final int MAX_CONFIGURATION_OPTIONS_PER_WIDTH = 6;
    private static final long MAX_ENUMERATED_COMBINATIONS = 1000L;

    public static List<StationAssignment> buildTupleBlockAssignments(
            Map<Integer, Integer> pattern,
            int usageCount,
            List<SolverOrderItem> groupItems,
            Map<String, Integer> remainingDemands) {
        return buildTupleBlockAssignments(pattern, usageCount, groupItems, remainingDemands, GreedyStrategy.BATCH_FIRST);
    }

    public static List<StationAssignment> buildTupleBlockAssignments(
            Map<Integer, Integer> pattern,
            int usageCount,
            List<SolverOrderItem> groupItems,
            Map<String, Integer> remainingDemands,
            GreedyStrategy strategy) {

        List<StationAssignment> result = new ArrayList<>();
        if (pattern == null || pattern.isEmpty() || usageCount <= 0 || groupItems == null || groupItems.isEmpty()) {
            return result;
        }

        Map<Integer, List<ItemDemand>> itemsByWidth = buildItemsByWidth(groupItems);
        List<ConfigurationBlock> blocks = new ArrayList<>();
        Map<String, Integer> configurationUsage = new HashMap<>();
        Map<String, Integer> widthMessageUsage = new HashMap<>();

        int assignedRolls = 0;
        while (assignedRolls < usageCount) {
            Map<Integer, List<String>> bestConfig = findBestBatchConfiguration(
                    pattern, itemsByWidth, remainingDemands, configurationUsage, strategy);

            if (bestConfig == null) {
                bestConfig = buildFallbackConfiguration(pattern, itemsByWidth, widthMessageUsage);
                if (bestConfig == null) {
                    log.error("Unable to assign the remaining {} rolls because no candidates are available",
                            usageCount - assignedRolls);
                    break;
                }
                log.info("Using fallback configuration for the remaining {} rolls", usageCount - assignedRolls);
            }

            int maxSupportable = calculateMaxBatchSize(bestConfig, remainingDemands);
            int batchSize;
            if (maxSupportable <= 0) {
                batchSize = chooseBatchSize(bestConfig, usageCount - assignedRolls, usageCount - assignedRolls);
                log.info("Demand is exhausted; forcing over-production assignments for the remaining {} rolls", batchSize);
            } else {
                batchSize = chooseBatchSize(bestConfig,
                        Math.min(usageCount - assignedRolls, maxSupportable),
                        usageCount - assignedRolls);
            }

            if (batchSize <= 0) {
                break;
            }

            if (batchSize <= 0) {
                break;
            }

            Map<Integer, List<String>> normalizedConfig = normalizeConfiguration(bestConfig);
            String signature = configurationSignature(normalizedConfig);
            blocks.add(new ConfigurationBlock(normalizedConfig, batchSize));
            configurationUsage.merge(signature, batchSize, Integer::sum);
            updateWidthMessageUsage(widthMessageUsage, normalizedConfig, batchSize);

            deductDemands(normalizedConfig, batchSize, remainingDemands);
            assignedRolls += batchSize;
        }

        List<ConfigurationBlock> mergedBlocks = mergeBlocks(blocks);
        if (mergedBlocks.size() < blocks.size()) {
            log.info("Merged greedy assignment blocks from {} to {}", blocks.size(), mergedBlocks.size());
        }

        for (ConfigurationBlock block : mergedBlocks) {
            for (int roll = 0; roll < block.count(); roll++) {
                for (Map.Entry<Integer, List<String>> widthEntry : block.configuration().entrySet()) {
                    int width = widthEntry.getKey();
                    for (String messageText : widthEntry.getValue()) {
                        result.add(new StationAssignment(width, messageText));
                    }
                }
            }
        }

        return result;
    }

    private static Map<Integer, List<ItemDemand>> buildItemsByWidth(List<SolverOrderItem> groupItems) {
        Map<Integer, LinkedHashSet<String>> uniqueMessagesByWidth = new LinkedHashMap<>();
        for (SolverOrderItem item : groupItems) {
            uniqueMessagesByWidth
                    .computeIfAbsent(item.getWidth(), key -> new LinkedHashSet<>())
                    .add(item.getMessageText());
        }

        Map<Integer, List<ItemDemand>> itemsByWidth = new LinkedHashMap<>();
        for (Map.Entry<Integer, LinkedHashSet<String>> entry : uniqueMessagesByWidth.entrySet()) {
            List<ItemDemand> items = new ArrayList<>();
            for (String messageText : entry.getValue()) {
                items.add(new ItemDemand(messageText, 0));
            }
            itemsByWidth.put(entry.getKey(), items);
        }
        return itemsByWidth;
    }

    private static Map<Integer, List<String>> buildFallbackConfiguration(
            Map<Integer, Integer> pattern,
            Map<Integer, List<ItemDemand>> itemsByWidth,
            Map<String, Integer> widthMessageUsage) {

        Map<Integer, List<String>> configuration = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> patternEntry : pattern.entrySet()) {
            int width = patternEntry.getKey();
            int slotsNeeded = patternEntry.getValue();
            List<ItemDemand> candidates = itemsByWidth.get(width);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }

            String fallbackMessage = chooseFallbackMessage(width, candidates, widthMessageUsage);
            List<String> messages = new ArrayList<>(slotsNeeded);
            for (int i = 0; i < slotsNeeded; i++) {
                messages.add(fallbackMessage);
            }
            configuration.put(width, messages);
        }

        return configuration;
    }

    private static String chooseFallbackMessage(int width,
            List<ItemDemand> candidates,
            Map<String, Integer> widthMessageUsage) {
        String bestMessage = candidates.get(0).messageText();
        int bestUsage = widthMessageUsage.getOrDefault(widthMessageKey(width, bestMessage), 0);

        for (ItemDemand candidate : candidates) {
            int usage = widthMessageUsage.getOrDefault(widthMessageKey(width, candidate.messageText()), 0);
            if (usage > bestUsage) {
                bestUsage = usage;
                bestMessage = candidate.messageText();
            }
        }

        return bestMessage;
    }

    private static Map<Integer, List<String>> findBestBatchConfiguration(
            Map<Integer, Integer> pattern,
            Map<Integer, List<ItemDemand>> itemsByWidth,
            Map<String, Integer> remainingDemands,
            Map<String, Integer> configurationUsage,
            GreedyStrategy strategy) {

        Map<Integer, List<WidthConfigurationOption>> configurationOptionsByWidth = new LinkedHashMap<>();
        List<Integer> widths = new ArrayList<>();

        for (Map.Entry<Integer, Integer> patternEntry : pattern.entrySet()) {
            int width = patternEntry.getKey();
            int slotsNeeded = patternEntry.getValue();
            List<ItemDemand> candidates = itemsByWidth.get(width);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }

            List<ItemDemand> exactCandidates = new ArrayList<>();
            List<ItemDemand> positiveCandidates = new ArrayList<>();
            for (ItemDemand candidate : candidates) {
                int remaining = getRemainingDemand(remainingDemands, width, candidate.messageText());
                if (remaining >= slotsNeeded) {
                    exactCandidates.add(new ItemDemand(candidate.messageText(), remaining));
                } else if (remaining > 0) {
                    positiveCandidates.add(new ItemDemand(candidate.messageText(), remaining));
                }
            }

            List<ItemDemand> activeCandidates = !exactCandidates.isEmpty() ? exactCandidates : positiveCandidates;
            if (activeCandidates.isEmpty()) {
                return null;
            }

            activeCandidates.sort((left, right) -> Integer.compare(right.currentDemand(), left.currentDemand()));
            List<WidthConfigurationOption> configurationOptions =
                    generateWidthConfigurationOptions(slotsNeeded, activeCandidates);
            if (configurationOptions.isEmpty()) {
                return null;
            }
            configurationOptionsByWidth.put(width, configurationOptions);
            widths.add(width);
        }

        long totalCombinations = 1L;
        int[] maxIndices = new int[widths.size()];
        for (int i = 0; i < widths.size(); i++) {
            int optionCount = configurationOptionsByWidth.get(widths.get(i)).size();
            maxIndices[i] = optionCount;
            totalCombinations *= optionCount;
            if (totalCombinations > MAX_ENUMERATED_COMBINATIONS) {
                return findBestBatchConfigurationGreedy(pattern, configurationOptionsByWidth);
            }
        }

        Map<Integer, List<String>> bestConfiguration = null;
        int bestBatchSupport = 0;
        int bestPriorUsage = -1;
        int bestOddMultiplicityCount = Integer.MAX_VALUE;
        int bestDistinctKeyCount = Integer.MAX_VALUE;

        int[] indices = new int[widths.size()];
        while (true) {
            Map<Integer, List<String>> candidateConfiguration = new LinkedHashMap<>();
            int minBatchSupport = Integer.MAX_VALUE;
            boolean valid = true;

            for (int i = 0; i < widths.size(); i++) {
                int width = widths.get(i);
                WidthConfigurationOption chosen = configurationOptionsByWidth.get(width).get(indices[i]);
                if (chosen.batchSupport() <= 0) {
                    valid = false;
                    break;
                }
                minBatchSupport = Math.min(minBatchSupport, chosen.batchSupport());
                candidateConfiguration.put(width, new ArrayList<>(chosen.messages()));
            }

            if (valid) {
                Map<Integer, List<String>> normalizedConfiguration = normalizeConfiguration(candidateConfiguration);
                String signature = configurationSignature(normalizedConfiguration);
                int priorUsage = configurationUsage.getOrDefault(signature, 0);
                int oddMultiplicityCount = countOddMessageMultiplicities(normalizedConfiguration);
                int distinctKeyCount = countDistinctWidthMessageKeys(normalizedConfiguration);

                if (isBetterConfiguration(
                        strategy,
                        minBatchSupport,
                        priorUsage,
                        oddMultiplicityCount,
                        distinctKeyCount,
                        bestBatchSupport,
                        bestPriorUsage,
                        bestOddMultiplicityCount,
                        bestDistinctKeyCount)) {
                    bestBatchSupport = minBatchSupport;
                    bestPriorUsage = priorUsage;
                    bestOddMultiplicityCount = oddMultiplicityCount;
                    bestDistinctKeyCount = distinctKeyCount;
                    bestConfiguration = normalizedConfiguration;
                }
            }

            int position = widths.size() - 1;
            while (position >= 0) {
                indices[position]++;
                if (indices[position] < maxIndices[position]) {
                    break;
                }
                indices[position] = 0;
                position--;
            }
            if (position < 0) {
                break;
            }
        }

        if (bestConfiguration == null) {
            return findBestBatchConfigurationGreedy(pattern, configurationOptionsByWidth);
        }

        return bestConfiguration;
    }

    private static boolean isBetterConfiguration(
            GreedyStrategy strategy,
            int batchSupport,
            int priorUsage,
            int oddMultiplicityCount,
            int distinctKeyCount,
            int bestBatchSupport,
            int bestPriorUsage,
            int bestOddMultiplicityCount,
            int bestDistinctKeyCount) {
        if (strategy == GreedyStrategy.REUSE_FIRST) {
            boolean reused = priorUsage > 0;
            boolean bestReused = bestPriorUsage > 0;
            if (reused != bestReused) {
                return reused;
            }
            if (oddMultiplicityCount != bestOddMultiplicityCount) {
                return oddMultiplicityCount < bestOddMultiplicityCount;
            }
            if (distinctKeyCount != bestDistinctKeyCount) {
                return distinctKeyCount < bestDistinctKeyCount;
            }
            if (batchSupport != bestBatchSupport) {
                return batchSupport > bestBatchSupport;
            }
            return priorUsage > bestPriorUsage;
        }

        return batchSupport > bestBatchSupport
                || (batchSupport == bestBatchSupport && priorUsage > bestPriorUsage)
                || (batchSupport == bestBatchSupport
                        && priorUsage == bestPriorUsage
                        && oddMultiplicityCount < bestOddMultiplicityCount)
                || (batchSupport == bestBatchSupport
                        && priorUsage == bestPriorUsage
                        && oddMultiplicityCount == bestOddMultiplicityCount
                        && distinctKeyCount < bestDistinctKeyCount);
    }

    private static Map<Integer, List<String>> findBestBatchConfigurationGreedy(
            Map<Integer, Integer> pattern,
            Map<Integer, List<WidthConfigurationOption>> configurationOptionsByWidth) {

        Map<Integer, List<String>> configuration = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> patternEntry : pattern.entrySet()) {
            int width = patternEntry.getKey();
            List<WidthConfigurationOption> options = configurationOptionsByWidth.get(width);
            if (options == null || options.isEmpty()) {
                return null;
            }

            configuration.put(width, new ArrayList<>(options.get(0).messages()));
        }
        return normalizeConfiguration(configuration);
    }

    static int chooseBatchSize(
            Map<Integer, List<String>> configuration,
            int upperBound,
            int remainingRolls) {
        if (upperBound <= 0 || remainingRolls <= 0) {
            return 0;
        }

        int cappedUpperBound = Math.min(upperBound, remainingRolls);
        boolean preferEvenBatch = countOddMessageMultiplicities(configuration) > 0;

        int bestBatchSize = 0;
        int bestLeaveSingletonPenalty = Integer.MAX_VALUE;
        int bestParityPenalty = Integer.MAX_VALUE;

        for (int batchSize = cappedUpperBound; batchSize >= 1; batchSize--) {
            int remainder = remainingRolls - batchSize;
            int leaveSingletonPenalty = remainder == 1 ? 1 : 0;
            int parityPenalty = preferEvenBatch && batchSize % 2 != 0 ? 1 : 0;

            if (leaveSingletonPenalty < bestLeaveSingletonPenalty
                    || (leaveSingletonPenalty == bestLeaveSingletonPenalty && parityPenalty < bestParityPenalty)
                    || (leaveSingletonPenalty == bestLeaveSingletonPenalty
                            && parityPenalty == bestParityPenalty
                            && batchSize > bestBatchSize)) {
                bestBatchSize = batchSize;
                bestLeaveSingletonPenalty = leaveSingletonPenalty;
                bestParityPenalty = parityPenalty;
            }
        }

        return bestBatchSize;
    }

    private static int calculateMaxBatchSize(
            Map<Integer, List<String>> configuration,
            Map<String, Integer> remainingDemands) {

        Map<DemandRef, Integer> usagePerRoll = new HashMap<>();
        for (Map.Entry<Integer, List<String>> widthEntry : configuration.entrySet()) {
            int width = widthEntry.getKey();
            for (String messageText : widthEntry.getValue()) {
                usagePerRoll.merge(new DemandRef(width, messageText), 1, Integer::sum);
            }
        }

        int maxBatch = Integer.MAX_VALUE;
        for (Map.Entry<DemandRef, Integer> usageEntry : usagePerRoll.entrySet()) {
            DemandRef demandRef = usageEntry.getKey();
            int needPerRoll = usageEntry.getValue();
            int remaining = getRemainingDemand(remainingDemands, demandRef.width(), demandRef.messageText());
            if (needPerRoll > 0) {
                maxBatch = Math.min(maxBatch, remaining / needPerRoll);
            }
        }
        return maxBatch;
    }

    private static void deductDemands(
            Map<Integer, List<String>> configuration,
            int batchSize,
            Map<String, Integer> remainingDemands) {

        for (Map.Entry<Integer, List<String>> widthEntry : configuration.entrySet()) {
            int width = widthEntry.getKey();
            for (String messageText : widthEntry.getValue()) {
                int current = getRemainingDemand(remainingDemands, width, messageText);
                setRemainingDemand(remainingDemands, width, messageText, current - batchSize);
            }
        }
    }

    private static List<ConfigurationBlock> mergeBlocks(List<ConfigurationBlock> blocks) {
        LinkedHashMap<String, ConfigurationBlock> merged = new LinkedHashMap<>();
        for (ConfigurationBlock block : blocks) {
            String signature = configurationSignature(block.configuration());
            ConfigurationBlock existing = merged.get(signature);
            if (existing == null) {
                merged.put(signature, block);
            } else {
                merged.put(signature, new ConfigurationBlock(existing.configuration(), existing.count() + block.count()));
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static List<WidthConfigurationOption> generateWidthConfigurationOptions(
            int slotsNeeded,
            List<ItemDemand> candidates) {
        if (slotsNeeded <= 0 || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<ItemDemand> limitedCandidates = candidates.subList(0, Math.min(candidates.size(), MAX_CANDIDATES_PER_WIDTH));
        List<WidthConfigurationOption> options = new ArrayList<>();
        int[] counts = new int[limitedCandidates.size()];
        enumerateWidthConfigurationOptions(limitedCandidates, slotsNeeded, 0, slotsNeeded, counts, options);

        options.sort(Comparator
                .comparingInt(WidthConfigurationOption::batchSupport).reversed()
                .thenComparingInt(WidthConfigurationOption::oddMultiplicityCount)
                .thenComparingInt(WidthConfigurationOption::distinctMessageCount)
                .thenComparing(option -> String.join(",", option.messages())));

        if (options.size() > MAX_CONFIGURATION_OPTIONS_PER_WIDTH) {
            return new ArrayList<>(options.subList(0, MAX_CONFIGURATION_OPTIONS_PER_WIDTH));
        }
        return options;
    }

    private static void enumerateWidthConfigurationOptions(
            List<ItemDemand> candidates,
            int slotsNeeded,
            int index,
            int remainingSlots,
            int[] counts,
            List<WidthConfigurationOption> options) {
        if (index == candidates.size() - 1) {
            counts[index] = remainingSlots;
            addWidthConfigurationOption(candidates, counts, options);
            counts[index] = 0;
            return;
        }

        for (int count = remainingSlots; count >= 0; count--) {
            counts[index] = count;
            enumerateWidthConfigurationOptions(candidates, slotsNeeded, index + 1, remainingSlots - count, counts, options);
        }
        counts[index] = 0;
    }

    private static void addWidthConfigurationOption(
            List<ItemDemand> candidates,
            int[] counts,
            List<WidthConfigurationOption> options) {
        List<String> messages = new ArrayList<>();
        int batchSupport = Integer.MAX_VALUE;
        int distinctMessageCount = 0;
        int oddMultiplicityCount = 0;

        for (int index = 0; index < counts.length; index++) {
            int count = counts[index];
            if (count <= 0) {
                continue;
            }

            ItemDemand candidate = candidates.get(index);
            batchSupport = Math.min(batchSupport, candidate.currentDemand() / count);
            distinctMessageCount++;
            if (count % 2 != 0) {
                oddMultiplicityCount++;
            }
            for (int slot = 0; slot < count; slot++) {
                messages.add(candidate.messageText());
            }
        }

        if (messages.isEmpty() || batchSupport <= 0) {
            return;
        }

        messages.sort(String::compareTo);
        options.add(new WidthConfigurationOption(messages, batchSupport, distinctMessageCount, oddMultiplicityCount));
    }

    private static Map<Integer, List<String>> normalizeConfiguration(Map<Integer, List<String>> configuration) {
        List<Integer> widths = new ArrayList<>(configuration.keySet());
        Collections.sort(widths);

        Map<Integer, List<String>> normalized = new LinkedHashMap<>();
        for (int width : widths) {
            List<String> messages = new ArrayList<>(configuration.getOrDefault(width, Collections.emptyList()));
            messages.sort(String::compareTo);
            normalized.put(width, messages);
        }
        return normalized;
    }

    private static String configurationSignature(Map<Integer, List<String>> configuration) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : configuration.entrySet()) {
            parts.add(entry.getKey() + "=" + String.join(",", entry.getValue()));
        }
        return String.join("|", parts);
    }

    private static int countDistinctWidthMessageKeys(Map<Integer, List<String>> configuration) {
        int total = 0;
        for (List<String> messages : configuration.values()) {
            total += new LinkedHashSet<>(messages).size();
        }
        return total;
    }

    private static int countOddMessageMultiplicities(Map<Integer, List<String>> configuration) {
        int oddCount = 0;
        for (List<String> messages : configuration.values()) {
            Map<String, Integer> multiplicities = new LinkedHashMap<>();
            for (String messageText : messages) {
                multiplicities.merge(messageText, 1, Integer::sum);
            }
            for (int multiplicity : multiplicities.values()) {
                if (multiplicity % 2 != 0) {
                    oddCount++;
                }
            }
        }
        return oddCount;
    }

    private static void updateWidthMessageUsage(Map<String, Integer> widthMessageUsage,
            Map<Integer, List<String>> configuration,
            int batchSize) {
        for (Map.Entry<Integer, List<String>> widthEntry : configuration.entrySet()) {
            int width = widthEntry.getKey();
            for (String messageText : widthEntry.getValue()) {
                widthMessageUsage.merge(widthMessageKey(width, messageText), batchSize, Integer::sum);
            }
        }
    }

    private static int getRemainingDemand(Map<String, Integer> remainingDemands, int width, String messageText) {
        String widthAwareKey = demandKey(width, messageText);
        if (remainingDemands.containsKey(widthAwareKey)) {
            return remainingDemands.getOrDefault(widthAwareKey, 0);
        }
        return remainingDemands.getOrDefault(messageText, 0);
    }

    private static void setRemainingDemand(Map<String, Integer> remainingDemands, int width, String messageText, int value) {
        String widthAwareKey = demandKey(width, messageText);
        int sanitized = Math.max(0, value);
        if (remainingDemands.containsKey(widthAwareKey)) {
            remainingDemands.put(widthAwareKey, sanitized);
        } else {
            remainingDemands.put(messageText, sanitized);
        }
    }

    private static String widthMessageKey(int width, String messageText) {
        return width + "|" + messageText;
    }

    private static String demandKey(int width, String messageText) {
        return width + "|" + messageText;
    }

    private record DemandRef(int width, String messageText) {
    }

    private record ItemDemand(String messageText, int currentDemand) {
    }

    private record WidthConfigurationOption(
            List<String> messages,
            int batchSupport,
            int distinctMessageCount,
            int oddMultiplicityCount) {
    }

    public enum GreedyStrategy {
        BATCH_FIRST,
        REUSE_FIRST
    }

    private record ConfigurationBlock(Map<Integer, List<String>> configuration, int count) {
    }
}
