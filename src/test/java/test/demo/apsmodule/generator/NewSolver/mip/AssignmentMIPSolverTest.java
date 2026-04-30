package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssignmentMIPSolverTest {

    @Test
    void buildBlocksFromWidthAssignmentsPreservesPerWidthMessageCounts() {
        Map<Integer, List<Map.Entry<String, Integer>>> widthAssignments = new LinkedHashMap<>();
        widthAssignments.put(1000, List.of(
                Map.entry("A", 2),
                Map.entry("B", 1)));
        widthAssignments.put(1200, List.of(
                Map.entry("X", 1),
                Map.entry("Y", 2)));

        List<AssignmentMIPSolver.AssignmentBlock> blocks =
                AssignmentMIPSolver.buildBlocksFromWidthAssignments(widthAssignments, 3);

        assertEquals(2, blocks.size());
        assertEquals(Map.of(1000, "A", 1200, "Y"), blocks.get(0).getConfig());
        assertEquals(2, blocks.get(0).getCount());
        assertEquals(Map.of(1000, "B", 1200, "X"), blocks.get(1).getConfig());
        assertEquals(1, blocks.get(1).getCount());

        Map<String, Integer> materializedCounts = new HashMap<>();
        for (AssignmentMIPSolver.AssignmentBlock block : blocks) {
            for (Map.Entry<Integer, String> configEntry : block.getConfig().entrySet()) {
                String key = configEntry.getKey() + "|" + configEntry.getValue();
                materializedCounts.merge(key, block.getCount(), Integer::sum);
            }
        }

        assertEquals(2, materializedCounts.get("1000|A"));
        assertEquals(1, materializedCounts.get("1000|B"));
        assertEquals(1, materializedCounts.get("1200|X"));
        assertEquals(2, materializedCounts.get("1200|Y"));
    }

    @Test
    void configurationEstimatorCountsWidthBucketedMessageMultisets() {
        PatternCandidate pattern = new PatternCandidate(
                new LinkedHashMap<>(Map.of(1000, 2, 1200, 1)),
                3200);
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        solution.put(pattern, 3);

        List<SolverOrderItem> items = List.of(
                item(1000, "A", 3),
                item(1000, "B", 1),
                item(1200, "X", 1),
                item(1200, "Y", 2));

        AssignmentConfigurationEstimator.Estimate estimate =
                AssignmentConfigurationEstimator.estimate(solution, items);

        assertEquals(1, estimate.patternCount());
        assertEquals(4, estimate.totalConfigurations());
        assertEquals(4, estimate.maxConfigurationsForPattern());
        assertEquals(2, estimate.maxWidthOptions());
        assertEquals("small", estimate.scaleBand());

        AssignmentConfigurationEstimator.PatternEstimate patternEstimate =
                estimate.patternEstimates().get(0);
        assertEquals(Map.of(1000, 2, 1200, 2), patternEstimate.widthOptionCounts());
        assertEquals(4, patternEstimate.configurationCount());
    }

    @Test
    void configurationEstimatorMarksPatternInfeasibleWhenWidthHasNoDemand() {
        PatternCandidate pattern = new PatternCandidate(
                new LinkedHashMap<>(Map.of(1000, 1, 1600, 1)),
                3200);
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        solution.put(pattern, 1);

        List<SolverOrderItem> items = List.of(item(1000, "A", 1));

        AssignmentConfigurationEstimator.Estimate estimate =
                AssignmentConfigurationEstimator.estimate(solution, items);

        assertEquals(0, estimate.totalConfigurations());
        assertEquals(0, estimate.maxConfigurationsForPattern());
        assertEquals(false, estimate.patternEstimates().get(0).feasible());
    }

    private static SolverOrderItem item(int width, String messageText, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setWidth(width);
        item.setMessageText(messageText);
        item.setDemand(demand);
        return item;
    }
}
