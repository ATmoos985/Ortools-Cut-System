package test.demo.apsmodule.generator.NewSolver.mip;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PatternAlignmentScorer {

    private static final double REMAINDER_WEIGHT = 1.0;
    private static final double OVERLAP_WEIGHT = 1.0;

    private final PatternAlignmentContext context;

    PatternAlignmentScorer(PatternAlignmentContext context) {
        this.context = context == null ? PatternAlignmentContext.empty() : context;
    }

    double cost(PatternCandidate pattern) {
        if (pattern == null || context.isEmpty()) {
            return 0.0;
        }
        return REMAINDER_WEIGHT * remainderCost(pattern)
                + OVERLAP_WEIGHT * overlapCost(pattern);
    }

    private double remainderCost(PatternCandidate pattern) {
        double weightedCost = 0.0;
        double totalWeight = 0.0;
        for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
            int slots = cut.getValue();
            if (slots <= 1) {
                continue;
            }
            for (int demand : context.messagesForWidth(cut.getKey()).values()) {
                if (demand <= 0) {
                    continue;
                }
                int remainder = demand % slots;
                if (remainder != 0) {
                    weightedCost += demand * (Math.min(remainder, slots - remainder) / (double) slots);
                }
                totalWeight += demand;
            }
        }
        return totalWeight == 0.0 ? 0.0 : weightedCost / totalWeight;
    }

    private double overlapCost(PatternCandidate pattern) {
        List<Map.Entry<Integer, Integer>> cuts = new ArrayList<>(pattern.getPattern().entrySet());
        if (cuts.size() <= 1) {
            return 0.0;
        }

        double weightedCost = 0.0;
        double totalWeight = 0.0;
        for (int i = 0; i < cuts.size(); i++) {
            int widthA = cuts.get(i).getKey();
            int demandA = context.widthDemand(widthA);
            if (demandA <= 0) {
                continue;
            }
            for (int j = i + 1; j < cuts.size(); j++) {
                int widthB = cuts.get(j).getKey();
                int demandB = context.widthDemand(widthB);
                if (demandB <= 0) {
                    continue;
                }
                double pairWeight = Math.min(demandA, demandB)
                        * (double) cuts.get(i).getValue()
                        * cuts.get(j).getValue();
                double overlap = context.overlapRatio(widthA, widthB);
                weightedCost += pairWeight * (1.0 - overlap);
                totalWeight += pairWeight;
            }
        }
        return totalWeight == 0.0 ? 0.0 : weightedCost / totalWeight;
    }
}
