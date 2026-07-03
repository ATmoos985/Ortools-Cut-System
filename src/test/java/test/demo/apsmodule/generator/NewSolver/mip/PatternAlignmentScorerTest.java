package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternAlignmentScorerTest {

    @Test
    void sharedMessagesLowerOverlapCost() {
        PatternAlignmentScorer scorer = new PatternAlignmentScorer(PatternAlignmentContext.from(List.of(
                item(1260, "A", 10),
                item(1280, "A", 10),
                item(1300, "C", 10))));

        double shared = scorer.cost(pattern(Map.of(1260, 1, 1280, 1)));
        double disjoint = scorer.cost(pattern(Map.of(1260, 1, 1300, 1)));

        assertTrue(shared < disjoint);
    }

    @Test
    void messageRemainderIncreasesCost() {
        PatternAlignmentScorer scorer = new PatternAlignmentScorer(PatternAlignmentContext.from(List.of(
                item(1260, "A", 5))));

        double divisible = scorer.cost(pattern(Map.of(1260, 1)));
        double remainder = scorer.cost(pattern(Map.of(1260, 2)));

        assertTrue(remainder > divisible);
    }

    private static SolverOrderItem item(int width, String message, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setWidth(width);
        item.setMessageText(message);
        item.setDemand(demand);
        return item;
    }

    private static PatternCandidate pattern(Map<Integer, Integer> widths) {
        Map<Integer, Integer> ordered = new LinkedHashMap<>();
        widths.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        int rollWidth = ordered.entrySet().stream()
                .mapToInt(entry -> entry.getKey() * entry.getValue())
                .sum();
        return new PatternCandidate(ordered, rollWidth);
    }
}
