package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Research-only exact decomposition for an odd car total and a one-odd target.
 *
 * <p>For each pattern whose modulo-2 fingerprint equals the demand fingerprint,
 * subtract one copy and divide demand, cars and waste by two. The remaining
 * integer model chooses the even-use complement bundle.</p>
 */
final class SingleOddParityDecomposer {

    private SingleOddParityDecomposer() {
    }

    static SearchResult search(
            List<PatternCandidate> universe,
            Map<Integer, Integer> demands,
            int totalWidth,
            int exactCars,
            int exactWaste,
            int maxPatternCount,
            long timeLimitPerSeedMs,
            long nodeLimitPerSeed) {
        if (exactCars % 2 == 0) {
            throw new IllegalArgumentException("single-odd decomposition requires odd cars");
        }
        List<PatternCandidate> seeds = universe.stream()
                .filter(pattern -> PatternParityFeasibilityOracle
                        .hasDemandParityFingerprint(pattern, demands))
                .filter(pattern -> canSubtract(pattern, demands))
                .filter(pattern -> (exactWaste - pattern.getRealWaste(totalWidth)) % 2 == 0)
                .sorted(Comparator.comparingInt(
                                (PatternCandidate pattern) -> pattern.getRealWaste(totalWidth))
                        .thenComparing(PatternCandidate::signature))
                .toList();

        List<SeedAttempt> attempts = new ArrayList<>();
        SeedAttempt bestRelaxed = null;
        for (PatternCandidate seed : seeds) {
            SeedAttempt attempt = solveSeed(
                    universe,
                    seed,
                    demands,
                    totalWidth,
                    exactCars,
                    exactWaste,
                    maxPatternCount,
                    timeLimitPerSeedMs,
                    nodeLimitPerSeed);
            attempts.add(attempt);
            if (attempt.withinPatternCap()) {
                return new SearchResult(
                        attempt.solution(), seed, List.copyOf(attempts), seeds.size(), true);
            }
            if (attempt.feasible()
                    && (bestRelaxed == null
                    || attempt.solution().size() < bestRelaxed.solution().size())) {
                bestRelaxed = attempt;
            }
        }
        if (bestRelaxed != null) {
            return new SearchResult(
                    bestRelaxed.solution(), bestRelaxed.seed(),
                    List.copyOf(attempts), seeds.size(), false);
        }
        return new SearchResult(
                Map.of(), null, List.copyOf(attempts), seeds.size(), false);
    }

    private static SeedAttempt solveSeed(
            List<PatternCandidate> universe,
            PatternCandidate seed,
            Map<Integer, Integer> demands,
            int totalWidth,
            int exactCars,
            int exactWaste,
            int maxPatternCount,
            long timeLimitMs,
            long nodeLimit) {
        long startedAt = System.currentTimeMillis();
        Map<Integer, Integer> halfDemand = halfDemand(seed, demands);
        int halfCars = (exactCars - 1) / 2;
        int seedWaste = seed.getRealWaste(totalWidth);
        int halfWaste = (exactWaste - seedWaste) / 2;
        int seedIndex = indexOf(universe, seed.signature());

        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            return new SeedAttempt(seed, MPSolver.ResultStatus.NOT_SOLVED,
                    Map.of(), 0L, -1L, maxPatternCount);
        }
        int count = universe.size();
        MPVariable[] halfUsage = new MPVariable[count];
        for (int index = 0; index < count; index++) {
            halfUsage[index] = solver.makeIntVar(0, halfCars, "z_" + index);
        }

        for (Map.Entry<Integer, Integer> demand : halfDemand.entrySet()) {
            MPConstraint row = solver.makeConstraint(
                    demand.getValue(), demand.getValue(), "demand_" + demand.getKey());
            for (int index = 0; index < count; index++) {
                int coefficient = universe.get(index).getPattern()
                        .getOrDefault(demand.getKey(), 0);
                if (coefficient > 0) {
                    row.setCoefficient(halfUsage[index], coefficient);
                }
            }
        }

        MPConstraint cars = solver.makeConstraint(halfCars, halfCars, "half_cars");
        MPConstraint waste = solver.makeConstraint(halfWaste, halfWaste, "half_waste");
        for (int index = 0; index < count; index++) {
            cars.setCoefficient(halfUsage[index], 1);
            waste.setCoefficient(halfUsage[index],
                    universe.get(index).getRealWaste(totalWidth));
        }

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
            for (int index = 0; index < count; index++) {
                int value = 2 * (int) Math.round(halfUsage[index].solutionValue());
                if (index == seedIndex) {
                    value++;
                }
                if (value > 0) {
                    solution.put(universe.get(index), value);
                }
            }
        }
        long nodes;
        try {
            nodes = solver.nodes();
        } catch (RuntimeException ignored) {
            nodes = -1L;
        }
        return new SeedAttempt(
                seed,
                status,
                Map.copyOf(solution),
                System.currentTimeMillis() - startedAt,
                nodes,
                maxPatternCount);
    }

    private static boolean canSubtract(
            PatternCandidate pattern,
            Map<Integer, Integer> demands) {
        for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
            if (cut.getValue() > demands.getOrDefault(cut.getKey(), 0)) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, Integer> halfDemand(
            PatternCandidate seed,
            Map<Integer, Integer> demands) {
        Map<Integer, Integer> result = new TreeMap<>();
        for (Map.Entry<Integer, Integer> demand : demands.entrySet()) {
            int residual = demand.getValue()
                    - seed.getPattern().getOrDefault(demand.getKey(), 0);
            if (residual < 0 || residual % 2 != 0) {
                throw new IllegalArgumentException(
                        "seed does not match demand parity: " + seed.signature());
            }
            if (residual > 0) {
                result.put(demand.getKey(), residual / 2);
            }
        }
        return result;
    }

    private static int indexOf(List<PatternCandidate> patterns, String signature) {
        for (int index = 0; index < patterns.size(); index++) {
            if (patterns.get(index).signature().equals(signature)) {
                return index;
            }
        }
        throw new IllegalArgumentException("seed is not in universe: " + signature);
    }

    record SeedAttempt(
            PatternCandidate seed,
            MPSolver.ResultStatus status,
            Map<PatternCandidate, Integer> solution,
            long elapsedMs,
            long nodes,
            int maxPatternCount) {

        boolean feasible() {
            return status == MPSolver.ResultStatus.OPTIMAL
                    || status == MPSolver.ResultStatus.FEASIBLE;
        }

        boolean withinPatternCap() {
            return feasible() && solution.size() <= maxPatternCount;
        }
    }

    record SearchResult(
            Map<PatternCandidate, Integer> solution,
            PatternCandidate selectedSeed,
            List<SeedAttempt> attempts,
            int seedCount,
            boolean withinPatternCap) {

        boolean feasible() {
            return withinPatternCap && selectedSeed != null && !solution.isEmpty();
        }

        boolean hasRelaxedSolution() {
            return selectedSeed != null && !solution.isEmpty();
        }
    }
}
