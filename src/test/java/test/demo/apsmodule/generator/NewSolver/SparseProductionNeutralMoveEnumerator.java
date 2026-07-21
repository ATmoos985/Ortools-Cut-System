package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Research-only bounded sparse integer-kernel move enumerator. */
final class SparseProductionNeutralMoveEnumerator {

    private static final String SCIP_PARAMETERS =
            "parallel/maxnthreads = 1\n"
                    + "randomization/randomseedshift = 0\n"
                    + "randomization/permutationseed = 0\n"
                    + "randomization/lpseed = 0\n";

    private SparseProductionNeutralMoveEnumerator() {
    }

    static Result enumerate(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upperBounds,
            Set<String> excludedStateSignatures,
            Options options) {
        return enumerate(
                universe,
                current,
                upperBounds,
                excludedStateSignatures,
                List.of(),
                options);
    }

    static Result enumerate(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upperBounds,
            Set<String> excludedStateSignatures,
            List<Map<PatternCandidate, Integer>> seedMoves,
            Options options) {
        Objects.requireNonNull(excludedStateSignatures, "excludedStateSignatures");
        Objects.requireNonNull(seedMoves, "seedMoves");
        Objects.requireNonNull(options, "options");
        long startedAt = System.currentTimeMillis();
        long deadline = safeDeadline(startedAt, options.totalTimeLimitMs());
        IndexedData data = index(universe, current, upperBounds);

        MasterModel master = buildMaster(data, options);
        if (master == null) {
            return emptyResult(Status.SOLVER_UNAVAILABLE, data, startedAt);
        }

        Map<String, Candidate> candidatesByState = new LinkedHashMap<>();
        List<MasterIteration> masterIterations = new ArrayList<>();
        List<SupportRun> supportRuns = new ArrayList<>();
        long masterNodes = 0L;
        long subproblemNodes = 0L;
        int supportNoGoods = 0;
        int coefficientNoGoods = 0;
        int coefficientSolutions = 0;
        int excludedDuplicates = 0;
        int duplicateStates = 0;
        int seededSupportsVisited = 0;
        boolean supportExhausted = false;
        boolean coefficientExhausted = true;
        boolean timedOut = false;
        boolean capped = false;
        boolean abnormal = false;

        List<Support> seedSupports = seedSupports(data, seedMoves);
        for (Support support : seedSupports) {
            if (supportRuns.size() >= options.maxSupports()
                    || coefficientSolutions >= options.maxCoefficientSolutions()
                    || candidatesByState.size() >= options.maxUniqueStates()) {
                capped = true;
                break;
            }
            if (remainingMs(deadline) <= 0) {
                timedOut = true;
                break;
            }
            SubproblemResult subproblem = enumerateCoefficients(
                    data,
                    support,
                    excludedStateSignatures,
                    candidatesByState.keySet(),
                    options,
                    deadline,
                    options.maxCoefficientSolutions() - coefficientSolutions,
                    options.maxUniqueStates() - candidatesByState.size());
            coefficientSolutions += subproblem.coefficientSolutions();
            coefficientNoGoods += subproblem.noGoodCuts();
            excludedDuplicates += subproblem.excludedDuplicates();
            duplicateStates += subproblem.duplicateStates();
            subproblemNodes = addNodes(subproblemNodes, subproblem.nodes());
            subproblem.candidates().forEach(candidate ->
                    candidatesByState.putIfAbsent(
                            candidate.stateSignature(), candidate));
            supportRuns.add(subproblem.run());
            seededSupportsVisited++;
            addSupportNoGood(master, support, supportNoGoods++);
            if (!subproblem.exhausted()) {
                coefficientExhausted = false;
                if (subproblem.capped()) {
                    capped = true;
                } else if (subproblem.timedOut()) {
                    timedOut = true;
                } else {
                    abnormal = true;
                }
                break;
            }
        }

        while (!capped && !timedOut && !abnormal && coefficientExhausted) {
            if (supportRuns.size() >= options.maxSupports()
                    || coefficientSolutions >= options.maxCoefficientSolutions()
                    || candidatesByState.size() >= options.maxUniqueStates()) {
                capped = true;
                break;
            }
            long remaining = remainingMs(deadline);
            if (remaining <= 0) {
                timedOut = true;
                break;
            }
            long solveLimit = Math.min(remaining, options.masterSolveTimeLimitMs());
            master.solver().setTimeLimit(solveLimit);
            long solveStartedAt = System.currentTimeMillis();
            MPSolver.ResultStatus masterStatus = master.solver().solve();
            long solveElapsed = System.currentTimeMillis() - solveStartedAt;
            long nodes = safeNodes(master.solver());
            masterNodes = addNodes(masterNodes, nodes);
            masterIterations.add(new MasterIteration(
                    masterIterations.size() + 1,
                    masterStatus,
                    solveElapsed,
                    nodes,
                    supportNoGoods,
                    reportableObjectiveValue(masterStatus, master.solver().objective()),
                    reportableBestBound(masterStatus, master.solver().objective())));

            if (masterStatus == MPSolver.ResultStatus.INFEASIBLE) {
                supportExhausted = true;
                break;
            }
            if (masterStatus == MPSolver.ResultStatus.NOT_SOLVED) {
                timedOut = true;
                break;
            }
            if (!feasible(masterStatus)) {
                abnormal = true;
                break;
            }

            Support support = extractSupport(master);
            SubproblemResult subproblem = enumerateCoefficients(
                    data,
                    support,
                    excludedStateSignatures,
                    candidatesByState.keySet(),
                    options,
                    deadline,
                    options.maxCoefficientSolutions() - coefficientSolutions,
                    options.maxUniqueStates() - candidatesByState.size());
            coefficientSolutions += subproblem.coefficientSolutions();
            coefficientNoGoods += subproblem.noGoodCuts();
            excludedDuplicates += subproblem.excludedDuplicates();
            duplicateStates += subproblem.duplicateStates();
            subproblemNodes = addNodes(subproblemNodes, subproblem.nodes());
            subproblem.candidates().forEach(candidate ->
                    candidatesByState.putIfAbsent(
                            candidate.stateSignature(), candidate));
            supportRuns.add(subproblem.run());

            addSupportNoGood(master, support, supportNoGoods++);
            if (!subproblem.exhausted()) {
                coefficientExhausted = false;
                if (subproblem.capped()) {
                    capped = true;
                } else if (subproblem.timedOut()) {
                    timedOut = true;
                } else {
                    abnormal = true;
                }
                break;
            }
        }

        Status status = classifyStatus(
                false,
                abnormal,
                !coefficientExhausted,
                capped,
                timedOut,
                supportExhausted && coefficientExhausted);
        Metrics metrics = new Metrics(
                data.patterns().size(),
                data.supportSize(),
                masterIterations.size(),
                supportRuns.size(),
                seededSupportsVisited,
                supportNoGoods,
                coefficientSolutions,
                coefficientNoGoods,
                excludedDuplicates,
                duplicateStates,
                candidatesByState.size(),
                masterNodes,
                subproblemNodes,
                supportExhausted,
                coefficientExhausted,
                List.copyOf(masterIterations),
                List.copyOf(supportRuns),
                System.currentTimeMillis() - startedAt);
        return new Result(
                status,
                List.copyOf(candidatesByState.values()),
                metrics);
    }

