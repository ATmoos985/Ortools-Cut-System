package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPSolver;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateAuditTracker.CandidateExplanation;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateAuditTracker.LossStage;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ColumnPool;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Phase;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Result;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Explains why known feasible group columns are absent from an autonomous pool.
 */
final class OrderGroupMissingColumnAudit {

    private OrderGroupMissingColumnAudit() {
    }

    static AuditResult audit(
            Input input,
            Options options,
            Result autonomous,
            Result witness,
            long auditTimeLimitMs) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(autonomous, "autonomous");
        Objects.requireNonNull(witness, "witness");
        if (auditTimeLimitMs <= 0) {
            throw new IllegalArgumentException("auditTimeLimitMs must be positive");
        }

        long startedAt = System.currentTimeMillis();
        if (!isExact(input, witness.selectedColumns())) {
            return AuditResult.failed(
                    AuditStatus.WITNESS_INVALID,
                    "witness columns do not satisfy the exact resource rows",
                    System.currentTimeMillis() - startedAt);
        }
        if (!isExact(input, autonomous.selectedColumns())) {
            return AuditResult.failed(
                    AuditStatus.AUTONOMOUS_POOL_INVALID,
                    "autonomous selected columns do not satisfy the exact resource rows",
                    System.currentTimeMillis() - startedAt);
        }

        long lpStartedAt = System.currentTimeMillis();
        OrderGroupRestrictedMaster.LpResult optimizationLp =
                OrderGroupRestrictedMaster.solveLp(
                        input, options, autonomous.pool(), Phase.OPTIMIZATION);
        long lpMs = System.currentTimeMillis() - lpStartedAt;
        if (optimizationLp.status() != MPSolver.ResultStatus.OPTIMAL) {
            return AuditResult.failed(
                    AuditStatus.AUTONOMOUS_POOL_INVALID,
                    "autonomous pool optimization LP status="
                            + optimizationLp.status(),
                    System.currentTimeMillis() - startedAt);
        }

        ColumnPool auditPool = new ColumnPool(Math.max(
                1, autonomous.pool().size() + witness.selectedColumns().size()));
        auditPool.addAll(autonomous.pool());
        OrderGroupColumnPricingOracle oracle =
                new OrderGroupColumnPricingOracle(input, options);
        long replayStartedAt = System.currentTimeMillis();
        OrderGroupColumnCandidateAuditTracker.Replay replay = oracle.auditTargets(
                witness.selectedColumns(),
                optimizationLp.dual(),
                auditPool,
                deadline(replayStartedAt, auditTimeLimitMs));
        long replayMs = System.currentTimeMillis() - replayStartedAt;

        List<CandidateExplanation> explanations =
                witness.selectedColumns().stream()
                        .map(GroupColumn::signature)
                        .sorted()
                        .map(replay.explanations()::get)
                        .filter(Objects::nonNull)
                        .toList();
        Map<LossStage, Integer> counts = new EnumMap<>(LossStage.class);
        explanations.forEach(explanation ->
                counts.merge(explanation.stage(), 1, Math::addExact));

