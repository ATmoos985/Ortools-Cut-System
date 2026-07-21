package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Research-only feasibility oracle that separates pool quality from Stage4 search. */
final class PatternParityFeasibilityOracle {

    private PatternParityFeasibilityOracle() {
    }

    static Result solve(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            int totalWidth,
            int exactCars,
            int exactWaste,
            int maxPatternCount,
            int maxOddUsages,
            long timeLimitMs,
            long nodeLimit,
            Map<PatternCandidate, Integer> warmStart) {
        return solveInternal(
                patterns, demands, totalWidth, exactCars, exactWaste,
                maxPatternCount, maxOddUsages, timeLimitMs, nodeLimit,
                warmStart, false);
    }

    static Result solveSingleOddParityKernel(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            int totalWidth,
            int exactCars,
            int exactWaste,
            int maxPatternCount,
            long timeLimitMs,
            long nodeLimit,
            Map<PatternCandidate, Integer> warmStart) {
        if (exactCars % 2 == 0) {
            throw new IllegalArgumentException(
                    "single-odd parity kernel requires an odd exact car count");
        }
        return solveInternal(
                patterns, demands, totalWidth, exactCars, exactWaste,
                maxPatternCount, 1, timeLimitMs, nodeLimit,
                warmStart, true);
    }

    private static Result solveInternal(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            int totalWidth,
            int exactCars,
            int exactWaste,
            int maxPatternCount,
            int maxOddUsages,
            long timeLimitMs,
            long nodeLimit,
            Map<PatternCandidate, Integer> warmStart,
            boolean singleOddParityKernel) {
        long startedAt = System.currentTimeMillis();
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            return new Result(MPSolver.ResultStatus.NOT_SOLVED, Map.of(), 0L, -1L);
        }

        int patternCount = patterns.size();
        MPVariable[] usage = new MPVariable[patternCount];
        MPVariable[] selected = new MPVariable[patternCount];
        MPVariable[] half = new MPVariable[patternCount];
        MPVariable[] odd = new MPVariable[patternCount];
        for (int index = 0; index < patternCount; index++) {
            usage[index] = solver.makeIntVar(0, exactCars, "x_" + index);
            selected[index] = solver.makeBoolVar("y_" + index);
            half[index] = solver.makeIntVar(0, exactCars, "h_" + index);
            boolean oddCompatible = !singleOddParityKernel
                    || hasDemandParityFingerprint(patterns.get(index), demands);
            odd[index] = solver.makeIntVar(
                    0, oddCompatible ? 1 : 0, "o_" + index);

            MPConstraint upperLink = solver.makeConstraint(
                    -MPSolver.infinity(), 0, "upper_link_" + index);
            upperLink.setCoefficient(usage[index], 1);
            upperLink.setCoefficient(selected[index], -exactCars);

            MPConstraint lowerLink = solver.makeConstraint(
                    -MPSolver.infinity(), 0, "lower_link_" + index);
            lowerLink.setCoefficient(selected[index], 1);
            lowerLink.setCoefficient(usage[index], -1);

            MPConstraint parity = solver.makeConstraint(0, 0, "parity_" + index);
            parity.setCoefficient(usage[index], 1);
            parity.setCoefficient(half[index], -2);
            parity.setCoefficient(odd[index], -1);
        }

        for (Map.Entry<Integer, Integer> demand : demands.entrySet()) {
            MPConstraint row = solver.makeConstraint(
                    demand.getValue(), demand.getValue(), "demand_" + demand.getKey());
            for (int index = 0; index < patternCount; index++) {
                int coefficient = patterns.get(index).getPattern()
                        .getOrDefault(demand.getKey(), 0);
                if (coefficient > 0) {
                    row.setCoefficient(usage[index], coefficient);
                }
            }
        }

        MPConstraint cars = solver.makeConstraint(exactCars, exactCars, "exact_cars");
        MPConstraint waste = solver.makeConstraint(exactWaste, exactWaste, "exact_waste");
        MPConstraint patternCap = solver.makeConstraint(
                0, maxPatternCount, "pattern_cap");
        MPConstraint oddCap = solver.makeConstraint(
                singleOddParityKernel ? 1 : 0, maxOddUsages, "odd_cap");
        for (int index = 0; index < patternCount; index++) {
            cars.setCoefficient(usage[index], 1);
            waste.setCoefficient(usage[index],
                    patterns.get(index).getRealWaste(totalWidth));
            patternCap.setCoefficient(selected[index], 1);
            oddCap.setCoefficient(odd[index], 1);
        }

        applyHint(solver, patterns, warmStart, usage, selected, half, odd);
        solver.objective().setMinimization();
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
            for (int index = 0; index < patternCount; index++) {
                int value = (int) Math.round(usage[index].solutionValue());
                if (value > 0) {
                    solution.put(patterns.get(index), value);
                }
            }
        }
        long nodes;
        try {
            nodes = solver.nodes();
        } catch (RuntimeException ignored) {
            nodes = -1L;
        }
        return new Result(
                status,
                Map.copyOf(solution),
                System.currentTimeMillis() - startedAt,
                nodes);
    }

    static boolean hasDemandParityFingerprint(
            PatternCandidate pattern,
            Map<Integer, Integer> demands) {
        for (Map.Entry<Integer, Integer> demand : demands.entrySet()) {
            int expectedParity = Math.floorMod(demand.getValue(), 2);
            int coefficientParity = Math.floorMod(
                    pattern.getPattern().getOrDefault(demand.getKey(), 0), 2);
            if (coefficientParity != expectedParity) {
                return false;
            }
        }
        return true;
    }

    private static void applyHint(
            MPSolver solver,
            List<PatternCandidate> patterns,
            Map<PatternCandidate, Integer> warmStart,
            MPVariable[] usage,
            MPVariable[] selected,
            MPVariable[] half,
            MPVariable[] odd) {
        if (warmStart == null || warmStart.isEmpty()) {
            return;
        }
        Map<String, Integer> hintBySignature = new LinkedHashMap<>();
        warmStart.forEach((pattern, value) ->
                hintBySignature.put(pattern.signature(), value));
        List<MPVariable> variables = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < patterns.size(); index++) {
            int value = hintBySignature.getOrDefault(patterns.get(index).signature(), 0);
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
            long nodes) {

        boolean feasible() {
            return status == MPSolver.ResultStatus.OPTIMAL
                    || status == MPSolver.ResultStatus.FEASIBLE;
        }
    }
}