    static Status classifyStatus(
            boolean solverUnavailable,
            boolean abnormal,
            boolean partial,
            boolean capped,
            boolean timedOut,
            boolean exhausted) {
        if (solverUnavailable) {
            return Status.SOLVER_UNAVAILABLE;
        }
        if (abnormal) {
            return Status.ABNORMAL;
        }
        if (capped) {
            return Status.CAPPED;
        }
        if (timedOut) {
            return Status.TIMED_OUT;
        }
        if (partial) {
            return Status.PARTIAL;
        }
        return exhausted ? Status.EXHAUSTED : Status.PARTIAL;
    }

    private static Result emptyResult(
            Status status,
            IndexedData data,
            long startedAt) {
        return new Result(
                status,
                List.of(),
                new Metrics(
                        data.patterns().size(),
                        data.supportSize(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        -1L,
                        -1L,
                        false,
                        false,
                        List.of(),
                        List.of(),
                        System.currentTimeMillis() - startedAt));
    }

    private static MasterModel buildMaster(IndexedData data, Options options) {
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            return null;
        }
        configure(solver, options.nodeLimit());
        int patternCount = data.patterns().size();
        MPVariable[] remove = new MPVariable[patternCount];
        MPVariable[] add = new MPVariable[patternCount];
        MPVariable[] removeSelected = new MPVariable[patternCount];
        MPVariable[] addSelected = new MPVariable[patternCount];
        MPVariable[] half = new MPVariable[patternCount];
        MPVariable[] odd = new MPVariable[patternCount];
        MPVariable[] remainActive = new MPVariable[patternCount];
        List<SupportVariable> supportVariables = new ArrayList<>();

        MPConstraint removeCardinality = solver.makeConstraint(
                1, 3, "remove_support");
        MPConstraint addCardinality = solver.makeConstraint(
                1, 3, "add_support");
        MPConstraint oddCount = solver.makeConstraint(1, 1, "odd_count");
        MPObjective objective = solver.objective();

        for (int index = 0; index < patternCount; index++) {
            int current = data.current()[index];
            int upper = data.upperBounds()[index];
            int addCapacity = upper - current;

            if (current > 0) {
                remove[index] = solver.makeIntVar(0, current, "remove_" + index);
                removeSelected[index] = solver.makeBoolVar("remove_selected_" + index);
                linkPositive(
                        solver,
                        remove[index],
                        removeSelected[index],
                        1,
                        current,
                        "remove_link_" + index);
                removeCardinality.setCoefficient(removeSelected[index], 1);
                supportVariables.add(new SupportVariable(
                        Side.REMOVE, index, removeSelected[index]));
            }

            int addLower = current == 0 ? 2 : 1;
            if (addCapacity >= addLower) {
                add[index] = solver.makeIntVar(0, addCapacity, "add_" + index);
                addSelected[index] = solver.makeBoolVar("add_selected_" + index);
                linkPositive(
                        solver,
                        add[index],
                        addSelected[index],
                        addLower,
                        addCapacity,
                        "add_link_" + index);
                addCardinality.setCoefficient(addSelected[index], 1);
                supportVariables.add(new SupportVariable(
                        Side.ADD, index, addSelected[index]));
            }

            if (removeSelected[index] != null && addSelected[index] != null) {
                MPConstraint disjoint = solver.makeConstraint(
                        -MPSolver.infinity(), 1, "disjoint_" + index);
                disjoint.setCoefficient(removeSelected[index], 1);
                disjoint.setCoefficient(addSelected[index], 1);
            }

            half[index] = solver.makeIntVar(0, upper / 2, "half_" + index);
            odd[index] = solver.makeIntVar(
                    0,
                    data.oddCompatible()[index] ? 1 : 0,
                    "odd_" + index);
            MPConstraint parity = solver.makeConstraint(
                    -current, -current, "parity_" + index);
            setIfPresent(parity, remove[index], -1);
            setIfPresent(parity, add[index], 1);
            parity.setCoefficient(half[index], -2);
            parity.setCoefficient(odd[index], -1);
            oddCount.setCoefficient(odd[index], 1);

            if (current > 0) {
                remainActive[index] = solver.makeBoolVar("remain_active_" + index);
                MPConstraint lower = solver.makeConstraint(
                        -current, MPSolver.infinity(), "next_lower_" + index);
                setIfPresent(lower, remove[index], -1);
                setIfPresent(lower, add[index], 1);
                lower.setCoefficient(remainActive[index], -2);

                MPConstraint upperLink = solver.makeConstraint(
                        -MPSolver.infinity(), -current, "next_upper_" + index);
                setIfPresent(upperLink, remove[index], -1);
                setIfPresent(upperLink, add[index], 1);
                upperLink.setCoefficient(remainActive[index], -upper);
            }

            double removeScore = options.preferredPatternSignatures()
                    .contains(data.patterns().get(index).signature()) ? -1000.0 : 1.0;
            if (removeSelected[index] != null) {
                objective.setCoefficient(removeSelected[index], removeScore);
            }
            if (addSelected[index] != null) {
                boolean touchesPreferredWidth = data.patterns().get(index).getPattern()
                        .keySet().stream()
                        .anyMatch(options.preferredWidths()::contains);
                objective.setCoefficient(
                        addSelected[index], touchesPreferredWidth ? -100.0 : 1.0);
            }
        }

        for (int widthIndex = 0;
                widthIndex < data.widths().size(); widthIndex++) {
            MPConstraint balance = solver.makeConstraint(
                    0, 0, "width_balance_" + data.widths().get(widthIndex));
            for (int patternIndex = 0;
                    patternIndex < patternCount; patternIndex++) {
                int coefficient = data.coefficients()[patternIndex][widthIndex];
                if (coefficient == 0) {
                    continue;
                }
                setIfPresent(balance, remove[patternIndex], -coefficient);
                setIfPresent(balance, add[patternIndex], coefficient);
            }
        }
        MPConstraint cars = solver.makeConstraint(0, 0, "car_balance");
        for (int index = 0; index < patternCount; index++) {
            setIfPresent(cars, remove[index], -1);
            setIfPresent(cars, add[index], 1);
        }
        objective.setMinimization();
        return new MasterModel(
                solver,
                remove,
                add,
                removeSelected,
                addSelected,
                List.copyOf(supportVariables));
    }

