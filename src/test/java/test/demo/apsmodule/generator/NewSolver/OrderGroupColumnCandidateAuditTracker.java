package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ColumnPool;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Observes the real pricing pipeline for a fixed set of diagnostic columns.
 *
 * <p>The tracker never changes ordering, limits or returned candidates. It only
 * records the first stage at which a watched column disappears.</p>
 */
final class OrderGroupColumnCandidateAuditTracker {

    private final Map<String, MutableTarget> bySignature = new LinkedHashMap<>();
    private final Map<String, List<MutableTarget>> byPattern = new TreeMap<>();

    OrderGroupColumnCandidateAuditTracker(
            Collection<GroupColumn> targets, ColumnPool pool) {
        targets.stream()
                .sorted(java.util.Comparator.comparing(GroupColumn::signature))
                .forEach(target -> {
                    MutableTarget state = new MutableTarget(
                            target, pool.contains(target.signature()));
                    bySignature.put(target.signature(), state);
                    byPattern.computeIfAbsent(
                            target.pattern().signature(), ignored -> new ArrayList<>())
                            .add(state);
                });
    }

    List<GroupColumn> targetsFor(PatternCandidate pattern, int cars) {
        return byPattern.getOrDefault(pattern.signature(), List.of()).stream()
                .map(state -> state.target)
                .filter(target -> target.cars() == cars)
                .toList();
    }

    void observePattern(PatternCandidate pattern) {
        for (MutableTarget state :
                byPattern.getOrDefault(pattern.signature(), List.of())) {
            state.patternSeen = true;
        }
    }

    void observeCar(PatternCandidate pattern, int cars) {
        for (MutableTarget state :
                byPattern.getOrDefault(pattern.signature(), List.of())) {
            if (state.target.cars() == cars) {
                state.carSeen = true;
            }
        }
    }

    void observeLocal(
            GroupColumn target,
            int width,
            boolean existsInFullEnumeration,
            int stableRank,
            boolean retained,
            int limit) {
        MutableTarget state = state(target);
        if (!existsInFullEnumeration || !retained) {
            state.recordLocalFailure(
                    width, existsInFullEnumeration, stableRank, limit);
        }
    }

    void observeBeam(
            GroupColumn target,
            int width,
            int fullRank,
            boolean retained,
            int limit) {
        MutableTarget state = state(target);
        if (!retained) {
            state.recordBeamFailure(width, fullRank, limit);
        }
    }

    void observeMaterialized(GroupColumn column, double rawReducedCost) {
        MutableTarget state = bySignature.get(column.signature());
        if (state != null) {
            state.materialized = true;
            state.rawReducedCost = rawReducedCost;
        }
    }

    void observePatternRanking(
            PatternCandidate pattern,
            List<String> orderedNegativeSignatures,
            int retainedLimit) {
        for (MutableTarget state :
                byPattern.getOrDefault(pattern.signature(), List.of())) {
            int index = orderedNegativeSignatures.indexOf(state.target.signature());
            if (index >= 0) {
                state.patternRank = index + 1;
                state.patternLimit = retainedLimit;
                state.patternRetained = index < retainedLimit;
            }
        }
    }

    void observeGlobalRetained(Collection<GroupColumn> retained) {
        for (GroupColumn column : retained) {
            MutableTarget state = bySignature.get(column.signature());
            if (state != null) {
                state.globalCollectorRetained = true;
            }
        }
    }

    void observeReturned(Collection<GroupColumn> returned) {
        for (GroupColumn column : returned) {
            MutableTarget state = bySignature.get(column.signature());
            if (state != null) {
                state.returned = true;
            }
        }
    }

    Replay freeze(
            boolean deadlineReached,
            double reducedCostEpsilon,
            OrderGroupColumnPricingOracle.Diagnostics diagnostics) {
        Map<String, CandidateExplanation> explanations = new LinkedHashMap<>();
        for (MutableTarget state : bySignature.values()) {
            CandidateExplanation explanation =
                    state.explain(deadlineReached, reducedCostEpsilon);
            explanations.put(state.target.signature(), explanation);
        }
        return new Replay(
                Collections.unmodifiableMap(explanations),
                diagnostics,
                deadlineReached);
    }

    private MutableTarget state(GroupColumn target) {
        MutableTarget state = bySignature.get(target.signature());
        if (state == null) {
            throw new IllegalArgumentException(
                    "unwatched target " + target.signature());
        }
        return state;
    }

    enum LossStage {
        PRESENT_IN_AUTONOMOUS_POOL,
        PATTERN_NOT_IN_UNIVERSE,
        CAR_COUNT_PRUNED,
        LOCAL_CONFIGURATION_PRUNED,
        BEAM_PRUNED,
        MATERIALIZED_NONNEGATIVE,
        NEGATIVE_PATTERN_CAP_PRUNED,
        NEGATIVE_GLOBAL_SELECTION_PRUNED,
        GENERATED_IN_DIAGNOSTIC_REPLAY,
        AUDIT_TIME_LIMIT,
        UNCLASSIFIED_COLUMN
    }