        ExchangeAudit exchange = auditExchange(
                autonomous.selectedColumns(),
                witness.selectedColumns(),
                optimizationLp.dual());
        AuditStatus status = determineStatus(
                witness.selectedColumns().size(),
                explanations,
                replay.deadlineReached(),
                exchange);
        Recommendation recommendation =
                recommend(status, counts, exchange);
        return new AuditResult(
                status,
                recommendation,
                List.copyOf(explanations),
                Collections.unmodifiableMap(new EnumMap<>(counts)),
                exchange,
                optimizationLp.objectiveValue(),
                lpMs,
                replayMs,
                System.currentTimeMillis() - startedAt,
                replay.diagnostics(),
                status == AuditStatus.COMPLETE
                        ? ""
                        : failureDetail(
                                witness.selectedColumns().size(),
                                explanations,
                                replay.deadlineReached(),
                                exchange));
    }

    static ExchangeAudit auditExchange(
            List<GroupColumn> autonomous,
            List<GroupColumn> witness,
            OrderGroupColumnPricingPrototype.DualVector dual) {
        Map<String, GroupColumn> autonomousBySignature = bySignature(autonomous);
        Map<String, GroupColumn> witnessBySignature = bySignature(witness);
        List<GroupColumn> kept = autonomousBySignature.entrySet().stream()
                .filter(entry -> witnessBySignature.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted(java.util.Comparator.comparing(GroupColumn::signature))
                .toList();
        List<GroupColumn> removed = autonomousBySignature.entrySet().stream()
                .filter(entry -> !witnessBySignature.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted(java.util.Comparator.comparing(GroupColumn::signature))
                .toList();
        List<GroupColumn> added = witnessBySignature.entrySet().stream()
                .filter(entry -> !autonomousBySignature.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted(java.util.Comparator.comparing(GroupColumn::signature))
                .toList();

        ResourceVector removedResources = ResourceVector.from(removed);
        ResourceVector addedResources = ResourceVector.from(added);
        boolean conserved = removedResources.equals(addedResources);
        double removedObjective = removed.stream()
                .mapToDouble(OrderGroupRestrictedMaster::optimizationCost)
                .sum();
        double addedObjective = added.stream()
                .mapToDouble(OrderGroupRestrictedMaster::optimizationCost)
                .sum();
        double removedRawReducedCost = removed.stream()
                .mapToDouble(column ->
                        OrderGroupColumnPricingPrototype.rawReducedCost(
                                column, dual, Phase.OPTIMIZATION))
                .sum();
        double addedRawReducedCost = added.stream()
                .mapToDouble(column ->
                        OrderGroupColumnPricingPrototype.rawReducedCost(
                                column, dual, Phase.OPTIMIZATION))
                .sum();
        long nonnegativeAdded = added.stream()
                .filter(column ->
                        OrderGroupColumnPricingPrototype.rawReducedCost(
                                column, dual, Phase.OPTIMIZATION) >= 0.0)
                .count();
        return new ExchangeAudit(
                kept.stream().map(GroupColumn::signature).toList(),
                removed.stream().map(GroupColumn::signature).toList(),
                added.stream().map(GroupColumn::signature).toList(),
                removedResources,
                addedResources,
                conserved,
                added.size() - removed.size(),
                addedObjective - removedObjective,
                addedRawReducedCost,
                removedRawReducedCost,
                Math.toIntExact(nonnegativeAdded));
    }

    private static AuditStatus determineStatus(
            int targetCount,
            List<CandidateExplanation> explanations,
            boolean deadlineReached,
            ExchangeAudit exchange) {
        if (deadlineReached
                || explanations.stream().anyMatch(explanation ->
                        explanation.stage() == LossStage.AUDIT_TIME_LIMIT)) {
            return AuditStatus.AUDIT_TIME_LIMIT;
        }
        if (explanations.size() != targetCount
                || explanations.stream().anyMatch(explanation ->
                        explanation.stage() == LossStage.UNCLASSIFIED_COLUMN)) {
            return AuditStatus.UNCLASSIFIED_COLUMN;
        }
        if (!exchange.conserved()) {
            return AuditStatus.EXCHANGE_NOT_CONSERVED;
        }
        return AuditStatus.COMPLETE;
    }

    private static Recommendation recommend(
            AuditStatus status,
            Map<LossStage, Integer> counts,
            ExchangeAudit exchange) {
        if (status != AuditStatus.COMPLETE) {
            return Recommendation.INCONCLUSIVE;
        }
        int generationLosses = count(
                counts,
                LossStage.PATTERN_NOT_IN_UNIVERSE,
                LossStage.CAR_COUNT_PRUNED,
                LossStage.LOCAL_CONFIGURATION_PRUNED,
                LossStage.BEAM_PRUNED);
        int selectionLosses = count(
                counts,
                LossStage.NEGATIVE_PATTERN_CAP_PRUNED,
                LossStage.NEGATIVE_GLOBAL_SELECTION_PRUNED);
        int nonnegative = counts.getOrDefault(
                LossStage.MATERIALIZED_NONNEGATIVE, 0);
        int activeKinds = (generationLosses > 0 ? 1 : 0)
                + (selectionLosses > 0 ? 1 : 0)
                + (nonnegative > 0 ? 1 : 0);
        if (activeKinds > 1) {
            return Recommendation.MIXED_EARLIEST_STAGE_FIRST;
        }
        if (generationLosses > 0) {
            return Recommendation.FIX_CANDIDATE_GENERATION;
        }
        if (selectionLosses > 0) {
            return Recommendation.FIX_NEGATIVE_SELECTION;
        }
        if (nonnegative > 0
                && exchange.conserved()
                && exchange.groupDelta() < 0) {
            return Recommendation.DESIGN_BUNDLE_PRICING;
        }
        return Recommendation.REVIEW_INTEGER_SELECTION;
    }

    private static int count(
            Map<LossStage, Integer> counts, LossStage... stages) {
        int total = 0;
        for (LossStage stage : stages) {
            total = Math.addExact(total, counts.getOrDefault(stage, 0));
        }
        return total;
    }

    private static String failureDetail(
            int targetCount,
            List<CandidateExplanation> explanations,
            boolean deadlineReached,
            ExchangeAudit exchange) {
        return "targets=" + targetCount
                + ", classified=" + explanations.size()
                + ", deadline=" + deadlineReached
                + ", conserved=" + exchange.conserved();
    }

    private static boolean isExact(Input input, List<GroupColumn> columns) {
        ResourceVector actual = ResourceVector.from(columns);
        ResourceVector expected = new ResourceVector(
                input.demand(),
                input.exactCars(),
                input.exactWaste(),
                input.exactOddGroups(),
                input.exactOneCarGroups());
        return actual.equals(expected);
    }

    private static Map<String, GroupColumn> bySignature(
            List<GroupColumn> columns) {
        Map<String, GroupColumn> result = new LinkedHashMap<>();
        columns.stream()
                .sorted(java.util.Comparator.comparing(GroupColumn::signature))
                .forEach(column -> {
                    GroupColumn previous =
                            result.putIfAbsent(column.signature(), column);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                                "duplicate group-column signature "
                                        + column.signature());
                    }
                });
        return result;
    }

    private static long deadline(long startedAt, long durationMs) {
        if (durationMs >= Long.MAX_VALUE - startedAt) {
            return Long.MAX_VALUE;
        }
        return startedAt + durationMs;
    }

    enum AuditStatus {
        COMPLETE,
        WITNESS_INVALID,
        AUTONOMOUS_POOL_INVALID,
        AUDIT_TIME_LIMIT,
        UNCLASSIFIED_COLUMN,
        EXCHANGE_NOT_CONSERVED
    }

    enum Recommendation {
        FIX_CANDIDATE_GENERATION,
        FIX_NEGATIVE_SELECTION,
        DESIGN_BUNDLE_PRICING,
        MIXED_EARLIEST_STAGE_FIRST,
        REVIEW_INTEGER_SELECTION,
        INCONCLUSIVE
    }

    record ResourceVector(
            Map<DemandKey, Integer> demand,
            int cars,
            int waste,
            int oddGroups,
            int oneCarGroups) {

        ResourceVector {
            demand = Collections.unmodifiableMap(new TreeMap<>(demand));
        }

        static ResourceVector from(List<GroupColumn> columns) {
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
                odd = Math.addExact(odd, column.odd() ? 1 : 0);
                one = Math.addExact(one, column.oneCar() ? 1 : 0);
            }
            return new ResourceVector(demand, cars, waste, odd, one);
        }
    }

    record ExchangeAudit(
            List<String> kept,
            List<String> removed,
            List<String> added,
            ResourceVector removedResources,
            ResourceVector addedResources,
            boolean conserved,
            int groupDelta,
            double objectiveDelta,
            double addedRawReducedCost,
            double removedRawReducedCost,
            int nonnegativeAddedColumns) {

        ExchangeAudit {
            kept = List.copyOf(kept);
            removed = List.copyOf(removed);
            added = List.copyOf(added);
            Objects.requireNonNull(removedResources);
            Objects.requireNonNull(addedResources);
        }

        static ExchangeAudit empty() {
            ResourceVector empty = ResourceVector.from(List.of());
            return new ExchangeAudit(
                    List.of(),
                    List.of(),
                    List.of(),
                    empty,
                    empty,
                    true,
                    0,
                    0.0,
                    0.0,
                    0.0,
                    0);
        }
    }

    record AuditResult(
            AuditStatus status,
            Recommendation recommendation,
            List<CandidateExplanation> columns,
            Map<LossStage, Integer> counts,
            ExchangeAudit exchange,
            double optimizationLpObjective,
            long lpMs,
            long replayMs,
            long totalMs,
            OrderGroupColumnPricingOracle.Diagnostics replayDiagnostics,
            String detail) {

        AuditResult {
            Objects.requireNonNull(status);
            Objects.requireNonNull(recommendation);
            columns = List.copyOf(columns);
            EnumMap<LossStage, Integer> normalizedCounts =
                    new EnumMap<>(LossStage.class);
            normalizedCounts.putAll(counts);
            counts = Collections.unmodifiableMap(normalizedCounts);
            Objects.requireNonNull(exchange);
            Objects.requireNonNull(replayDiagnostics);
            detail = Objects.requireNonNullElse(detail, "");
        }

        static AuditResult failed(
                AuditStatus status, String detail, long totalMs) {
            return new AuditResult(
                    status,
                    Recommendation.INCONCLUSIVE,
                    List.of(),
                    Map.of(),
                    ExchangeAudit.empty(),
                    Double.NaN,
                    0L,
                    0L,
                    totalMs,
                    OrderGroupColumnPricingOracle.Diagnostics.empty(),
                    detail);
        }
    }

}