    private static void linkPositive(
            MPSolver solver,
            MPVariable amount,
            MPVariable selected,
            int lower,
            int upper,
            String name) {
        MPConstraint lowerLink = solver.makeConstraint(0, MPSolver.infinity(), name + "_lower");
        lowerLink.setCoefficient(amount, 1);
        lowerLink.setCoefficient(selected, -lower);
        MPConstraint upperLink = solver.makeConstraint(-MPSolver.infinity(), 0, name + "_upper");
        upperLink.setCoefficient(amount, 1);
        upperLink.setCoefficient(selected, -upper);
    }

    private static Support extractSupport(MasterModel master) {
        List<Integer> removals = new ArrayList<>();
        List<Integer> additions = new ArrayList<>();
        for (int index = 0; index < master.removeSelected().length; index++) {
            if (selected(master.removeSelected()[index])) {
                removals.add(index);
            }
            if (selected(master.addSelected()[index])) {
                additions.add(index);
            }
        }
        if (removals.isEmpty() || additions.isEmpty()
                || removals.size() > 3 || additions.size() > 3) {
            throw new IllegalStateException(
                    "master returned an invalid support: remove="
                            + removals + ", add=" + additions);
        }
        return new Support(List.copyOf(removals), List.copyOf(additions));
    }

    private static SubproblemResult enumerateCoefficients(
            IndexedData data,
            Support support,
            Set<String> excludedStateSignatures,
            Set<String> alreadyFoundStateSignatures,
            Options options,
            long globalDeadline,
            int remainingCoefficientSolutions,
            int remainingUniqueStates) {
        long startedAt = System.currentTimeMillis();
        long localDeadline = Math.min(
                globalDeadline,
                safeDeadline(startedAt, options.subproblemTimeLimitMs()));
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            SupportRun run = new SupportRun(
                    support.signature(),
                    MPSolver.ResultStatus.NOT_SOLVED,
                    0,
                    0,
                    0,
                    false,
                    System.currentTimeMillis() - startedAt,
                    -1L);
            return new SubproblemResult(
                    List.of(), run, 0, 0, 0, 0, -1L,
                    false, false, false);
        }
        configure(solver, options.nodeLimit());

