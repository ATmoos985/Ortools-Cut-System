package test.demo.apsmodule.util;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderAssignmentOptimizerTest {

    @Test
    void buildTupleBlockAssignmentsMergesRepeatedConfigurations() {
        Map<Integer, Integer> pattern = new LinkedHashMap<>();
        pattern.put(1000, 1);

        List<SolverOrderItem> groupItems = List.of(
                item(1000, "A", 2),
                item(1000, "B", 1));

        Map<String, Integer> remainingDemands = new LinkedHashMap<>();
        remainingDemands.put("1000|A", 2);
        remainingDemands.put("1000|B", 1);

        List<StationAssignment> assignments = OrderAssignmentOptimizer.buildTupleBlockAssignments(
                pattern, 4, groupItems, remainingDemands);

        assertEquals(List.of("A", "A", "A", "B"), assignments.stream()
                .map(StationAssignment::getMessageText)
                .toList());
        assertEquals(2, contiguousGroupCount(assignments));
    }

    @Test
    void buildTupleBlockAssignmentsUsesMixedMessagesForRepeatedWidthSlots() {
        Map<Integer, Integer> pattern = new LinkedHashMap<>();
        pattern.put(1000, 3);

        List<SolverOrderItem> groupItems = List.of(
                item(1000, "A", 60),
                item(1000, "B", 30));

        Map<String, Integer> remainingDemands = new LinkedHashMap<>();
        remainingDemands.put("1000|A", 60);
        remainingDemands.put("1000|B", 30);

        List<StationAssignment> assignments = OrderAssignmentOptimizer.buildTupleBlockAssignments(
                pattern, 30, groupItems, remainingDemands);

        assertEquals(90, assignments.size());
        assertEquals(1, contiguousRollSignatureCount(assignments, 3));

        Map<String, Long> demandByMessage = assignments.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        StationAssignment::getMessageText,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        assertEquals(60L, demandByMessage.get("A"));
        assertEquals(30L, demandByMessage.get("B"));
    }

    @Test
    void chooseBatchSizeAvoidsSingletonTailWhenParityMatters() {
        Map<Integer, List<String>> oddMultiplicityConfig = new LinkedHashMap<>();
        oddMultiplicityConfig.put(1000, List.of("A"));

        Map<Integer, List<String>> evenMultiplicityConfig = new LinkedHashMap<>();
        evenMultiplicityConfig.put(1000, List.of("A", "A"));

        assertEquals(4, OrderAssignmentOptimizer.chooseBatchSize(oddMultiplicityConfig, 5, 7));
        assertEquals(5, OrderAssignmentOptimizer.chooseBatchSize(evenMultiplicityConfig, 5, 7));
    }

    @Test
    void buildTupleBlockAssignmentsReuseFirstPreservesDemandTotals() {
        Map<Integer, Integer> pattern = new LinkedHashMap<>();
        pattern.put(1000, 2);

        List<SolverOrderItem> groupItems = List.of(
                item(1000, "A", 8),
                item(1000, "B", 6),
                item(1000, "C", 4));

        Map<String, Integer> remainingDemands = new LinkedHashMap<>();
        remainingDemands.put("1000|A", 8);
        remainingDemands.put("1000|B", 6);
        remainingDemands.put("1000|C", 4);

        List<StationAssignment> assignments = OrderAssignmentOptimizer.buildTupleBlockAssignments(
                pattern,
                9,
                groupItems,
                remainingDemands,
                OrderAssignmentOptimizer.GreedyStrategy.REUSE_FIRST);

        assertEquals(18, assignments.size());
        Map<String, Long> demandByMessage = assignments.stream()
                .collect(Collectors.groupingBy(
                        StationAssignment::getMessageText,
                        LinkedHashMap::new,
                        Collectors.counting()));
        assertEquals(8L, demandByMessage.get("A"));
        assertEquals(6L, demandByMessage.get("B"));
        assertEquals(4L, demandByMessage.get("C"));
    }

    private static int contiguousGroupCount(List<StationAssignment> assignments) {
        if (assignments.isEmpty()) {
            return 0;
        }

        int groups = 1;
        String previous = assignments.get(0).getMessageText();
        for (int i = 1; i < assignments.size(); i++) {
            String current = assignments.get(i).getMessageText();
            if (!previous.equals(current)) {
                groups++;
                previous = current;
            }
        }
        return groups;
    }

    private static int contiguousRollSignatureCount(List<StationAssignment> assignments, int slotsPerRoll) {
        if (assignments.isEmpty()) {
            return 0;
        }

        int groups = 0;
        String previousSignature = null;
        for (int index = 0; index < assignments.size(); index += slotsPerRoll) {
            List<String> signature = assignments.subList(index, index + slotsPerRoll).stream()
                    .map(StationAssignment::getMessageText)
                    .sorted()
                    .toList();
            String currentSignature = String.join(",", signature);
            if (!currentSignature.equals(previousSignature)) {
                groups++;
                previousSignature = currentSignature;
            }
        }
        return groups;
    }

    private static SolverOrderItem item(int width, String messageText, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setWidth(width);
        item.setMessageText(messageText);
        item.setDemand(demand);
        return item;
    }
}
