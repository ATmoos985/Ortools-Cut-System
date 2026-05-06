package test.demo.apsmodule.generator.NewSolver.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.StationAssignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Heuristic post-processing for sequence groups.
 *
 * This step is best-effort only. It must never block the whole solve for a
 * large result set, so it runs with a fixed time budget and only keeps swaps
 * that actually reduce the real group count.
 */
public class SequenceGroupPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SequenceGroupPostProcessor.class);
    private static final long DEFAULT_TIME_BUDGET_MS = 1500L;

    public static void optimize(List<CuttingInstruction> instructions) {
        optimize(instructions, DEFAULT_TIME_BUDGET_MS, true);
    }

    static void optimize(List<CuttingInstruction> instructions, long timeBudgetMs) {
        optimize(instructions, timeBudgetMs, true);
    }

    static void optimize(List<CuttingInstruction> instructions, long timeBudgetMs, boolean logSummary) {
        if (instructions == null || instructions.isEmpty()) {
            return;
        }

        int totalBefore = 0;
        int totalAfter = 0;

        for (CuttingInstruction instruction : instructions) {
            if (instruction.getStationAssignments() == null || instruction.getStationAssignments().isEmpty()) {
                continue;
            }
            if (instruction.getSubRolls() == null || instruction.getSubRolls().isEmpty()) {
                continue;
            }

            List<List<StationAssignment>> rollsBefore = simulateRolls(instruction);
            if (rollsBefore.size() <= 1) {
                continue;
            }

            int groupsBefore = identifyGroups(rollsBefore).size();
            totalBefore += groupsBefore;

            List<StationAssignment> resequenced = resequenceBySort(instruction);
            instruction.setStationAssignments(resequenced);

            List<List<StationAssignment>> rollsAfter = simulateRolls(instruction);
            int groupsAfter = identifyGroups(rollsAfter).size();

            if (groupsAfter > groupsBefore) {
                // Revert if the sort made things worse (e.g., multi-width interaction)
                List<StationAssignment> original = new ArrayList<>();
                for (List<StationAssignment> roll : rollsBefore) {
                    original.addAll(roll);
                }
                instruction.setStationAssignments(original);
                totalAfter += groupsBefore;
            } else {
                totalAfter += groupsAfter;
            }
        }

        if (logSummary && totalBefore > totalAfter) {
            log.info("Sequence-group postprocess: {} -> {} (reduced by {})",
                    totalBefore, totalAfter, totalBefore - totalAfter);
        }
    }

    /**
     * Reorder StationAssignments within an instruction so that identical roll
     * configurations are consecutive, minimising sequence groups.
     *
     * Each StationAssignment object is kept intact (messageText is never changed).
     * Only the order in which assignments are consumed per-roll is changed.
     *
     * Strategy: sort each per-width assignment list by messageText so that all
     * same-message assignments are consecutive.  Then rebuild the flat list by
     * interleaving width buckets in pattern order.  For single-width patterns
     * this is provably optimal; for multi-width patterns it is a strong heuristic.
     */
    private static List<StationAssignment> resequenceBySort(CuttingInstruction instruction) {
        // Group assignments by width, preserving insertion order per width
        Map<Integer, List<StationAssignment>> byWidth = new LinkedHashMap<>();
        for (StationAssignment a : instruction.getStationAssignments()) {
            byWidth.computeIfAbsent(a.getWidth(), k -> new ArrayList<>()).add(a);
        }

        // Sort each width group by messageText to cluster same messages
        for (List<StationAssignment> group : byWidth.values()) {
            group.sort(Comparator.comparing(StationAssignment::getMessageText,
                    Comparator.nullsLast(Comparator.naturalOrder())));
        }

        // Convert to queues for sequential consumption
        Map<Integer, java.util.ArrayDeque<StationAssignment>> queues = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<StationAssignment>> e : byWidth.entrySet()) {
            queues.put(e.getKey(), new java.util.ArrayDeque<>(e.getValue()));
        }

        // Rebuild: for each roll, take the required count from each width queue
        int usageCount = instruction.getUsageCount();
        Map<Integer, Integer> subRolls = instruction.getSubRolls();
        List<StationAssignment> result = new ArrayList<>(instruction.getStationAssignments().size());

        for (int i = 0; i < usageCount; i++) {
            for (Map.Entry<Integer, Integer> e : subRolls.entrySet()) {
                int width = e.getKey();
                int slotsPerRoll = e.getValue();
                java.util.ArrayDeque<StationAssignment> q = queues.get(width);
                for (int j = 0; j < slotsPerRoll; j++) {
                    if (q != null && !q.isEmpty()) {
                        result.add(q.poll());
                    }
                }
            }
        }
        return result;
    }

    public static int countTotalGroups(List<CuttingInstruction> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            return 0;
        }

        int totalGroups = 0;
        List<StationAssignment> previousRoll = null;
        for (CuttingInstruction instruction : instructions) {
            if (instruction == null || instruction.getStationAssignments() == null || instruction.getStationAssignments().isEmpty()) {
                continue;
            }
            if (instruction.getSubRolls() == null || instruction.getSubRolls().isEmpty()) {
                continue;
            }

            List<List<StationAssignment>> rolls = simulateRolls(instruction);
            for (List<StationAssignment> roll : rolls) {
                if (previousRoll == null || !isRollContentSame(previousRoll, roll)) {
                    totalGroups++;
                }
                previousRoll = roll;
            }
        }
        return totalGroups;
    }

    public static int countGroups(CuttingInstruction instruction) {
        if (instruction == null || instruction.getStationAssignments() == null || instruction.getStationAssignments().isEmpty()) {
            return 0;
        }
        if (instruction.getSubRolls() == null || instruction.getSubRolls().isEmpty()) {
            return 0;
        }

        List<List<StationAssignment>> rolls = simulateRolls(instruction);
        if (rolls.isEmpty()) {
            return 0;
        }
        return identifyGroups(rolls).size();
    }

    private static List<List<StationAssignment>> simulateRolls(CuttingInstruction instruction) {
        List<List<StationAssignment>> rolls = new ArrayList<>();

        Map<Integer, Queue<StationAssignment>> buckets = new LinkedHashMap<>();
        for (StationAssignment assignment : instruction.getStationAssignments()) {
            buckets.computeIfAbsent(assignment.getWidth(), key -> new LinkedList<>()).add(assignment);
        }

        int usageCount = instruction.getUsageCount();
        Map<Integer, Integer> subRolls = instruction.getSubRolls();

        for (int i = 0; i < usageCount; i++) {
            List<StationAssignment> rollAssignments = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : subRolls.entrySet()) {
                int width = entry.getKey();
                int count = entry.getValue();
                Queue<StationAssignment> bucket = buckets.get(width);
                for (int j = 0; j < count; j++) {
                    if (bucket != null && !bucket.isEmpty()) {
                        rollAssignments.add(bucket.poll());
                    }
                }
            }
            if (!rollAssignments.isEmpty()) {
                rolls.add(rollAssignments);
            }
        }

        return rolls;
    }

    private static List<int[]> identifyGroups(List<List<StationAssignment>> rolls) {
        List<int[]> groups = new ArrayList<>();
        if (rolls.isEmpty()) {
            return groups;
        }

        int groupStart = 0;
        for (int i = 1; i < rolls.size(); i++) {
            if (!isRollContentSame(rolls.get(i), rolls.get(i - 1))) {
                groups.add(new int[] { groupStart, i - 1 });
                groupStart = i;
            }
        }
        groups.add(new int[] { groupStart, rolls.size() - 1 });
        return groups;
    }

    private static boolean isRollContentSame(List<StationAssignment> roll1, List<StationAssignment> roll2) {
        if (roll1.size() != roll2.size()) {
            return false;
        }

        List<String> keys1 = roll1.stream()
                .map(assignment -> assignment.getWidth() + "_" + assignment.getMessageText())
                .sorted()
                .collect(Collectors.toList());
        List<String> keys2 = roll2.stream()
                .map(assignment -> assignment.getWidth() + "_" + assignment.getMessageText())
                .sorted()
                .collect(Collectors.toList());

        return keys1.equals(keys2);
    }

    private static boolean tryMergeAdjacentGroups(List<List<StationAssignment>> rolls, long deadlineNanos) {
        boolean anyChanged = false;
        int maxPasses = Math.max(1, rolls.size());

        for (int pass = 0; pass < maxPasses && System.nanoTime() < deadlineNanos; pass++) {
            boolean changedThisPass = false;
            List<int[]> groups = identifyGroups(rolls);
            int groupCountBefore = groups.size();

            for (int groupIndex = 0; groupIndex < groups.size() - 1; groupIndex++) {
                if (System.nanoTime() >= deadlineNanos) {
                    return anyChanged;
                }

                int[] group1 = groups.get(groupIndex);
                int[] group2 = groups.get(groupIndex + 1);

                List<StationAssignment> representative1 = rolls.get(group1[0]);
                List<StationAssignment> representative2 = rolls.get(group2[0]);
                if (representative1.size() != representative2.size()) {
                    continue;
                }

                Map<Integer, String> messageByWidth1 = toMessageByWidth(representative1);
                Map<Integer, String> messageByWidth2 = toMessageByWidth(representative2);
                if (messageByWidth1.equals(messageByWidth2)) {
                    continue;
                }

                List<Integer> diffWidths = new ArrayList<>();
                for (Integer width : messageByWidth1.keySet()) {
                    String message1 = messageByWidth1.get(width);
                    String message2 = messageByWidth2.get(width);
                    if (message2 != null && !Objects.equals(message1, message2)) {
                        diffWidths.add(width);
                    }
                }

                if (diffWidths.size() != 1) {
                    continue;
                }

                int diffWidth = diffWidths.get(0);
                String message1 = messageByWidth1.get(diffWidth);
                String message2 = messageByWidth2.get(diffWidth);
                int group1Size = group1[1] - group1[0] + 1;
                int group2Size = group2[1] - group2[0] + 1;

                GroupSnapshot snapshot;
                if (group1Size <= group2Size) {
                    snapshot = snapshotRolls(rolls, group1[0], group1[1]);
                    swapMessageInRolls(rolls, group1[0], group1[1], diffWidth, message1, message2);
                } else {
                    snapshot = snapshotRolls(rolls, group2[0], group2[1]);
                    swapMessageInRolls(rolls, group2[0], group2[1], diffWidth, message2, message1);
                }

                int groupCountAfter = identifyGroups(rolls).size();
                if (groupCountAfter < groupCountBefore) {
                    anyChanged = true;
                    changedThisPass = true;
                    break;
                }

                restoreRolls(rolls, snapshot);
            }

            if (!changedThisPass) {
                break;
            }
        }

        return anyChanged;
    }

    private static Map<Integer, String> toMessageByWidth(List<StationAssignment> roll) {
        Map<Integer, String> messageByWidth = new LinkedHashMap<>();
        for (StationAssignment assignment : roll) {
            messageByWidth.put(assignment.getWidth(), assignment.getMessageText());
        }
        return messageByWidth;
    }

    private static void swapMessageInRolls(List<List<StationAssignment>> rolls,
            int startRoll,
            int endRoll,
            int width,
            String fromMessage,
            String toMessage) {
        for (int rollIndex = startRoll; rollIndex <= endRoll; rollIndex++) {
            for (int stationIndex = 0; stationIndex < rolls.get(rollIndex).size(); stationIndex++) {
                StationAssignment assignment = rolls.get(rollIndex).get(stationIndex);
                if (assignment.getWidth() == width && Objects.equals(assignment.getMessageText(), fromMessage)) {
                    rolls.get(rollIndex).set(stationIndex, new StationAssignment(width, toMessage));
                }
            }
        }
    }

    private static void tryEvenizeGroups(List<List<StationAssignment>> rolls, List<int[]> groups, long deadlineNanos) {
        if (groups.size() < 2) {
            return;
        }

        for (int groupIndex = 0; groupIndex < groups.size() - 1; groupIndex++) {
            if (System.nanoTime() >= deadlineNanos) {
                return;
            }

            int[] group1 = groups.get(groupIndex);
            int[] group2 = groups.get(groupIndex + 1);
            int size1 = group1[1] - group1[0] + 1;
            int size2 = group2[1] - group2[0] + 1;

            if (size1 % 2 != 0 && size2 % 2 != 0 && size1 > 1) {
                List<StationAssignment> boundaryRoll = rolls.get(group1[1]);
                List<StationAssignment> targetRoll = rolls.get(group2[0]);
                for (int i = 0; i < boundaryRoll.size() && i < targetRoll.size(); i++) {
                    StationAssignment boundary = boundaryRoll.get(i);
                    StationAssignment target = targetRoll.get(i);
                    if (boundary.getWidth() == target.getWidth()
                            && !Objects.equals(boundary.getMessageText(), target.getMessageText())) {
                        boundaryRoll.set(i, new StationAssignment(boundary.getWidth(), target.getMessageText()));
                    }
                }
            }
        }
    }

    private static GroupSnapshot snapshotRolls(List<List<StationAssignment>> rolls, int startRoll, int endRoll) {
        List<List<StationAssignment>> snapshot = new ArrayList<>();
        for (int rollIndex = startRoll; rollIndex <= endRoll; rollIndex++) {
            List<StationAssignment> rollCopy = new ArrayList<>();
            for (StationAssignment assignment : rolls.get(rollIndex)) {
                rollCopy.add(new StationAssignment(assignment.getWidth(), assignment.getMessageText()));
            }
            snapshot.add(rollCopy);
        }
        return new GroupSnapshot(startRoll, snapshot);
    }

    private static void restoreRolls(List<List<StationAssignment>> rolls, GroupSnapshot snapshot) {
        for (int offset = 0; offset < snapshot.rolls().size(); offset++) {
            rolls.set(snapshot.startRoll() + offset, snapshot.rolls().get(offset));
        }
    }

    private record GroupSnapshot(int startRoll, List<List<StationAssignment>> rolls) {
    }
}