        List<CoefficientVariable> coefficientVariables = new ArrayList<>();
        Map<Integer, MPVariable> removeByIndex = new LinkedHashMap<>();
        Map<Integer, MPVariable> addByIndex = new LinkedHashMap<>();
        for (int index : support.removals()) {
            MPVariable variable = solver.makeIntVar(
                    1, data.current()[index], "remove_" + index);
            removeByIndex.put(index, variable);
            coefficientVariables.add(new CoefficientVariable(
                    Side.REMOVE, index, variable, 1, data.current()[index]));
        }
        for (int index : support.additions()) {
            int lower = data.current()[index] == 0 ? 2 : 1;
            int upper = data.upperBounds()[index] - data.current()[index];
            MPVariable variable = solver.makeIntVar(
                    lower, upper, "add_" + index);
            addByIndex.put(index, variable);
            coefficientVariables.add(new CoefficientVariable(
                    Side.ADD, index, variable, lower, upper));
        }

        for (int widthIndex = 0;
                widthIndex < data.widths().size(); widthIndex++) {
            MPConstraint balance = solver.makeConstraint(
                    0, 0, "width_balance_" + data.widths().get(widthIndex));
            for (CoefficientVariable variable : coefficientVariables) {
                int coefficient = data.coefficients()[variable.patternIndex()][widthIndex];
                if (coefficient != 0) {
                    balance.setCoefficient(
                            variable.variable(),
                            variable.side() == Side.REMOVE ? -coefficient : coefficient);
                }
            }
        }
        MPConstraint cars = solver.makeConstraint(0, 0, "car_balance");
        coefficientVariables.forEach(variable -> cars.setCoefficient(
                variable.variable(), variable.side() == Side.REMOVE ? -1 : 1));

