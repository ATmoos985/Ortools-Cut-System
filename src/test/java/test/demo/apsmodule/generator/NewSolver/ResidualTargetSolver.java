package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exact residual group-count feasibility judge used by the Target-24 research.
 *
 * <p>An infeasible solver status is promoted to {@link ProofState#PROVEN_INFEASIBLE}
 * only when the caller certifies that the candidate enumeration is complete.
 * Truncated candidate sets, time limits and solver failures remain
 * {@link ProofState#INCONCLUSIVE}.</p>
 */
final class ResidualTargetSolver {

    private static final String SCIP_PARAMS =
            "parallel/maxnthreads = 1\n"
                    + "randomization/randomseedshift = 42\n"
                    + "randomization/permutationseed = 42\n"
                    + "randomization/lpseed = 42\n";

    private ResidualTargetSolver() {
    }

    enum ProofState {
        FEASIBLE,
        PROVEN_INFEASIBLE,
        INCONCLUSIVE
    }

    enum InconclusiveReason {
        NONE,
        CANDIDATE_INCOMPLETE,
        TIME_LIMIT,
        SOLVER_UNAVAILABLE,
        SOLVER_NOT_PROVEN,
        SOLVER_ERROR,
        INVALID_INPUT,
        INVALID_SOLUTION
    }

    record Request(
            Map<DemandKey, Integer> residualDemand,
            int residualCars,
            int residualWaste,
            int residualOdd,
            int residualOne,
            List<GroupColumn> candidates,
            int targetGroups,
            boolean candidateComplete,
            long timeLimitMs) {

        Request {
            residualDemand = Collections.unmodifiableMap(
                    new TreeMap<>(Objects.requireNonNull(
                            residualDemand, "residualDemand")));
            candidates = List.copyOf(Objects.requireNonNull(
                    candidates, "candidates"));
        }
    }

    record Result(
            ProofState state,
            List<GroupColumn> selectedColumns,
            MPSolver.ResultStatus solverStatus,
            String solverName,
            InconclusiveReason inconclusiveReason,
            boolean candidateComplete,
            int candidateCount,
            int variableCount,
            int constraintCount,
            long nodes,
            long elapsedMs,
            String detail) {

        Result {
            selectedColumns = List.copyOf(selectedColumns);
            detail = Objects.requireNonNullElse(detail, "");
        }

        boolean feasible() {
            return state == ProofState.FEASIBLE;
        }
    }

    static Result solve(Request request) {
        Objects.requireNonNull(request, "request");
        long startedAt = System.currentTimeMillis();
        String invalid = validateRequest(request);
        if (invalid != null) {
            return inconclusive(
                    request, MPSolver.ResultStatus.NOT_SOLVED, "",
                    InconclusiveReason.INVALID_INPUT, 0, 0, 0,
                    0L, elapsed(startedAt), invalid);
        }

        List<GroupColumn> candidates = canonicalCandidates(request.candidates());
        MPSolver scip = MPSolver.createSolver("SCIP");
        if (scip != null) {
            scip.setSolverSpecificParametersAsString(SCIP_PARAMS);
        }
        MPSolver solver = scip != null ? scip : MPSolver.createSolver("CBC");
        if (solver == null) {
            return inconclusive(
                    request, MPSolver.ResultStatus.NOT_SOLVED, "",
                    InconclusiveReason.SOLVER_UNAVAILABLE, candidates.size(),
                    0, 0, 0L, elapsed(startedAt),
                    "Neither SCIP nor CBC is available");
        }
        String solverName = scip != null ? "SCIP" : "CBC";
        solver.setTimeLimit(Math.max(1L, request.timeLimitMs()));

        try {
            List<MPVariable> variables = new ArrayList<>(candidates.size());
            for (int index = 0; index < candidates.size(); index++) {
                variables.add(solver.makeBoolVar("x_" + index));
            }

            Map<DemandKey, MPConstraint> demandConstraints = new TreeMap<>();
            int demandIndex = 0;
            for (Map.Entry<DemandKey, Integer> entry
                    : request.residualDemand().entrySet()) {
                demandConstraints.put(
                        entry.getKey(),
                        solver.makeConstraint(
                                entry.getValue(), entry.getValue(),
                                "d_" + demandIndex++));
            }
            MPConstraint cars = solver.makeConstraint(
                    request.residualCars(), request.residualCars(), "cars");
            MPConstraint waste = solver.makeConstraint(
                    request.residualWaste(), request.residualWaste(), "waste");
            MPConstraint odd = solver.makeConstraint(
                    request.residualOdd(), request.residualOdd(), "odd");
            MPConstraint one = solver.makeConstraint(
                    request.residualOne(), request.residualOne(), "one");
            MPConstraint groupCap = solver.makeConstraint(
                    0.0, request.targetGroups(), "target_groups");

            Map<String, MPConstraint> familyConstraints = new TreeMap<>();
            MPObjective objective = solver.objective();
            for (int index = 0; index < candidates.size(); index++) {
                GroupColumn column = candidates.get(index);
                MPVariable variable = variables.get(index);
                for (Map.Entry<DemandKey, Integer> entry
                        : column.coverage().entrySet()) {
                    MPConstraint constraint = demandConstraints.get(entry.getKey());
                    if (constraint == null) {
                        return inconclusive(
                                request, MPSolver.ResultStatus.NOT_SOLVED,
                                solverName, InconclusiveReason.INVALID_INPUT,
                                candidates.size(), variables.size(),
                                demandConstraints.size() + 5
                                        + familyConstraints.size(),
                                0, elapsed(startedAt),
                                "Candidate covers a demand key outside the residual: "
                                        + column.signature());
                    }
                    constraint.setCoefficient(variable, entry.getValue());
                }
                cars.setCoefficient(variable, column.cars());
                waste.setCoefficient(variable, column.totalWaste());
                odd.setCoefficient(variable, column.odd() ? 1.0 : 0.0);
                one.setCoefficient(variable, column.oneCar() ? 1.0 : 0.0);
                groupCap.setCoefficient(variable, 1.0);
                familyConstraints
                        .computeIfAbsent(
                                column.familySignature(),
                                ignored -> solver.makeConstraint(
                                        0.0, 1.0,
                                        "family_" + familyConstraints.size()))
                        .setCoefficient(variable, 1.0);
                objective.setCoefficient(variable, 1.0);
            }
            objective.setMinimization();

            int constraintCount =
                    demandConstraints.size() + 5 + familyConstraints.size();
            MPSolver.ResultStatus status = solver.solve();
            long nodes = safeNodes(solver);
            long elapsedMs = elapsed(startedAt);

            if (status == MPSolver.ResultStatus.INFEASIBLE) {
                if (request.candidateComplete()) {
                    return new Result(
                            ProofState.PROVEN_INFEASIBLE, List.of(), status,
                            solverName, InconclusiveReason.NONE, true,
                            candidates.size(), variables.size(), constraintCount,
                            nodes, elapsedMs,
                            "Complete candidate model proved infeasible");
                }
                return inconclusive(
                        request, status, solverName,
                        InconclusiveReason.CANDIDATE_INCOMPLETE,
                        candidates.size(), variables.size(), constraintCount,
                        nodes, elapsedMs,
                        "Restricted candidate model is infeasible, but "
                                + "enumeration was incomplete");
            }
            if (status != MPSolver.ResultStatus.OPTIMAL
                    && status != MPSolver.ResultStatus.FEASIBLE) {
                InconclusiveReason reason =
                        status == MPSolver.ResultStatus.NOT_SOLVED
                                ? InconclusiveReason.TIME_LIMIT
                                : InconclusiveReason.SOLVER_NOT_PROVEN;
                return inconclusive(
                        request, status, solverName, reason,
                        candidates.size(), variables.size(), constraintCount,
                        nodes, elapsedMs,
                        "Solver returned " + status);
            }

            List<GroupColumn> selected = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                if (variables.get(index).solutionValue() > 0.5) {
                    selected.add(candidates.get(index));
                }
            }
            selected.sort(Comparator.comparing(GroupColumn::signature));
            if (selected.size() > request.targetGroups()
                    || !conserves(
                            selected, request.residualDemand(),
                            request.residualCars(), request.residualWaste(),
                            request.residualOdd(), request.residualOne())) {
                return inconclusive(
                        request, status, solverName,
                        InconclusiveReason.INVALID_SOLUTION,
                        candidates.size(), variables.size(), constraintCount,
                        nodes, elapsedMs,
                        "Solver result failed exact residual validation");
            }
            return new Result(
                    ProofState.FEASIBLE, selected, status, solverName,
                    InconclusiveReason.NONE, request.candidateComplete(),
                    candidates.size(), variables.size(), constraintCount,
                    nodes, elapsedMs, "Exact residual solution verified");
        } catch (RuntimeException | LinkageError error) {
            return inconclusive(
                    request, MPSolver.ResultStatus.ABNORMAL, solverName,
                    InconclusiveReason.SOLVER_ERROR, candidates.size(),
                    candidates.size(), 0, safeNodes(solver),
                    elapsed(startedAt), error.getClass().getSimpleName()
                            + ": " + Objects.toString(error.getMessage(), ""));
        }
    }

    static boolean conserves(
            List<GroupColumn> columns,
            Map<DemandKey, Integer> residualDemand,
            int residualCars,
            int residualWaste,
            int residualOdd,
            int residualOne) {
        Map<DemandKey, Integer> coverage = new TreeMap<>();
        int cars = 0;
        int waste = 0;
        int odd = 0;
        int one = 0;
        Set<String> families = new HashSet<>();
        for (GroupColumn column : columns) {
            column.coverage().forEach((key, value) ->
                    coverage.merge(key, value, Math::addExact));
            cars = Math.addExact(cars, column.cars());
            waste = Math.addExact(waste, column.totalWaste());
            odd += column.odd() ? 1 : 0;
            one += column.oneCar() ? 1 : 0;
            if (!families.add(column.familySignature())) {
                return false;
            }
        }
        return coverage.equals(residualDemand)
                && cars == residualCars
                && waste == residualWaste
                && odd == residualOdd
                && one == residualOne;
    }

    private static String validateRequest(Request request) {
        if (request.targetGroups() < 0) {
            return "targetGroups must be non-negative";
        }
        if (request.timeLimitMs() <= 0) {
            return "timeLimitMs must be positive";
        }
        if (request.residualCars() < 0 || request.residualWaste() < 0
                || request.residualOdd() < 0 || request.residualOne() < 0) {
            return "Residual resources must be non-negative";
        }
        if (request.residualOdd() > request.targetGroups()
                || request.residualOne() > request.residualOdd()) {
            return "Residual parity counts are inconsistent with targetGroups";
        }
        for (Map.Entry<DemandKey, Integer> entry
                : request.residualDemand().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue() <= 0) {
                return "Residual demand must contain positive values";
            }
        }
        return null;
    }

    private static List<GroupColumn> canonicalCandidates(
            List<GroupColumn> candidates) {
        Map<String, GroupColumn> bySignature = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparing(GroupColumn::signature))
                .forEach(column -> bySignature.putIfAbsent(
                        column.signature(), column));
        return List.copyOf(bySignature.values());
    }

    private static Result inconclusive(
            Request request,
            MPSolver.ResultStatus status,
            String solverName,
            InconclusiveReason reason,
            int candidateCount,
            int variableCount,
            int constraintCount,
            long nodes,
            long elapsedMs,
            String detail) {
        return new Result(
                ProofState.INCONCLUSIVE, List.of(), status, solverName,
                reason, request.candidateComplete(), candidateCount,
                variableCount, constraintCount, nodes, elapsedMs, detail);
    }

    private static long safeNodes(MPSolver solver) {
        try {
            return solver.nodes();
        } catch (RuntimeException | LinkageError ignored) {
            return -1L;
        }
    }

    private static long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }
}
