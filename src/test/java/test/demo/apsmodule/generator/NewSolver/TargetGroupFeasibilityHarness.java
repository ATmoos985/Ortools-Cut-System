package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupDestroySetPlanner.DestroySet;
import test.demo.apsmodule.generator.NewSolver.ResidualTargetSolver.InconclusiveReason;
import test.demo.apsmodule.generator.NewSolver.ResidualTargetSolver.ProofState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Coordinates deterministic destroy sets, residual candidate enumeration and
 * the exact three-state residual feasibility judge.
 */
final class TargetGroupFeasibilityHarness {

    private TargetGroupFeasibilityHarness() {
    }

    record Options(
            OrderGroupDestroySetPlanner.Options plannerOptions,
            int maxCandidateColumns,
            long generationTimeLimitMs,
            long mipTimeLimitMs,
            long totalTimeLimitMs) {

        Options {
            Objects.requireNonNull(plannerOptions, "plannerOptions");
            if (maxCandidateColumns <= 0
                    || generationTimeLimitMs <= 0
                    || mipTimeLimitMs <= 0
                    || totalTimeLimitMs <= 0) {
                throw new IllegalArgumentException(
                        "invalid Target-Group feasibility options");
            }
        }

        static Options target24Defaults() {
            return new Options(
                    OrderGroupDestroySetPlanner.Options.target24Defaults(),
                    50_000,
                    30_000L,
                    30_000L,
                    30L * 60_000L);
        }
    }

    record Attempt(
            DestroySet destroySet,
            int keptGroups,
            int targetResidualGroups,
            int candidateCount,
            boolean candidateTruncated,
            int blockedFamilyCount,
            String candidateSignatureHash,
            ResidualTargetSolver.Result solverResult,
            long elapsedMs) {
    }

    record Result(
            ProofState state,
            List<GroupColumn> columns,
            int targetGroups,
            int neighborhoodsPlanned,
            int neighborhoodsAttempted,
            int neighborhoodsProvenInfeasible,
            int neighborhoodsInconclusive,
            boolean budgetExhausted,
            List<Attempt> attempts,
            long elapsedMs,
            String detail) {

        Result {
            columns = List.copyOf(columns);
            attempts = List.copyOf(attempts);
        }
    }

    static Result search(
            Input input,
            List<GroupColumn> incumbent,
            int targetGroups,
            Options options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(incumbent, "incumbent");
        Objects.requireNonNull(options, "options");
        long startedAt = System.currentTimeMillis();
        if (targetGroups < 0 || targetGroups >= incumbent.size()) {
            return inconclusiveResult(
                    targetGroups, 0, List.of(), false, startedAt,
                    "targetGroups must be smaller than incumbent size");
        }
        if (!exact(input, incumbent)) {
            return inconclusiveResult(
                    targetGroups, 0, List.of(), false, startedAt,
                    "incumbent failed exact semantic validation");
        }

        List<DestroySet> destroySets =
                OrderGroupDestroySetPlanner.plan(
                        incumbent, options.plannerOptions());
        if (destroySets.isEmpty()) {
            return inconclusiveResult(
                    targetGroups, 0, List.of(), false, startedAt,
                    "planner produced no destroy sets");
        }

        long overallDeadline = safeDeadline(
                startedAt, options.totalTimeLimitMs());
        List<Attempt> attempts = new ArrayList<>();
        int provenInfeasible = 0;
        int inconclusive = 0;
        boolean budgetExhausted = false;

        for (DestroySet destroySet : destroySets) {
            if (System.currentTimeMillis() >= overallDeadline) {
                budgetExhausted = true;
                break;
            }
            Attempt attempt = attempt(
                    input, incumbent, targetGroups,
                    destroySet, options, overallDeadline);
            attempts.add(attempt);
            if (attempt.solverResult().state()
                    == ProofState.PROVEN_INFEASIBLE) {
                provenInfeasible++;
            } else if (attempt.solverResult().state()
                    == ProofState.INCONCLUSIVE) {
                inconclusive++;
            } else {
                List<GroupColumn> kept = keptColumns(
                        incumbent, destroySet);
                List<GroupColumn> improved = new ArrayList<>(kept);
                improved.addAll(
                        attempt.solverResult().selectedColumns());
                improved.sort(Comparator.comparing(
                        GroupColumn::signature));
                if (improved.size() <= targetGroups
                        && exact(input, improved)) {
                    return new Result(
                            ProofState.FEASIBLE, improved, targetGroups,
                            destroySets.size(), attempts.size(),
                            provenInfeasible, inconclusive, false,
                            attempts, elapsed(startedAt),
                            "Exact target-group solution verified");
                }
                inconclusive++;
            }
        }

        if (!budgetExhausted
                && inconclusive == 0
                && attempts.size() == destroySets.size()) {
            return new Result(
                    ProofState.PROVEN_INFEASIBLE, incumbent, targetGroups,
                    destroySets.size(), attempts.size(),
                    provenInfeasible, 0, false, attempts,
                    elapsed(startedAt),
                    "All planned neighborhoods were completely enumerated "
                            + "and proved infeasible; this is not a global proof");
        }
        return new Result(
                ProofState.INCONCLUSIVE, incumbent, targetGroups,
                destroySets.size(), attempts.size(),
                provenInfeasible, inconclusive, budgetExhausted,
                attempts, elapsed(startedAt),
                budgetExhausted
                        ? "Total search budget exhausted"
                        : "At least one planned neighborhood was inconclusive");
    }

