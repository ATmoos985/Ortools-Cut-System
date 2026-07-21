package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Research-only support minimization with a feasible one-odd warm start. */
final class SingleOddPatternSupportCompressor {

    private SingleOddPatternSupportCompressor() {
    }

    static Result solve(
            List<PatternCandidate> pool,
            Map<Integer, Integer> demands,
            int totalWidth,
            int exactCars,
            int exactWaste,
            int minimumSelectedUsage,
            long timeLimitMs,
            long nodeLimit,
            Map<PatternCandidate, Integer> warmStart) {
        long startedAt = System.currentTimeMillis();
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            return new Result(MPSolver.ResultStatus.NOT_SOLVED, Map.of(),
                    0L, -1L, Double.NaN);
        }

        int count = pool.size();
        int effectiveMinimumUsage = Math.max(1, minimumSelectedUsage);
        MPVariable[] usage = new MPVariable[count];
        MPVariable[] selected = new MPVariable[count];
        MPVariable[] half = new MPVariable[count];
        MPVariable[] odd = new MPVariable[count];
        for (int index = 0; index < count; index++) {
            usage[index] = solver.makeIntVar(0, exactCars, "x_" + index);
            selected[index] = solver.makeBoolVar("y_" + index);
            half[index] = solver.makeIntVar(0, exactCars, "h_" + index);
            boolean parityCompatible = PatternParityFeasibilityOracle
                    .hasDemandParityFingerprint(pool.get(index), demands);
            odd[index] = solver.makeIntVar(0, parityCompatible ? 1 : 0, "o_" + index);

            MPConstraint upperLink = solver.makeConstraint(
                    -MPSolver.infinity(), 0, "upper_link_" + index);
            upperLink.setCoefficient(usage[index], 1);
            upperLink.setCoefficient(selected[index], -exactCars);
            MPConstraint lowerLink = solver.makeConstraint(
                    -MPSolver.infinity(), 0, "lower_link_" + index);
            lowerLink.setCoefficient(selected[index], effectiveMinimumUsage);
            lowerLink.setCoefficient(usage[index], -1);
            MPConstraint parity = solver.makeConstraint(0, 0, "parity_" + index);
            parity.setCoefficient(usage[index], 1);
            parity.setCoefficient(half[index], -2);
            parity.setCoefficient(odd[index], -1);
        }

        for (Map.Entry<Integer, Integer> demand : demands.entrySet()) {
            MPConstraint row = solver.makeConstraint(
                    demand.getValue(), demand.getValue(), "demand_" + demand.getKey());
            for (int index = 0; index < count; index++) {
                int coefficient = pool.get(index).getPattern()
                        .getOrDefault(demand.getKey(), 0);
                if (coefficient > 0) {
                    row.setCoefficient(usage[index], coefficient);
                }
            }
        }

        MPConstraint cars = solver.makeConstraint(exactCars, exactCars, "exact_cars");
        MPConstraint waste = solver.makeConstraint(exactWaste, exactWaste, "exact_waste");
        MPConstraint oddCount = solver.makeConstraint(1, 1, "one_odd_usage");
        MPObjective objective = solver.objective();
        for (int index = 0; index < count; index++) {
            cars.setCoefficient(usage[index], 1);
            waste.setCoefficient(usage[index], pool.get(index).getRealWaste(totalWidth));
            oddCount.setCoefficient(odd[index], 1);
            objective.setCoefficient(selected[index], 1);
        }
        objective.setMinimization();

        applyHint(solver, pool, warmStart, usage, selected, half, odd);
        solver.setTimeLimit(timeLimitMs);
        if (nodeLimit > 0) {
            solver.setSolverSpecificParametersAsString(
                    "parallel/maxnthreads = 1\n"
                            + "randomization/randomseedshift = 0\n"
                            + "limits/nodes = " + nodeLimit + "\n");
        }

        MPSolver.ResultStatus status = solver.solve();
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        if (status == MPSolver.ResultStatus.OPTIMAL
                || status == MPSolver.ResultStatus.FEASIBLE) {
            for (int index = 0; index < count; index++) {
                int value = (int) Math.round(usage[index].solutionValue());
                if (value > 0) {
                    solution.put(pool.get(index), value);
                }
            }
        }
        long nodes;
        try {
            nodes = solver.nodes();
        } catch (RuntimeException ignored) {
            nodes = -1L;
        }
        double bestBound;
        try {
            bestBound = objective.bestBound();
        } catch (RuntimeException ignored) {
            bestBound = Double.NaN;
        }
        return new Result(status, Map.copyOf(solution),
                System.currentTimeMillis() - startedAt, nodes, bestBound);
    }

    private static void applyHint(
            MPSolver solver,
            List<PatternCandidate> pool,
            Map<PatternCandidate, Integer> warmStart,
            MPVariable[] usage,
            MPVariable[] selected,
            MPVariable[] half,
            MPVariable[] odd) {
        Map<String, Integer> hintBySignature = new LinkedHashMap<>();
        warmStart.forEach((pattern, value) ->
                hintBySignature.put(pattern.signature(), value));
        List<MPVariable> variables = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < pool.size(); index++) {
            int value = hintBySignature.getOrDefault(pool.get(index).signature(), 0);
            variables.add(usage[index]);
            values.add((double) value);
            variables.add(selected[index]);
            values.add(value > 0 ? 1.0 : 0.0);
            variables.add(half[index]);
            values.add((double) (value / 2));
            variables.add(odd[index]);
            values.add((double) (value % 2));
        }
        solver.setHint(
                variables.toArray(MPVariable[]::new),
                values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    record Result(
            MPSolver.ResultStatus status,
            Map<PatternCandidate, Integer> solution,
            long elapsedMs,
            long nodes,
            double bestBound) {

        boolean feasible() {
            return status == MPSolver.ResultStatus.OPTIMAL
                    || status == MPSolver.ResultStatus.FEASIBLE;
        }
    }
}
