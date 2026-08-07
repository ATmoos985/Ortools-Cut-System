package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DualVector;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Phase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds fresh GLOP and SCIP restricted masters for the research prototype. */
final class OrderGroupRestrictedMaster {

    static final double FEASIBILITY_COLUMN_COST = 1e-5;
    private static final double SMALL_GROUP_TIE_COST = 1e-4;
    private static final String SCIP_PARAMS =
            "parallel/maxnthreads = 1\n"
                    + "randomization/randomseedshift = 42\n"
                    + "randomization/permutationseed = 42\n"
                    + "randomization/lpseed = 42\n";

    private OrderGroupRestrictedMaster() {
    }

    static LpResult solveLp(
            Input input,
            Options options,
            List<GroupColumn> columns,
            Phase phase) {
        return solveLp(input, columns, phase, options.lpTimeLimitMs());
    }

    static LpResult solveLp(
            Input input,
            List<GroupColumn> columns,
            Phase phase,
            long timeLimitMs) {
        MPSolver solver = MPSolver.createSolver("GLOP");
        if (solver == null) {
            return LpResult.failed(MPSolver.ResultStatus.NOT_SOLVED);
        }
        solver.setTimeLimit(Math.max(1L, timeLimitMs));

        Model model = buildModel(solver, input, columns, phase, false, true, true);
        MPSolver.ResultStatus status = solver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL) {
            return LpResult.failed(status);
        }

        Map<DemandKey, Double> demandDual = new TreeMap<>();
        model.demandConstraints().forEach(
                (key, constraint) -> demandDual.put(key, constraint.dualValue()));
        Map<String, Double> familyDual = new TreeMap<>();
        model.familyConstraints().forEach(
                (key, constraint) -> familyDual.put(key, constraint.dualValue()));
        DualVector dual = new DualVector(
                demandDual,
                model.carsConstraint().dualValue(),
                model.wasteConstraint().dualValue(),
                model.oddConstraint().dualValue(),
                model.oneConstraint().dualValue(),
                familyDual);