    static Attempt attempt(
            Input input,
            List<GroupColumn> incumbent,
            int targetGroups,
            DestroySet destroySet,
            Options options,
            long overallDeadline) {
        long startedAt = System.currentTimeMillis();
        List<GroupColumn> removed =
                removedColumns(incumbent, destroySet);
        List<GroupColumn> kept =
                keptColumns(incumbent, destroySet);
        Residual residual = residual(removed);
        int targetResidualGroups = targetGroups - kept.size();
        if (targetResidualGroups < 0) {
            ResidualTargetSolver.Result invalid =
                    new ResidualTargetSolver.Result(
                            ProofState.INCONCLUSIVE, List.of(),
                            com.google.ortools.linearsolver.MPSolver
                                    .ResultStatus.NOT_SOLVED,
                            "", InconclusiveReason.INVALID_INPUT,
                            false, 0, 0, 0, 0L,
                            elapsed(startedAt),
                            "Destroy set is too small for targetGroups");
            return new Attempt(
                    destroySet, kept.size(), targetResidualGroups,
                    0, false, kept.size(), "", invalid,
                    elapsed(startedAt));
        }

        Set<String> keptFamilies = new HashSet<>();
        kept.forEach(column ->
                keptFamilies.add(column.familySignature()));
        long generationDeadline = Math.min(
                overallDeadline,
                safeDeadline(
                        System.currentTimeMillis(),
                        options.generationTimeLimitMs()));
        OrderGroupResidualBundlePricer.CandidateSet candidateSet =
                OrderGroupResidualBundlePricer.enumerateResidualColumns(
                        input,
                        residual.demand(),
                        residual.cars(),
                        residual.waste(),
                        residual.odd(),
                        residual.one(),
                        keptFamilies,
                        removed,
                        options.maxCandidateColumns(),
                        generationDeadline);

        long remaining = Math.max(
                1L, overallDeadline - System.currentTimeMillis());
        long mipLimit = Math.min(
                options.mipTimeLimitMs(), remaining);
        ResidualTargetSolver.Result solverResult =
                ResidualTargetSolver.solve(
                        new ResidualTargetSolver.Request(
                                residual.demand(),
                                residual.cars(),
                                residual.waste(),
                                residual.odd(),
                                residual.one(),
                                candidateSet.columns(),
                                targetResidualGroups,
                                !candidateSet.truncated(),
                                mipLimit));
        return new Attempt(
                destroySet,
                kept.size(),
                targetResidualGroups,
                candidateSet.columns().size(),
                candidateSet.truncated(),
                candidateSet.blockedFamilies().size(),
                signatureHash(candidateSet.columns()),
                solverResult,
                elapsed(startedAt));
    }

    private static Residual residual(List<GroupColumn> columns) {
        Map<DemandKey, Integer> demand = new TreeMap<>();
        int cars = 0;
        int waste = 0;
        int odd = 0;
        int one = 0;
        for (GroupColumn column : columns) {
            column.coverage().forEach((key, value) ->
                    demand.merge(key, value, Math::addExact));
            cars = Math.addExact(cars, column.cars());
            waste = Math.addExact(waste, column.totalWaste());
            odd += column.odd() ? 1 : 0;
            one += column.oneCar() ? 1 : 0;
        }
        return new Residual(demand, cars, waste, odd, one);
    }

    private static List<GroupColumn> removedColumns(
            List<GroupColumn> incumbent,
            DestroySet destroySet) {
        Set<String> removed = new HashSet<>(
                destroySet.signatures());
        return incumbent.stream()
                .filter(column ->
                        removed.contains(column.signature()))
                .sorted(Comparator.comparing(GroupColumn::signature))
                .toList();
    }

    private static List<GroupColumn> keptColumns(
            List<GroupColumn> incumbent,
            DestroySet destroySet) {
        Set<String> removed = new HashSet<>(
                destroySet.signatures());
        return incumbent.stream()
                .filter(column ->
                        !removed.contains(column.signature()))
                .sorted(Comparator.comparing(GroupColumn::signature))
                .toList();
    }

    private static boolean exact(
            Input input,
            List<GroupColumn> columns) {
        return ResidualTargetSolver.conserves(
                columns, input.demand(), input.exactCars(),
                input.exactWaste(), input.exactOddGroups(),
                input.exactOneCarGroups());
    }

    private static Result inconclusiveResult(
            int targetGroups,
            int neighborhoodsPlanned,
            List<Attempt> attempts,
            boolean budgetExhausted,
            long startedAt,
            String detail) {
        return new Result(
                ProofState.INCONCLUSIVE, List.of(), targetGroups,
                neighborhoodsPlanned, attempts.size(), 0,
                attempts.size(), budgetExhausted, attempts,
                elapsed(startedAt), detail);
    }

    private static String signatureHash(
            List<GroupColumn> columns) {
        String canonical = columns.stream()
                .map(GroupColumn::signature)
                .sorted()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", impossible);
        }
    }

    private static long safeDeadline(long startedAt, long budgetMs) {
        try {
            return Math.addExact(startedAt, budgetMs);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long elapsed(long startedAt) {
        return Math.max(
                0L, System.currentTimeMillis() - startedAt);
    }

    private record Residual(
            Map<DemandKey, Integer> demand,
            int cars,
            int waste,
            int odd,
            int one) {

        Residual {
            demand = Map.copyOf(new TreeMap<>(demand));
        }
    }
}
