package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Result;
import test.demo.apsmodule.generator.NewSolver.colgen.ColumnGenerationSolver;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.PatternGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a non-manual integer incumbent for the research group-column master.
 *
 * <p>The seed has two deliberately separate stages. A small conventional
 * pattern MIP finds an exact production support with the necessary
 * pattern-usage parity. The same order-level group-column machinery then
 * expands only that support and proves a complete group assignment. Only the
 * selected complete group columns enter the full-universe pricing run.</p>
 */
final class OrderGroupColumnIncumbentSeeder {

    private static final String SCIP_PARAMS =
            "parallel/maxnthreads = 1\n"
                    + "randomization/randomseedshift = 42\n"
                    + "randomization/permutationseed = 42\n"
                    + "randomization/lpseed = 42\n";

    private OrderGroupColumnIncumbentSeeder() {
    }

    static SeedResult seed(Input input, Options options) {
        long startedAt = System.currentTimeMillis();
        SolverParameters seedParams = input.params().copy();
        seedParams.setTimeoutMs(options.patternSeedTimeLimitMs());

        Map<Integer, Integer> widthDemand = aggregateWidthDemand(input);
        long poolStartedAt = System.currentTimeMillis();
        List<PatternCandidate> generated =
                new PatternGenerator(seedParams).generate(widthDemand);
        List<PatternCandidate> candidatePool =
                new ColumnGenerationSolver(seedParams).solve(
                        generated, widthDemand, Set.of());
        long poolMs = System.currentTimeMillis() - poolStartedAt;

        long remainingMs = remaining(startedAt, options.patternSeedTimeLimitMs());
        if (remainingMs <= 0L) {
            return SeedResult.failed(
                    Status.PATTERN_POOL_TIME_LIMIT,
                    candidatePool.size(),
                    poolMs,
                    0L,
                    0L,
                    System.currentTimeMillis() - startedAt,
                    "pattern generation consumed the seed budget");
        }

        long mipStartedAt = System.currentTimeMillis();
        PatternSeed patternSeed = solvePatternSeed(
                input, candidatePool, remainingMs);
        long patternMipMs = System.currentTimeMillis() - mipStartedAt;
        if (!patternSeed.feasible()) {
            return SeedResult.failed(
                    Status.PATTERN_MIP_FAILED,
                    candidatePool.size(),
                    poolMs,
                    patternMipMs,
                    0L,
                    System.currentTimeMillis() - startedAt,
                    "status=" + patternSeed.status());
        }

        List<PatternCandidate> support = patternSeed.solution().keySet().stream()
                .sorted(Comparator.comparing(PatternCandidate::signature))
                .toList();
        Input supportInput = new Input(
                input.items(),
                support,
                input.params(),
                input.exactCars(),
                input.exactWaste(),
                input.exactOddGroups(),
                input.exactOneCarGroups());

        long groupStartedAt = System.currentTimeMillis();
        Options supportOptions = options.withoutAutomaticIncumbentSeed();
        Result supportResult = OrderGroupColumnPricingPrototype.solveCore(
                supportInput,
                supportOptions,
                List.of(),
                SeedResult.disabled(),
                groupStartedAt);
        long groupMs = System.currentTimeMillis() - groupStartedAt;
        if (!supportResult.feasible()) {
            return new SeedResult(
                    Status.GROUP_COLUMN_RMP_FAILED,
                    List.of(),
                    candidatePool.size(),
                    support.size(),
                    patternSeed.oddUsages(),
                    patternSeed.oneUsages(),
                    0,
                    poolMs,
                    patternMipMs,
                    groupMs,
                    System.currentTimeMillis() - startedAt,
                    "groupStatus=" + supportResult.status()
                            + ", pricingStop=" + supportResult.pricingTermination());
        }

        return new SeedResult(
                Status.READY,
                supportResult.selectedColumns(),
                candidatePool.size(),
                support.size(),
                patternSeed.oddUsages(),
                patternSeed.oneUsages(),
                supportResult.directMetrics().groups(),
                poolMs,
                patternMipMs,
                groupMs,
                System.currentTimeMillis() - startedAt,
                "patternStatus=" + patternSeed.status()
                        + ", groupStatus=" + supportResult.status());
    }

    private static PatternSeed solvePatternSeed(
            Input input,
            List<PatternCandidate> candidates,
            long timeLimitMs) {
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            return PatternSeed.failed(MPSolver.ResultStatus.NOT_SOLVED);
        }
        solver.setTimeLimit(Math.max(1L, timeLimitMs));
        solver.setSolverSpecificParametersAsString(SCIP_PARAMS);
        try {
            solver.setNumThreads(1);
        } catch (RuntimeException ignored) {
            // Optional native capability.
        }

        Map<Integer, MPConstraint> demandRows = new TreeMap<>();
        Map<Integer, Integer> widthDemand = aggregateWidthDemand(input);
        widthDemand.forEach((width, demand) -> demandRows.put(
                width,
                solver.makeConstraint(demand, demand, "width_" + width)));
        MPConstraint carsRow = solver.makeConstraint(
                input.exactCars(), input.exactCars(), "cars");
        MPConstraint wasteRow = solver.makeConstraint(
                input.exactWaste(), input.exactWaste(), "waste");
        MPConstraint oddRow = solver.makeConstraint(
                input.exactOddGroups(), input.exactOddGroups(), "usage_odd");

