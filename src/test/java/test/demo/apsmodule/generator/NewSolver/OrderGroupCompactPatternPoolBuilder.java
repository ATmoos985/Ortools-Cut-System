package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a small order-level skeleton pool from a complete universe.
 *
 * <p>The complete universe is used only by this cheap width-level pricing
 * pass. Order-level configurations never see the discarded candidates.</p>
 */
final class OrderGroupCompactPatternPoolBuilder {

    private static final int[] PREFERRED_BATCHES = {10, 8, 6, 4, 3, 2};
    private static final int ITERATIONS = 5;

    private OrderGroupCompactPatternPoolBuilder() {
    }

    static List<PatternCandidate> build(
            List<PatternCandidate> productionPool,
            List<PatternCandidate> completeUniverse,
            Map<Integer, Integer> demands,
            SolverParameters params,
            int maxPatterns) {
        if (maxPatterns <= 0) {
            throw new IllegalArgumentException("maxPatterns must be positive");
        }
        Map<String, PatternCandidate> selected = new LinkedHashMap<>();
        productionPool.forEach(pattern -> selected.putIfAbsent(
                pattern.signature(), pattern));
        List<PatternCandidate> universe = completeUniverse.stream()
                .filter(pattern -> maxRepeat(pattern, demands) >= 1)
                .toList();

        for (int iteration = 0;
                iteration < ITERATIONS && selected.size() < maxPatterns;
                iteration++) {
            Map<Integer, Double> duals = widthBatchDuals(
                    new ArrayList<>(selected.values()), demands);
            List<RankedPattern> ranked = universe.stream()
                    .flatMap(pattern -> allRoles(pattern, duals, demands).stream())
                    .sorted(Comparator.comparingDouble(RankedPattern::reducedCost)
                            .thenComparing(entry -> entry.pattern().signature()))
                    .toList();
            Map<String, PatternCandidate> additions = new LinkedHashMap<>();

            ranked.stream().limit(50).forEach(entry -> additions.putIfAbsent(
                    entry.pattern().signature(), entry.pattern()));
            for (int batch : PREFERRED_BATCHES) {
                ranked.stream()
                        .filter(entry -> entry.batch() == batch)
                        .limit(20)
                        .forEach(entry -> additions.putIfAbsent(
                                entry.pattern().signature(), entry.pattern()));
            }
            for (Map.Entry<Integer, Integer> demand : demands.entrySet()) {
                if (demand.getValue() > 10) {
                    continue;
                }
                ranked.stream()
                        .filter(entry -> entry.pattern().getPattern()
                                .containsKey(demand.getKey()))
                        .limit(6)
                        .forEach(entry -> additions.putIfAbsent(
                                entry.pattern().signature(), entry.pattern()));
            }
            ranked.stream()
                    .filter(entry -> rareWidthCount(entry.pattern(), demands) >= 2)
                    .limit(20)
                    .forEach(entry -> additions.putIfAbsent(
                                entry.pattern().signature(), entry.pattern()));

            int before = selected.size();
            additions.values().stream()
                    .filter(pattern -> !selected.containsKey(pattern.signature()))
                    .limit(maxPatterns - selected.size())
                    .forEach(pattern -> selected.put(pattern.signature(), pattern));
            if (selected.size() == before) {
                break;
            }
        }
        return List.copyOf(selected.values());
    }

    private static List<RankedPattern> allRoles(
            PatternCandidate pattern,
            Map<Integer, Double> duals,
            Map<Integer, Integer> demands) {
        List<RankedPattern> result = new ArrayList<>();
        int repeat = maxRepeat(pattern, demands);
        for (int batch : PREFERRED_BATCHES) {
            if (batch > repeat) {
                continue;
            }
            double coverage = pattern.getPattern().entrySet().stream()
                    .mapToDouble(entry -> duals.getOrDefault(entry.getKey(), 0.0)
                            * entry.getValue() * batch)
                    .sum();
            double cost = 1.0 + (batch <= 3 ? 0.10 : 0.0);
            int allowedRare = batch <= 4 ? 3 : 1;
            double reducedCost = cost - coverage
                    + 0.75 * Math.max(0,
                    rareWidthCount(pattern, demands) - allowedRare);
            result.add(new RankedPattern(pattern, reducedCost, batch));
        }
        return result;
    }

    private static Map<Integer, Double> widthBatchDuals(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands) {
        MPSolver solver = MPSolver.createSolver("GLOP");
        if (solver == null) {
            throw new IllegalStateException("GLOP is unavailable");
        }
        Map<Integer, MPConstraint> rows = new HashMap<>();
        demands.forEach((width, demand) -> rows.put(
                width, solver.makeConstraint(demand, demand, "demand_" + width)));
        List<MPVariable> usage = new ArrayList<>();
        for (PatternCandidate pattern : patterns) {
            int repeat = maxRepeat(pattern, demands);
            for (int batch : PREFERRED_BATCHES) {
                if (batch > repeat) {
                    continue;
                }
                MPVariable variable = solver.makeNumVar(0, solver.infinity(),
                        "x_" + usage.size());
                usage.add(variable);
                for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                    rows.get(cut.getKey()).setCoefficient(
                            variable, cut.getValue() * batch);
                }
                solver.objective().setCoefficient(
                        variable, 1.0 + (batch <= 3 ? 0.10 : 0.0));
            }
        }
        MPObjective objective = solver.objective();
        objective.setMinimization();
        MPSolver.ResultStatus status = solver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL
                && status != MPSolver.ResultStatus.FEASIBLE) {
            return fallbackDuals(demands);
        }
        Map<Integer, Double> duals = new HashMap<>();
        rows.forEach((width, row) -> duals.put(width, row.dualValue()));
        return duals;
    }

    private static Map<Integer, Double> fallbackDuals(
            Map<Integer, Integer> demands) {
        Map<Integer, Double> duals = new HashMap<>();
        demands.keySet().forEach(width -> duals.put(width, 0.0));
        return duals;
    }

    private static int maxRepeat(
            PatternCandidate pattern,
            Map<Integer, Integer> demands) {
        return pattern.getPattern().entrySet().stream()
                .mapToInt(entry -> demands.getOrDefault(entry.getKey(), 0)
                        / entry.getValue())
                .min().orElse(0);
    }

    private static int rareWidthCount(
            PatternCandidate pattern,
            Map<Integer, Integer> demands) {
        return (int) pattern.getPattern().keySet().stream()
                .filter(width -> demands.getOrDefault(width, 0) <= 10)
                .count();
    }

    private record RankedPattern(
            PatternCandidate pattern,
            double reducedCost,
            int batch) {
    }
}
