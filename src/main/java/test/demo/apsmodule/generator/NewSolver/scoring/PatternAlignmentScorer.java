package test.demo.apsmodule.generator.NewSolver.scoring;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes a "message alignment score" for each pattern candidate.
 *
 * Score ∈ [0,1]: higher = the widths inside this pattern tend to share the same
 * message-text demand cluster, making it easier for AssignmentMIPSolver to
 * consolidate message assignments into fewer sequence groups.
 *
 * Formula for a pattern with widths {w1, w2, ...}:
 *
 *               Σ_{pairs (wi,wj)}  Σ_m  min(demand[wi,m], demand[wj,m])
 *   alignment = ───────────────────────────────────────────────────────
 *               Σ_{pairs (wi,wj)}  max(totalDemand[wi], totalDemand[wj])
 *
 * Intuition: if 1260mm demand is all message-A and 1280mm demand is all message-B,
 * the pair (1260,1280) has overlap=0 → alignment=0 → solver avoids putting them
 * in the same pattern.  Conversely (1260mm, 1130mm) both dominated by message-A
 * would score near 1.
 */
public class PatternAlignmentScorer {

    private PatternAlignmentScorer() {}

    /**
     * Compute alignment scores for all pattern candidates.
     *
     * @param patterns   full pattern pool
     * @param groupItems order items for this solve group (contain width + messageText + demand)
     * @return map of pattern → alignment score in [0, 1]
     */
    public static Map<PatternCandidate, Double> score(
            List<PatternCandidate> patterns,
            List<SolverOrderItem> groupItems) {

        // Build demand[width][message] → count
        Map<Integer, Map<String, Integer>> demandByWidthMsg = buildDemandMap(groupItems);

        // Build total demand per width
        Map<Integer, Integer> totalByWidth = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Integer>> e : demandByWidthMsg.entrySet()) {
            totalByWidth.put(e.getKey(),
                    e.getValue().values().stream().mapToInt(Integer::intValue).sum());
        }

        Map<PatternCandidate, Double> scores = new LinkedHashMap<>();
        for (PatternCandidate pattern : patterns) {
            scores.put(pattern, computeAlignment(pattern, demandByWidthMsg, totalByWidth));
        }
        return scores;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static Map<Integer, Map<String, Integer>> buildDemandMap(List<SolverOrderItem> items) {
        Map<Integer, Map<String, Integer>> map = new LinkedHashMap<>();
        for (SolverOrderItem item : items) {
            map.computeIfAbsent(item.getWidth(), k -> new LinkedHashMap<>())
               .merge(item.getMessageText(), item.getDemand(), Integer::sum);
        }
        return map;
    }

    private static double computeAlignment(
            PatternCandidate pattern,
            Map<Integer, Map<String, Integer>> demandByWidthMsg,
            Map<Integer, Integer> totalByWidth) {

        List<Integer> widths = new ArrayList<>(pattern.getPattern().keySet());

        // Single-width patterns are trivially "aligned" (nothing to conflict)
        if (widths.size() <= 1) return 1.0;

        double numerator = 0.0;
        double denominator = 0.0;

        for (int i = 0; i < widths.size(); i++) {
            for (int j = i + 1; j < widths.size(); j++) {
                int wi = widths.get(i);
                int wj = widths.get(j);

                Map<String, Integer> dmI = demandByWidthMsg.getOrDefault(wi, Collections.emptyMap());
                Map<String, Integer> dmJ = demandByWidthMsg.getOrDefault(wj, Collections.emptyMap());
                int Di = totalByWidth.getOrDefault(wi, 1);
                int Dj = totalByWidth.getOrDefault(wj, 1);

                // Message overlap between the two widths
                Set<String> allMessages = new HashSet<>(dmI.keySet());
                allMessages.addAll(dmJ.keySet());
                double overlap = 0.0;
                for (String msg : allMessages) {
                    overlap += Math.min(dmI.getOrDefault(msg, 0), dmJ.getOrDefault(msg, 0));
                }

                numerator += overlap;
                denominator += Math.max(Di, Dj);
            }
        }

        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }
}