        List<MPVariable> usageVariables = new ArrayList<>(candidates.size());
        List<MPVariable> usedVariables = new ArrayList<>(candidates.size());
        List<MPVariable> oddVariables = new ArrayList<>(candidates.size());
        MPObjective objective = solver.objective();
        for (int index = 0; index < candidates.size(); index++) {
            PatternCandidate pattern = candidates.get(index);
            int upper = usageUpperBound(pattern, widthDemand, input.exactCars());
            MPVariable usage = solver.makeIntVar(0.0, upper, "x_" + index);
            MPVariable used = solver.makeBoolVar("y_" + index);
            MPVariable half = solver.makeIntVar(
                    0.0, Math.max(0, upper / 2), "q_" + index);
            MPVariable odd = solver.makeBoolVar("o_" + index);
            usageVariables.add(usage);
            usedVariables.add(used);
            oddVariables.add(odd);

            MPConstraint activationUpper =
                    solver.makeConstraint(-MPSolver.infinity(), 0.0);
            activationUpper.setCoefficient(usage, 1.0);
            activationUpper.setCoefficient(used, -upper);
            MPConstraint activationLower =
                    solver.makeConstraint(0.0, MPSolver.infinity());
            activationLower.setCoefficient(usage, 1.0);
            activationLower.setCoefficient(used, -2.0);
            MPConstraint parity = solver.makeConstraint(0.0, 0.0);
            parity.setCoefficient(usage, 1.0);
            parity.setCoefficient(half, -2.0);
            parity.setCoefficient(odd, -1.0);
            MPConstraint oddActivation =
                    solver.makeConstraint(-MPSolver.infinity(), 0.0);
            oddActivation.setCoefficient(odd, 1.0);
            oddActivation.setCoefficient(used, -1.0);

            pattern.getPattern().forEach((width, coefficient) ->
                    demandRows.get(width).setCoefficient(usage, coefficient));
            carsRow.setCoefficient(usage, 1.0);
            wasteRow.setCoefficient(
                    usage,
                    pattern.getRealWaste(input.params().getTotalWidth()));
            oddRow.setCoefficient(odd, 1.0);
            objective.setCoefficient(used, 1.0 + index * 1e-9);
        }
        objective.setMinimization();

        MPSolver.ResultStatus status = solver.solve();
        boolean feasible = status == MPSolver.ResultStatus.OPTIMAL
                || status == MPSolver.ResultStatus.FEASIBLE;
        if (!feasible) {
            return PatternSeed.failed(status);
        }

        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        int oddUsages = 0;
        int oneUsages = 0;
        for (int index = 0; index < candidates.size(); index++) {
            int usage = (int) Math.round(usageVariables.get(index).solutionValue());
            if (usage <= 0) {
                continue;
            }
            solution.put(candidates.get(index), usage);
            if (usage % 2 != 0) {
                oddUsages++;
            }
            if (usage == 1) {
                oneUsages++;
            }
        }
        return new PatternSeed(
                status,
                Collections.unmodifiableMap(solution),
                oddUsages,
                oneUsages);
    }

    private static Map<Integer, Integer> aggregateWidthDemand(Input input) {
        Map<Integer, Integer> demand = new TreeMap<>();
        input.demand().forEach((key, value) ->
                demand.merge(key.width(), value, Math::addExact));
        return demand;
    }

    private static int usageUpperBound(
            PatternCandidate pattern,
            Map<Integer, Integer> demand,
            int exactCars) {
        int upper = exactCars;
        for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
            upper = Math.min(
                    upper,
                    demand.getOrDefault(cut.getKey(), 0) / cut.getValue());
        }
        return Math.max(0, upper);
    }

    private static long remaining(long startedAt, long budgetMs) {
        return Math.max(0L, budgetMs - (System.currentTimeMillis() - startedAt));
    }

    enum Status {
        DISABLED,
        PATTERN_POOL_TIME_LIMIT,
        PATTERN_MIP_FAILED,
        GROUP_COLUMN_RMP_FAILED,
        READY
    }

    record SeedResult(
            Status status,
            List<GroupColumn> columns,
            int candidatePatterns,
            int supportPatterns,
            int oddUsagePatterns,
            int oneUsagePatterns,
            int groups,
            long patternPoolMs,
            long patternMipMs,
            long groupColumnMs,
            long totalMs,
            String detail) {

        SeedResult {
            columns = List.copyOf(columns);
            detail = detail == null ? "" : detail;
        }

        static SeedResult disabled() {
            return new SeedResult(
                    Status.DISABLED,
                    List.of(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    "");
        }

        static SeedResult failed(
                Status status,
                int candidatePatterns,
                long patternPoolMs,
                long patternMipMs,
                long groupColumnMs,
                long totalMs,
                String detail) {
            return new SeedResult(
                    status,
                    List.of(),
                    candidatePatterns,
                    0,
                    0,
                    0,
                    0,
                    patternPoolMs,
                    patternMipMs,
                    groupColumnMs,
                    totalMs,
                    detail);
        }
    }

    private record PatternSeed(
            MPSolver.ResultStatus status,
            Map<PatternCandidate, Integer> solution,
            int oddUsages,
            int oneUsages) {

        boolean feasible() {
            return status == MPSolver.ResultStatus.OPTIMAL
                    || status == MPSolver.ResultStatus.FEASIBLE;
        }

        static PatternSeed failed(MPSolver.ResultStatus status) {
            return new PatternSeed(status, Map.of(), 0, 0);
        }
    }
}