        Set<Integer> affected = new TreeSet<>();
        affected.addAll(support.removals());
        affected.addAll(support.additions());
        int fixedOdd = 0;
        for (int index = 0; index < data.patterns().size(); index++) {
            if (!affected.contains(index) && data.current()[index] % 2 != 0) {
                fixedOdd++;
            }
        }
        MPConstraint oddTarget = solver.makeConstraint(
                1 - fixedOdd, 1 - fixedOdd, "odd_target");
        for (int index : affected) {
            int current = data.current()[index];
            int upper = data.upperBounds()[index];
            MPVariable half = solver.makeIntVar(0, upper / 2, "half_" + index);
            MPVariable odd = solver.makeIntVar(
                    0, data.oddCompatible()[index] ? 1 : 0, "odd_" + index);
            MPConstraint parity = solver.makeConstraint(
                    -current, -current, "parity_" + index);
            setIfPresent(parity, removeByIndex.get(index), -1);
            setIfPresent(parity, addByIndex.get(index), 1);
            parity.setCoefficient(half, -2);
            parity.setCoefficient(odd, -1);
            oddTarget.setCoefficient(odd, 1);

            if (current > 0 && removeByIndex.containsKey(index)) {
                MPVariable active = solver.makeBoolVar("active_" + index);
                MPConstraint lower = solver.makeConstraint(
                        -current, MPSolver.infinity(), "next_lower_" + index);
                lower.setCoefficient(removeByIndex.get(index), -1);
                lower.setCoefficient(active, -2);
                MPConstraint upperLink = solver.makeConstraint(
                        -MPSolver.infinity(), -current, "next_upper_" + index);
                upperLink.setCoefficient(removeByIndex.get(index), -1);
                upperLink.setCoefficient(active, -upper);
            }
        }
        solver.objective().setMinimization();

        List<Candidate> candidates = new ArrayList<>();
        Set<String> localStates = new LinkedHashSet<>();
        int noGoodCuts = 0;
        int solutions = 0;
        int excludedDuplicates = 0;
        int duplicateStates = 0;
        long nodes = 0L;
        boolean exhausted = false;
        boolean timedOut = false;
        boolean capped = false;
        MPSolver.ResultStatus lastStatus = MPSolver.ResultStatus.NOT_SOLVED;

        while (true) {
            if (solutions >= remainingCoefficientSolutions
                    || candidates.size() >= remainingUniqueStates) {
                capped = true;
                break;
            }
            long remaining = remainingMs(localDeadline);
            if (remaining <= 0) {
                timedOut = true;
                break;
            }
            solver.setTimeLimit(remaining);
            lastStatus = solver.solve();
            nodes = addNodes(nodes, safeNodes(solver));
            if (lastStatus == MPSolver.ResultStatus.INFEASIBLE) {
                exhausted = true;
                break;
            }
            if (lastStatus == MPSolver.ResultStatus.NOT_SOLVED) {
                timedOut = true;
                break;
            }
            if (!feasible(lastStatus)) {
                break;
            }

            solutions++;
            int[] values = new int[coefficientVariables.size()];
            int[] next = data.current().clone();
            Map<PatternCandidate, Integer> delta = new LinkedHashMap<>();
            for (int position = 0;
                    position < coefficientVariables.size(); position++) {
                CoefficientVariable variable = coefficientVariables.get(position);
                int value = (int) Math.round(variable.variable().solutionValue());
                values[position] = value;
                int signed = variable.side() == Side.REMOVE ? -value : value;
                next[variable.patternIndex()] += signed;
                delta.put(data.patterns().get(variable.patternIndex()), signed);
            }
            String stateSignature = stateSignature(data.patterns(), next);
            if (excludedStateSignatures.contains(stateSignature)) {
                excludedDuplicates++;
            } else if (alreadyFoundStateSignatures.contains(stateSignature)
                    || !localStates.add(stateSignature)) {
                duplicateStates++;
            } else {
                candidates.add(new Candidate(
                        Collections.unmodifiableMap(delta),
                        solution(data.patterns(), next),
                        stateSignature,
                        support.signature(),
                        coefficientSignature(coefficientVariables, values)));
            }
            addCoefficientNoGood(
                    solver, coefficientVariables, values, noGoodCuts++);
        }