        double maxArtificial = model.artificialVariables().stream()
                .mapToDouble(MPVariable::solutionValue)
                .max()
                .orElse(0.0);
        double artificialSum = model.artificialVariables().stream()
                .mapToDouble(MPVariable::solutionValue)
                .sum();
        double dualL1 = demandDual.values().stream()
                .mapToDouble(Math::abs)
                .sum()
                + Math.abs(dual.cars())
                + Math.abs(dual.waste())
                + Math.abs(dual.odd())
                + Math.abs(dual.oneCar())
                + familyDual.values().stream().mapToDouble(Math::abs).sum();
        Map<String, Double> columnValues = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            columnValues.put(
                    columns.get(index).signature(),
                    model.columnVariables().get(index).solutionValue());
        }
        return new LpResult(
                status,
                solver.objective().value(),
                maxArtificial,
                artificialSum,
                dual,
                dualL1,
                Collections.unmodifiableMap(columnValues),
                solver.iterations(),
                solver.numVariables(),
                solver.numConstraints());
    }

    static IntegerResult solveInteger(
            Input input,
            Options options,
            List<GroupColumn> columns) {
        MPSolver solver = MPSolver.createSolver("SCIP");
        String solverName = "SCIP";
        if (solver == null) {
            solver = MPSolver.createSolver("CBC");
            solverName = "CBC";
        }
        if (solver == null) {
            return IntegerResult.notSolved();
        }
        if ("SCIP".equals(solverName)) {
            solver.setSolverSpecificParametersAsString(SCIP_PARAMS);
            try {
                solver.setNumThreads(1);
            } catch (RuntimeException ignored) {
                // Some native builds do not expose thread control.
            }
        }
        solver.setTimeLimit(options.integerTimeLimitMs());

        Model model = buildModel(
                solver, input, columns, Phase.OPTIMIZATION, true, true, true);
        MPSolver.ResultStatus status = solver.solve();
        boolean feasible = status == MPSolver.ResultStatus.OPTIMAL
                || status == MPSolver.ResultStatus.FEASIBLE;
        if (!feasible) {
            return new IntegerResult(
                    status,
                    solverName,
                    List.of(),
                    Double.NaN,
                    readNodes(solver),
                    solver.numVariables(),
                    solver.numConstraints());
        }

        List<GroupColumn> selected = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            if (model.columnVariables().get(index).solutionValue() > 0.5) {
                selected.add(columns.get(index));
            }
        }
        selected.sort(Comparator.comparing(GroupColumn::signature));
        return new IntegerResult(
                status,
                solverName,
                List.copyOf(selected),
                solver.objective().value(),
                readNodes(solver),
                solver.numVariables(),
                solver.numConstraints());
    }

    static IntegerFeasibilityProfile diagnoseIntegerFeasibility(
            Input input,
            Options options,
            List<GroupColumn> columns) {
        MPSolver.ResultStatus productionOnly = solveFeasibilityVariant(
                input, options, columns, false, false);
        MPSolver.ResultStatus withOdd = solveFeasibilityVariant(
                input, options, columns, true, false);
        MPSolver.ResultStatus withFullShape = solveFeasibilityVariant(
                input, options, columns, true, true);
        return new IntegerFeasibilityProfile(
                productionOnly, withOdd, withFullShape);
    }

    static IntegerRepairResult solveIntegerRepair(
            Input input,
            Options options,
            List<GroupColumn> candidates) {
        MPSolver solver = MPSolver.createSolver("SCIP");
        String solverName = "SCIP";
        if (solver == null) {
            solver = MPSolver.createSolver("CBC");
            solverName = "CBC";
        }
        if (solver == null) {
            return IntegerRepairResult.notSolved();
        }
        if ("SCIP".equals(solverName)) {
            solver.setSolverSpecificParametersAsString(SCIP_PARAMS);
            try {
                solver.setNumThreads(1);
            } catch (RuntimeException ignored) {
                // Optional backend capability.
            }
        }
        solver.setTimeLimit(options.integerRepairTimeLimitMs());
        Model model = buildModel(
                solver,
                input,
                candidates,
                Phase.OPTIMIZATION,
                true,
                true,
                true);
        MPSolver.ResultStatus status = solver.solve();
        boolean feasible = status == MPSolver.ResultStatus.OPTIMAL
                || status == MPSolver.ResultStatus.FEASIBLE;
        double maxArtificial = feasible ? 0.0 : Double.POSITIVE_INFINITY;
        List<GroupColumn> selected = new ArrayList<>();
        if (feasible) {
            for (int index = 0; index < candidates.size(); index++) {
                if (model.columnVariables().get(index).solutionValue() > 0.5) {
                    selected.add(candidates.get(index));
                }
            }
        }
        selected.sort(Comparator.comparing(GroupColumn::signature));
        return new IntegerRepairResult(
                status,
                solverName,
                List.copyOf(selected),
                maxArtificial,
                feasible ? solver.objective().value() : Double.NaN,
                readNodes(solver),
                solver.numVariables(),
                solver.numConstraints());
    }

    static double optimizationCost(GroupColumn column) {
        return 1.0 + (column.small() ? SMALL_GROUP_TIE_COST : 0.0);
    }

    private static Model buildModel(
            MPSolver solver,
            Input input,
            List<GroupColumn> columns,
            Phase phase,
            boolean integer,
            boolean enforceOdd,
            boolean enforceOne) {
        List<MPVariable> columnVariables = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            MPVariable variable = integer
                    ? solver.makeBoolVar("z_" + index)
                    : solver.makeNumVar(0.0, 1.0, "z_" + index);
            columnVariables.add(variable);
        }

        Map<DemandKey, MPConstraint> demandConstraints = new TreeMap<>();
        for (Map.Entry<DemandKey, Integer> demand : input.demand().entrySet()) {
            demandConstraints.put(
                    demand.getKey(),
                    solver.makeConstraint(
                            demand.getValue(),
                            demand.getValue(),
                            "demand_" + safeName(demand.getKey().signature())));
        }
        MPConstraint carsConstraint = solver.makeConstraint(
                input.exactCars(), input.exactCars(), "cars");
        MPConstraint wasteConstraint = solver.makeConstraint(
                input.exactWaste(), input.exactWaste(), "waste");
        MPConstraint oddConstraint = enforceOdd
                ? solver.makeConstraint(
                        input.exactOddGroups(), input.exactOddGroups(), "odd_groups")
                : solver.makeConstraint(0.0, MPSolver.infinity(), "odd_groups_relaxed");
        MPConstraint oneConstraint = enforceOne
                ? solver.makeConstraint(
                        input.exactOneCarGroups(), input.exactOneCarGroups(), "one_groups")
                : solver.makeConstraint(0.0, MPSolver.infinity(), "one_groups_relaxed");

        for (int index = 0; index < columns.size(); index++) {
            GroupColumn column = columns.get(index);
            MPVariable variable = columnVariables.get(index);
            for (Map.Entry<DemandKey, Integer> coverage : column.coverage().entrySet()) {
                MPConstraint constraint = demandConstraints.get(coverage.getKey());
                if (constraint != null) {
                    constraint.setCoefficient(variable, coverage.getValue());
                }
            }
            carsConstraint.setCoefficient(variable, column.cars());
            wasteConstraint.setCoefficient(variable, column.totalWaste());
            oddConstraint.setCoefficient(variable, column.odd() ? 1.0 : 0.0);
            oneConstraint.setCoefficient(variable, column.oneCar() ? 1.0 : 0.0);
        }

        Map<String, MPConstraint> familyConstraints = new TreeMap<>();
        Map<String, List<Integer>> familyMembers = new TreeMap<>();
        for (int index = 0; index < columns.size(); index++) {
            familyMembers.computeIfAbsent(
                    columns.get(index).familySignature(), ignored -> new ArrayList<>())
                    .add(index);
        }
        int familyIndex = 0;
        for (Map.Entry<String, List<Integer>> family : familyMembers.entrySet()) {
            MPConstraint constraint = solver.makeConstraint(
                    0.0, 1.0, "family_" + familyIndex++);
            for (int index : family.getValue()) {
                constraint.setCoefficient(columnVariables.get(index), 1.0);
            }
            familyConstraints.put(family.getKey(), constraint);
        }

        MPObjective objective = solver.objective();
        for (int index = 0; index < columns.size(); index++) {
            objective.setCoefficient(
                    columnVariables.get(index),
                    phase == Phase.FEASIBILITY
                            ? FEASIBILITY_COLUMN_COST
                            : optimizationCost(columns.get(index)));
        }

        List<MPVariable> artificialVariables = new ArrayList<>();
        if (phase == Phase.FEASIBILITY) {
            addArtificialPair(
                    solver,
                    objective,
                    carsConstraint,
                    "cars",
                    normalizedWeight(input.exactCars()),
                    integer,
                    artificialVariables);
            addArtificialPair(
                    solver,
                    objective,
                    wasteConstraint,
                    "waste",
                    normalizedWeight(input.exactWaste()),
                    integer,
                    artificialVariables);
            addArtificialPair(
                    solver,
                    objective,
                    oddConstraint,
                    "odd",
                    normalizedWeight(input.exactOddGroups()),
                    integer,
                    artificialVariables);
            addArtificialPair(
                    solver,
                    objective,
                    oneConstraint,
                    "one",
                    normalizedWeight(input.exactOneCarGroups()),
                    integer,
                    artificialVariables);
            for (Map.Entry<DemandKey, MPConstraint> entry : demandConstraints.entrySet()) {
                addArtificialPair(
                        solver,
                        objective,
                        entry.getValue(),
                        "d_" + safeName(entry.getKey().signature()),
                        normalizedWeight(input.demand().get(entry.getKey())),
                        integer,
                        artificialVariables);
            }
        }
        objective.setMinimization();
        return new Model(
                solver,
                List.copyOf(columnVariables),
                Collections.unmodifiableMap(demandConstraints),
                carsConstraint,
                wasteConstraint,
                oddConstraint,
                oneConstraint,
                Collections.unmodifiableMap(familyConstraints),
                List.copyOf(artificialVariables));
    }

    private static MPSolver.ResultStatus solveFeasibilityVariant(
            Input input,
            Options options,
            List<GroupColumn> columns,
            boolean enforceOdd,
            boolean enforceOne) {
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            solver = MPSolver.createSolver("CBC");
        }
        if (solver == null) {
            return MPSolver.ResultStatus.NOT_SOLVED;
        }
        solver.setTimeLimit(Math.min(5_000L, options.integerTimeLimitMs()));
        Model model = buildModel(
                solver,
                input,
                columns,
                Phase.OPTIMIZATION,
                true,
                enforceOdd,
                enforceOne);
        MPObjective objective = solver.objective();
        objective.clear();
        objective.setMinimization();
        return solver.solve();
    }

    private static void addArtificialPair(
            MPSolver solver,
            MPObjective objective,
            MPConstraint constraint,
            String name,
            double weight,
            boolean integer,
            List<MPVariable> artificialVariables) {
        MPVariable positive = integer
                ? solver.makeIntVar(0.0, MPSolver.infinity(), "art_pos_" + name)
                : solver.makeNumVar(0.0, MPSolver.infinity(), "art_pos_" + name);
        MPVariable negative = integer
                ? solver.makeIntVar(0.0, MPSolver.infinity(), "art_neg_" + name)
                : solver.makeNumVar(0.0, MPSolver.infinity(), "art_neg_" + name);
        constraint.setCoefficient(positive, 1.0);
        constraint.setCoefficient(negative, -1.0);
        objective.setCoefficient(positive, weight);
        objective.setCoefficient(negative, weight);
        artificialVariables.add(positive);
        artificialVariables.add(negative);
    }

    private static double normalizedWeight(int target) {
        return 1.0 / Math.max(1, Math.abs(target));
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static long readNodes(MPSolver solver) {
        try {
            return solver.nodes();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private record Model(
            MPSolver solver,
            List<MPVariable> columnVariables,
            Map<DemandKey, MPConstraint> demandConstraints,
            MPConstraint carsConstraint,
            MPConstraint wasteConstraint,
            MPConstraint oddConstraint,
            MPConstraint oneConstraint,
            Map<String, MPConstraint> familyConstraints,
            List<MPVariable> artificialVariables) {
    }

    record LpResult(
            MPSolver.ResultStatus status,
            double objectiveValue,
            double maxArtificialValue,
            double artificialSum,
            DualVector dual,
            double previousDualL1,
            Map<String, Double> columnValues,
            long iterations,
            int variableCount,
            int constraintCount) {

        static LpResult failed(MPSolver.ResultStatus status) {
            return new LpResult(
                    status,
                    Double.NaN,
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    new DualVector(Map.of(), 0.0, 0.0, 0.0, 0.0, Map.of()),
                    Double.NaN,
                    Map.of(),
                    0L,
                    0,
                    0);
        }
    }

    record IntegerResult(
            MPSolver.ResultStatus status,
            String solverName,
            List<GroupColumn> selectedColumns,
            double objectiveValue,
            long nodes,
            int variableCount,
            int constraintCount) {

        boolean feasible() {
            return status == MPSolver.ResultStatus.OPTIMAL
                    || status == MPSolver.ResultStatus.FEASIBLE;
        }

        static IntegerResult notSolved() {
            return new IntegerResult(
                    MPSolver.ResultStatus.NOT_SOLVED,
                    "",
                    List.of(),
                    Double.NaN,
                    -1L,
                    0,
                    0);
        }
    }

    record IntegerFeasibilityProfile(
            MPSolver.ResultStatus productionOnly,
            MPSolver.ResultStatus withOdd,
            MPSolver.ResultStatus withFullShape) {
    }

    record IntegerRepairResult(
            MPSolver.ResultStatus status,
            String solverName,
            List<GroupColumn> selectedColumns,
            double maxArtificial,
            double objectiveValue,
            long nodes,
            int variableCount,
            int constraintCount) {

        boolean exact(double epsilon) {
            return (status == MPSolver.ResultStatus.OPTIMAL
                    || status == MPSolver.ResultStatus.FEASIBLE)
                    && maxArtificial <= epsilon;
        }

        static IntegerRepairResult notSolved() {
            return new IntegerRepairResult(
                    MPSolver.ResultStatus.NOT_SOLVED,
                    "",
                    List.of(),
                    Double.POSITIVE_INFINITY,
                    Double.NaN,
                    -1L,
                    0,
                    0);
        }
    }
}
