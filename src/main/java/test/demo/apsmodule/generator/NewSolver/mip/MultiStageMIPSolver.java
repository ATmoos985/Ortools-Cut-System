package test.demo.apsmodule.generator.NewSolver.mip;

import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.model.SolverResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A-layer final pattern selection for the NewSolver pipeline.
 *
 * <p>Production uses {@link #solvePrimaryOnly}, which delegates to
 * {@link LegacyOrderPatternSelectionSolver}. The former diverse-search /
 * alignment-scoring path (generateDiverseSolutions + PatternAlignmentScorer +
 * the duplicate Stage1-4 MIPs) never beat the legacy primary in any measured run
 * and was never wired into {@code CuttingSolver}; it has been removed.
 */
public class MultiStageMIPSolver {

    private final SolverParameters params;

    public MultiStageMIPSolver(SolverParameters params) {
        this.params = params;
    }

    public SolveCandidate solvePrimaryOnly(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        return solvePrimaryOnly(patterns, demands, allowOverSet, params.getALayerScipSeed());
    }

    /**
     * Legacy primary with an explicit A-layer SCIP seed. The seed steers the
     * selection MIP onto a different (but reproducible) 花型集 among tie-degenerate
     * optima, which is the cheap multi-start dimension that finds lower-group sets.
     */
    public SolveCandidate solvePrimaryOnly(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int seed) {
        SolverParameters seededParams = params.copy();
        seededParams.setALayerScipSeed(seed);
        LegacyOrderPatternSelectionSolver legacySolver = new LegacyOrderPatternSelectionSolver(seededParams);
        List<LegacyOrderPatternSelectionSolver.Result> results = legacySolver.solveCandidates(
                patterns, demands, allowOverSet);
        if (results.isEmpty()) {
            return null;
        }
        Map<PatternCandidate, Integer> sol = results.get(0).solution();
        int rolls = sol.values().stream().mapToInt(Integer::intValue).sum();
        int waste = calculateTotalWaste(sol);
        int over = calculateTotalOver(sol, demands);
        return new SolveCandidate(results.get(0).name(), new SolverResult(sol, rolls, waste, over, 0L));
    }

    private int calculateTotalWaste(Map<PatternCandidate, Integer> solution) {
        return solution.entrySet().stream()
                .mapToInt(entry -> entry.getKey().getRealWaste(params.getTotalWidth()) * entry.getValue())
                .sum();
    }

    private int calculateTotalOver(Map<PatternCandidate, Integer> solution, Map<Integer, Integer> demands) {
        Map<Integer, Integer> production = calculateProduction(solution);
        int totalOver = 0;
        for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
            int produced = production.getOrDefault(demandEntry.getKey(), 0);
            totalOver += Math.max(0, produced - demandEntry.getValue());
        }
        return totalOver;
    }

    private Map<Integer, Integer> calculateProduction(Map<PatternCandidate, Integer> solution) {
        Map<Integer, Integer> produced = new HashMap<>();
        for (Map.Entry<PatternCandidate, Integer> entry : solution.entrySet()) {
            for (Map.Entry<Integer, Integer> cut : entry.getKey().getPattern().entrySet()) {
                produced.merge(cut.getKey(), cut.getValue() * entry.getValue(), Integer::sum);
            }
        }
        return produced;
    }

    public record SolveCandidate(
            String name,
            SolverResult result) {
    }
}