    record CandidateExplanation(
            String signature,
            LossStage stage,
            double rawReducedCost,
            int rank,
            int limit,
            String locus,
            String detail) {

        CandidateExplanation {
            Objects.requireNonNull(signature);
            Objects.requireNonNull(stage);
            locus = Objects.requireNonNullElse(locus, "");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    record Replay(
            Map<String, CandidateExplanation> explanations,
            OrderGroupColumnPricingOracle.Diagnostics diagnostics,
            boolean deadlineReached) {

        Replay {
            explanations = Collections.unmodifiableMap(
                    new LinkedHashMap<>(explanations));
            Objects.requireNonNull(diagnostics);
        }
    }

    private static final class MutableTarget {
        private final GroupColumn target;
        private final boolean present;
        private boolean patternSeen;
        private boolean carSeen;
        private LocalFailure localFailure;
        private BeamFailure beamFailure;
        private boolean materialized;
        private double rawReducedCost = Double.NaN;
        private int patternRank;
        private int patternLimit;
        private boolean patternRetained;
        private boolean globalCollectorRetained;
        private boolean returned;

        private MutableTarget(GroupColumn target, boolean present) {
            this.target = Objects.requireNonNull(target);
            this.present = present;
        }

        private void recordLocalFailure(
                int width, boolean exists, int rank, int limit) {
            if (localFailure == null) {
                localFailure = new LocalFailure(width, exists, rank, limit);
            }
        }

        private void recordBeamFailure(int width, int rank, int limit) {
            if (beamFailure == null) {
                beamFailure = new BeamFailure(width, rank, limit);
            }
        }

        private CandidateExplanation explain(
                boolean deadlineReached, double epsilon) {
            if (present) {
                return explanation(
                        LossStage.PRESENT_IN_AUTONOMOUS_POOL,
                        0,
                        0,
                        "pool",
                        "exact signature already exists");
            }
            if (!patternSeen) {
                return explanation(
                        deadlineReached
                                ? LossStage.AUDIT_TIME_LIMIT
                                : LossStage.PATTERN_NOT_IN_UNIVERSE,
                        0,
                        0,
                        "pattern",
                        deadlineReached
                                ? "pricing replay ended before pattern was observed"
                                : "pattern signature is absent from the audited universe");
            }
            if (!carSeen) {
                return explanation(
                        LossStage.CAR_COUNT_PRUNED,
                        0,
                        0,
                        "cars=" + target.cars(),
                        "target car count was not processed");
            }
            if (localFailure != null) {
                return explanation(
                        LossStage.LOCAL_CONFIGURATION_PRUNED,
                        localFailure.rank,
                        localFailure.limit,
                        "width=" + localFailure.width,
                        localFailure.exists
                                ? "configuration exists but was not retained"
                                : "configuration was not produced by full local enumeration");
            }
            if (beamFailure != null) {
                return explanation(
                        LossStage.BEAM_PRUNED,
                        beamFailure.rank,
                        beamFailure.limit,
                        "width=" + beamFailure.width,
                        "target prefix did not survive the beam");
            }
            if (!materialized) {
                return explanation(
                        LossStage.UNCLASSIFIED_COLUMN,
                        0,
                        0,
                        "materialization",
                        "all prior stages passed but the exact column was not materialized");
            }
            if (rawReducedCost >= -epsilon) {
                return explanation(
                        LossStage.MATERIALIZED_NONNEGATIVE,
                        0,
                        0,
                        "reduced-cost",
                        "raw reduced cost failed the single-column admission gate");
            }
            if (!patternRetained) {
                return explanation(
                        LossStage.NEGATIVE_PATTERN_CAP_PRUNED,
                        patternRank,
                        patternLimit,
                        "pattern-cap",
                        "negative column ranked below the per-pattern limit");
            }
            if (!returned) {
                String layer = globalCollectorRetained
                        ? "role/global-selection"
                        : "bounded-role-collector";
                return explanation(
                        LossStage.NEGATIVE_GLOBAL_SELECTION_PRUNED,
                        patternRank,
                        patternLimit,
                        layer,
                        "negative column passed the pattern cap but was not returned");
            }
            return explanation(
                    LossStage.GENERATED_IN_DIAGNOSTIC_REPLAY,
                    patternRank,
                    patternLimit,
                    "diagnostic-replay",
                    "same oracle settings returned the column");
        }

        private CandidateExplanation explanation(
                LossStage stage,
                int rank,
                int limit,
                String locus,
                String detail) {
            return new CandidateExplanation(
                    target.signature(),
                    stage,
                    rawReducedCost,
                    rank,
                    limit,
                    locus,
                    detail);
        }
    }

    private record LocalFailure(int width, boolean exists, int rank, int limit) {
    }

    private record BeamFailure(int width, int rank, int limit) {
    }
}