        SupportRun run = new SupportRun(
                support.signature(),
                lastStatus,
                solutions,
                candidates.size(),
                noGoodCuts,
                exhausted,
                System.currentTimeMillis() - startedAt,
                nodes);
        return new SubproblemResult(
                List.copyOf(candidates),
                run,
                solutions,
                noGoodCuts,
                excludedDuplicates,
                duplicateStates,
                nodes,
                exhausted,
                timedOut,
                capped);
    }

    private static void addCoefficientNoGood(
            MPSolver solver,
            List<CoefficientVariable> variables,
            int[] values,
            int ordinal) {
        MPConstraint atLeastOneDifference = solver.makeConstraint(
                1, MPSolver.infinity(), "coefficient_nogood_" + ordinal);
        for (int index = 0; index < variables.size(); index++) {
            CoefficientVariable coefficient = variables.get(index);
            int value = values[index];
            MPVariable less = solver.makeBoolVar(
                    "coefficient_less_" + ordinal + "_" + index);
            MPVariable greater = solver.makeBoolVar(
                    "coefficient_greater_" + ordinal + "_" + index);

            MPConstraint lessLink = solver.makeConstraint(
                    -MPSolver.infinity(), coefficient.upper(),
                    "coefficient_less_link_" + ordinal + "_" + index);
            lessLink.setCoefficient(coefficient.variable(), 1);
            lessLink.setCoefficient(
                    less, coefficient.upper() - value + 1);

            MPConstraint greaterLink = solver.makeConstraint(
                    coefficient.lower(), MPSolver.infinity(),
                    "coefficient_greater_link_" + ordinal + "_" + index);
            greaterLink.setCoefficient(coefficient.variable(), 1);
            greaterLink.setCoefficient(
                    greater, -(value + 1 - coefficient.lower()));

            atLeastOneDifference.setCoefficient(less, 1);
            atLeastOneDifference.setCoefficient(greater, 1);
        }
    }

    private static void addSupportNoGood(
            MasterModel master,
            Support support,
            int ordinal) {
        Set<String> selected = new LinkedHashSet<>();
        support.removals().forEach(index -> selected.add(Side.REMOVE + ":" + index));
        support.additions().forEach(index -> selected.add(Side.ADD + ":" + index));
        int selectedCount = selected.size();
        MPConstraint cut = master.solver().makeConstraint(
                1 - selectedCount,
                MPSolver.infinity(),
                "support_nogood_" + ordinal);
        for (SupportVariable variable : master.supportVariables()) {
            String key = variable.side() + ":" + variable.patternIndex();
            cut.setCoefficient(variable.variable(), selected.contains(key) ? -1 : 1);
        }
    }

    private static List<Support> seedSupports(
            IndexedData data,
            List<Map<PatternCandidate, Integer>> seedMoves) {
        Map<String, Support> supports = new LinkedHashMap<>();
        for (Map<PatternCandidate, Integer> seedMove : seedMoves) {
            if (seedMove == null || seedMove.isEmpty()) {
                continue;
            }
            Set<Integer> removals = new TreeSet<>();
            Set<Integer> additions = new TreeSet<>();
            for (Map.Entry<PatternCandidate, Integer> entry : seedMove.entrySet()) {
                if (entry.getValue() == null || entry.getValue() == 0) {
                    continue;
                }
                Integer index = data.indexBySignature()
                        .get(entry.getKey().signature());
                if (index == null) {
                    throw new IllegalArgumentException(
                            "seed move pattern is outside universe: "
                                    + entry.getKey().signature());
                }
                if (entry.getValue() < 0) {
                    removals.add(index);
                } else {
                    additions.add(index);
                }
            }
            if (removals.isEmpty() || additions.isEmpty()
                    || removals.size() > 3 || additions.size() > 3) {
                continue;
            }
            Support support = new Support(
                    List.copyOf(removals), List.copyOf(additions));
            supports.putIfAbsent(support.signature(), support);
        }
        return List.copyOf(supports.values());
    }

    private static IndexedData index(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upperBounds) {
        Objects.requireNonNull(universe, "universe");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(upperBounds, "upperBounds");
        if (universe.isEmpty() || current.isEmpty()) {
            throw new IllegalArgumentException("universe and current must not be empty");
        }
        List<PatternCandidate> patterns = universe.stream()
                .map(pattern -> Objects.requireNonNull(pattern,
                        "universe contains null"))
                .sorted(Comparator.comparing(PatternCandidate::signature))
                .toList();
        Map<String, Integer> indexBySignature = new LinkedHashMap<>();
        for (int index = 0; index < patterns.size(); index++) {
            if (indexBySignature.put(patterns.get(index).signature(), index) != null) {
                throw new IllegalArgumentException(
                        "duplicate universe signature: " + patterns.get(index).signature());
            }
        }
        int[] currentValues = new int[patterns.size()];
        int[] upperValues = new int[patterns.size()];
        Map<String, Integer> currentBySignature = new HashMap<>();
        current.forEach((pattern, value) -> {
            if (!indexBySignature.containsKey(pattern.signature())) {
                throw new IllegalArgumentException(
                        "current pattern is outside universe: " + pattern.signature());
            }
            if (value == null || value < 0) {
                throw new IllegalArgumentException("current usage must not be negative");
            }
            currentBySignature.put(pattern.signature(), value);
        });
        Map<String, Integer> upperBySignature = new HashMap<>();
        upperBounds.forEach((pattern, value) -> {
            if (value == null || value < 0) {
                throw new IllegalArgumentException("upper bound must not be negative");
            }
            upperBySignature.put(pattern.signature(), value);
        });
        for (int index = 0; index < patterns.size(); index++) {
            String signature = patterns.get(index).signature();
            currentValues[index] = currentBySignature.getOrDefault(signature, 0);
            Integer upper = upperBySignature.get(signature);
            if (upper == null) {
                throw new IllegalArgumentException("missing upper bound: " + signature);
            }
            if (currentValues[index] > upper) {
                throw new IllegalArgumentException(
                        "current usage exceeds upper bound: " + signature);
            }
            upperValues[index] = upper;
        }

        Set<Integer> widthSet = new TreeSet<>();
        patterns.forEach(pattern -> widthSet.addAll(pattern.getPattern().keySet()));
        List<Integer> widths = List.copyOf(widthSet);
        int[][] coefficients = new int[patterns.size()][widths.size()];
        int[] production = new int[widths.size()];
        for (int patternIndex = 0;
                patternIndex < patterns.size(); patternIndex++) {
            for (int widthIndex = 0;
                    widthIndex < widths.size(); widthIndex++) {
                int coefficient = patterns.get(patternIndex).getPattern()
                        .getOrDefault(widths.get(widthIndex), 0);
                coefficients[patternIndex][widthIndex] = coefficient;
                production[widthIndex] += coefficient * currentValues[patternIndex];
            }
        }
        boolean[] oddCompatible = new boolean[patterns.size()];
        for (int patternIndex = 0;
                patternIndex < patterns.size(); patternIndex++) {
            boolean compatible = true;
            for (int widthIndex = 0;
                    widthIndex < widths.size(); widthIndex++) {
                if (Math.floorMod(coefficients[patternIndex][widthIndex], 2)
                        != Math.floorMod(production[widthIndex], 2)) {
                    compatible = false;
                    break;
                }
            }
            oddCompatible[patternIndex] = compatible;
        }
        int supportSize = 0;
        for (int value : currentValues) {
            if (value > 0) {
                supportSize++;
            }
        }
        return new IndexedData(
                patterns,
                Map.copyOf(indexBySignature),
                widths,
                coefficients,
                currentValues,
                upperValues,
                oddCompatible,
                supportSize);
    }

    private static void configure(MPSolver solver, long nodeLimit) {
        solver.setNumThreads(1);
        String parameters = SCIP_PARAMETERS;
        if (nodeLimit > 0) {
            parameters += "limits/nodes = " + nodeLimit + "\n";
        }
        solver.setSolverSpecificParametersAsString(parameters);
    }

    private static boolean selected(MPVariable variable) {
        return variable != null && variable.solutionValue() > 0.5;
    }

    private static boolean feasible(MPSolver.ResultStatus status) {
        return status == MPSolver.ResultStatus.OPTIMAL
                || status == MPSolver.ResultStatus.FEASIBLE;
    }

    private static void setIfPresent(
            MPConstraint constraint,
            MPVariable variable,
            double coefficient) {
        if (variable != null) {
            constraint.setCoefficient(variable, coefficient);
        }
    }

    private static long safeNodes(MPSolver solver) {
        try {
            return solver.nodes();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static double reportableObjectiveValue(
            MPSolver.ResultStatus status,
            MPObjective objective) {
        if (!feasible(status)) {
            return Double.NaN;
        }
        try {
            return objective.value();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static double reportableBestBound(
            MPSolver.ResultStatus status,
            MPObjective objective) {
        if (!feasible(status)) {
            return Double.NaN;
        }
        try {
            return objective.bestBound();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static long addNodes(long left, long right) {
        if (left < 0) {
            return right;
        }
        if (right < 0) {
            return left;
        }
        return left + right;
    }

    private static long safeDeadline(long now, long durationMs) {
        return durationMs >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationMs;
    }

    private static long remainingMs(long deadline) {
        return Math.max(0L, deadline - System.currentTimeMillis());
    }

    private static Map<PatternCandidate, Integer> solution(
            List<PatternCandidate> patterns,
            int[] values) {
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index++) {
            if (values[index] > 0) {
                solution.put(patterns.get(index), values[index]);
            }
        }
        return Collections.unmodifiableMap(solution);
    }

    private static String stateSignature(
            List<PatternCandidate> patterns,
            int[] values) {
        StringBuilder signature = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (values[index] <= 0) {
                continue;
            }
            if (!signature.isEmpty()) {
                signature.append(';');
            }
            signature.append(patterns.get(index).signature())
                    .append('=').append(values[index]);
        }
        return signature.toString();
    }

    private static String coefficientSignature(
            List<CoefficientVariable> variables,
            int[] values) {
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < variables.size(); index++) {
            CoefficientVariable variable = variables.get(index);
            parts.add(variable.side() + ":" + variable.patternIndex()
                    + "=" + values[index]);
        }
        return String.join(";", parts);
    }

    enum Status {
        EXHAUSTED,
        TIMED_OUT,
        CAPPED,
        PARTIAL,
        SOLVER_UNAVAILABLE,
        ABNORMAL
    }

    record Options(
            long totalTimeLimitMs,
            long masterSolveTimeLimitMs,
            long subproblemTimeLimitMs,
            long nodeLimit,
            int maxSupports,
            int maxCoefficientSolutions,
            int maxUniqueStates,
            Set<String> preferredPatternSignatures,
            Set<Integer> preferredWidths) {

        Options {
            if (totalTimeLimitMs <= 0
                    || masterSolveTimeLimitMs <= 0
                    || subproblemTimeLimitMs <= 0) {
                throw new IllegalArgumentException("time limits must be positive");
            }
            if (nodeLimit == 0 || nodeLimit < -1) {
                throw new IllegalArgumentException("nodeLimit must be -1 or positive");
            }
            if (maxSupports <= 0
                    || maxCoefficientSolutions <= 0
                    || maxUniqueStates <= 0) {
                throw new IllegalArgumentException("enumeration caps must be positive");
            }
            preferredPatternSignatures = Set.copyOf(preferredPatternSignatures);
            preferredWidths = Set.copyOf(preferredWidths);
        }

        static Options testDefaults() {
            return new Options(
                    30_000L,
                    10_000L,
                    10_000L,
                    -1L,
                    100,
                    1000,
                    1000,
                    Set.of(),
                    Set.of());
        }
    }

    record Candidate(
            Map<PatternCandidate, Integer> delta,
            Map<PatternCandidate, Integer> solution,
            String stateSignature,
            String supportSignature,
            String coefficientSignature) {

        Candidate {
            delta = Collections.unmodifiableMap(new LinkedHashMap<>(delta));
            solution = Collections.unmodifiableMap(new LinkedHashMap<>(solution));
            stateSignature = Objects.requireNonNull(stateSignature);
            supportSignature = Objects.requireNonNull(supportSignature);
            coefficientSignature = Objects.requireNonNull(coefficientSignature);
        }
    }

    record MasterIteration(
            int iteration,
            MPSolver.ResultStatus status,
            long elapsedMs,
            long nodes,
            int supportNoGoodsBeforeSolve,
            double objectiveValue,
            double bestBound) {
    }

    record SupportRun(
            String supportSignature,
            MPSolver.ResultStatus lastStatus,
            int coefficientSolutions,
            int uniqueStates,
            int noGoodCuts,
            boolean exhausted,
            long elapsedMs,
            long nodes) {
    }

    record Metrics(
            int universeSize,
            int initialSupportSize,
            int masterSolves,
            int supportsVisited,
            int seededSupportsVisited,
            int supportNoGoodCuts,
            int coefficientSolutions,
            int coefficientNoGoodCuts,
            int excludedDuplicates,
            int duplicateStates,
            int uniqueStates,
            long masterNodes,
            long subproblemNodes,
            boolean supportExhausted,
            boolean coefficientExhausted,
            List<MasterIteration> masterIterations,
            List<SupportRun> supportRuns,
            long totalElapsedMs) {

        Metrics {
            masterIterations = List.copyOf(masterIterations);
            supportRuns = List.copyOf(supportRuns);
        }
    }

    record Result(Status status, List<Candidate> candidates, Metrics metrics) {

        Result {
            status = Objects.requireNonNull(status);
            candidates = List.copyOf(candidates);
            metrics = Objects.requireNonNull(metrics);
        }
    }

    private enum Side {
        REMOVE,
        ADD
    }

    private record IndexedData(
            List<PatternCandidate> patterns,
            Map<String, Integer> indexBySignature,
            List<Integer> widths,
            int[][] coefficients,
            int[] current,
            int[] upperBounds,
            boolean[] oddCompatible,
            int supportSize) {
    }

    private record SupportVariable(
            Side side,
            int patternIndex,
            MPVariable variable) {
    }

    private record MasterModel(
            MPSolver solver,
            MPVariable[] remove,
            MPVariable[] add,
            MPVariable[] removeSelected,
            MPVariable[] addSelected,
            List<SupportVariable> supportVariables) {
    }

    private record Support(List<Integer> removals, List<Integer> additions) {

        String signature() {
            return "R" + removals + "|A" + additions;
        }
    }

    private record CoefficientVariable(
            Side side,
            int patternIndex,
            MPVariable variable,
            int lower,
            int upper) {
    }

    private record SubproblemResult(
            List<Candidate> candidates,
            SupportRun run,
            int coefficientSolutions,
            int noGoodCuts,
            int excludedDuplicates,
            int duplicateStates,
            long nodes,
            boolean exhausted,
            boolean timedOut,
            boolean capped) {
    }
}
