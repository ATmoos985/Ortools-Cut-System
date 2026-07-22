package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPSolver;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure research-side selection logic for a frozen sparse-move candidate pool.
 *
 * <p>The class never generates candidates and never invokes a solver. It keeps
 * state identity, signed support identity and evaluation strategy separate so
 * the real-data experiment can compare selectors over exactly the same pool.</p>
 */
final class SupportBucketedCandidateEvaluator {

    private static final Comparator<FrozenCandidate> STATIC_STATE_ORDER =
            Comparator.comparingInt((FrozenCandidate state) ->
                            state.candidate().delta().size())
                    .thenComparing(FrozenCandidate::stateSignature);

    private static final Comparator<ScoredCandidate> SCORE_ORDER =
            Comparator.comparingInt(SupportBucketedCandidateEvaluator::scoreRank)
                    .thenComparingInt(value -> reportableGroups(value.score()))
                    .thenComparing(value -> value.state().stateSignature());

    private SupportBucketedCandidateEvaluator() {
    }

    static Comparator<ScoredCandidate> scoreOrder() {
        return SCORE_ORDER;
    }

    static Snapshot freeze(
            Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                    SparseProductionNeutralMoveEnumerator.Result> sparseRuns,
            int historicalStateAnchor) {
        Objects.requireNonNull(sparseRuns, "sparseRuns");
        if (sparseRuns.isEmpty()) {
            throw new IllegalArgumentException("sparseRuns must not be empty");
        }
        if (historicalStateAnchor <= 0) {
            throw new IllegalArgumentException(
                    "historicalStateAnchor must be positive");
        }

        Map<String, MutableFrozenCandidate> mutableByState =
                new LinkedHashMap<>();
        Map<StructuredLocalPatternUniverseBuilder.UniverseScope, LayerCoverage>
                layerCoverage = new EnumMap<>(
                StructuredLocalPatternUniverseBuilder.UniverseScope.class);
        Map<Double, Integer> objectiveFrequency = new TreeMap<>();
        int rawOccurrences = 0;
        int unmappedObjectiveOccurrences = 0;

        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            SparseProductionNeutralMoveEnumerator.Result result =
                    sparseRuns.get(scope);
            if (result == null) {
                continue;
            }
            SparseProductionNeutralMoveEnumerator.Metrics metrics =
                    result.metrics();
            metrics.masterIterations().stream()
                    .map(SparseProductionNeutralMoveEnumerator.MasterIteration
                            ::objectiveValue)
                    .filter(Double::isFinite)
                    .map(SupportBucketedCandidateEvaluator
                            ::canonicalObjective)
                    .forEach(value -> objectiveFrequency.merge(
                            value, 1, Integer::sum));
            Map<String, Double> objectiveByLocalSupport =
                    objectiveByLocalSupport(metrics);

            Set<String> layerStates = new TreeSet<>();
            Set<String> layerSupports = new TreeSet<>();
            for (SparseProductionNeutralMoveEnumerator.Candidate candidate
                    : result.candidates()) {
                rawOccurrences++;
                String stateSignature = candidate.stateSignature();
                String supportIdentity = supportIdentity(candidate);
                Double objective = objectiveByLocalSupport.get(
                        candidate.supportSignature());
                if (objective == null) {
                    unmappedObjectiveOccurrences++;
                }
                SourceOccurrence occurrence = new SourceOccurrence(
                        scope,
                        candidate.supportSignature(),
                        objective == null
                                ? OptionalDouble.empty()
                                : OptionalDouble.of(objective));
                MutableFrozenCandidate mutable = mutableByState.computeIfAbsent(
                        stateSignature,
                        ignored -> new MutableFrozenCandidate(
                                candidate, supportIdentity));
                mutable.add(candidate, supportIdentity, occurrence);
                layerStates.add(stateSignature);
                layerSupports.add(supportIdentity);
            }
            layerCoverage.put(scope, new LayerCoverage(
                    layerStates.size(), layerSupports.size()));
        }

        List<FrozenCandidate> staticOrder = mutableByState.values().stream()
                .map(MutableFrozenCandidate::freeze)
                .sorted(STATIC_STATE_ORDER)
                .toList();
        if (staticOrder.isEmpty()) {
            throw new IllegalArgumentException(
                    "sparseRuns did not contain any candidates");
        }

        Map<String, List<FrozenCandidate>> statesBySupport =
                new TreeMap<>();
        staticOrder.forEach(state -> statesBySupport
                .computeIfAbsent(state.supportIdentity(), ignored ->
                        new ArrayList<>())
                .add(state));

        Map<String, FrozenCandidate> firstBySupport = new LinkedHashMap<>();
        staticOrder.forEach(state -> firstBySupport.putIfAbsent(
                state.supportIdentity(), state));
        List<FrozenCandidate> historicalOrder = new ArrayList<>(
                firstBySupport.values());
        Set<String> selectedStates = historicalOrder.stream()
                .map(FrozenCandidate::stateSignature)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        staticOrder.stream()
                .filter(state -> selectedStates.add(state.stateSignature()))
                .forEach(historicalOrder::add);

        Map<String, List<FrozenCandidate>> immutableBuckets =
                new TreeMap<>();
        statesBySupport.forEach((support, states) -> immutableBuckets.put(
                support, List.copyOf(states)));
        SnapshotStatus status = staticOrder.size() == historicalStateAnchor
                ? SnapshotStatus.MATCHES_HISTORICAL_ANCHOR
                : SnapshotStatus.CANDIDATE_SNAPSHOT_DRIFT;
        return new Snapshot(
                List.copyOf(staticOrder),
                List.copyOf(historicalOrder),
                immutableBuckets,
                layerCoverage,
                objectiveFrequency,
                rawOccurrences,
                rawOccurrences - staticOrder.size(),
                unmappedObjectiveOccurrences,
                historicalStateAnchor,
                status);
    }

    static Evaluation evaluate(
            Snapshot snapshot,
            Map<String, ProductionNeutralMoveSearch.FastUpperBound>
                    phase2ByState,
            int historicalPhase2Limit,
            int exactCandidateLimit) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(phase2ByState, "phase2ByState");
        if (historicalPhase2Limit <= 0 || exactCandidateLimit <= 0) {
            throw new IllegalArgumentException(
                    "evaluation limits must be positive");
        }
        Set<String> expectedStates = snapshot.staticOrder().stream()
                .map(FrozenCandidate::stateSignature)
                .collect(TreeSet::new, Set::add, Set::addAll);
        Set<String> scoredStates = new TreeSet<>(phase2ByState.keySet());
        if (!expectedStates.equals(scoredStates)) {
            Set<String> missing = new TreeSet<>(expectedStates);
            missing.removeAll(scoredStates);
            Set<String> extra = new TreeSet<>(scoredStates);
            extra.removeAll(expectedStates);
            throw new IllegalArgumentException(
                    "Phase2 score set does not match snapshot: missing="
                            + missing + ", extra=" + extra);
        }

        Map<String, ScoredCandidate> scoredByState = new LinkedHashMap<>();
        for (FrozenCandidate state : snapshot.staticOrder()) {
            scoredByState.put(state.stateSignature(), new ScoredCandidate(
                    state,
                    Objects.requireNonNull(
                            phase2ByState.get(state.stateSignature()),
                            "Phase2 score")));
        }

        List<ScoredCandidate> globalOrder = scoredByState.values().stream()
                .sorted(SCORE_ORDER)
                .toList();
        int historicalSize = Math.min(
                historicalPhase2Limit, snapshot.historicalOrder().size());
        List<ScoredCandidate> historicalPrefix = snapshot.historicalOrder()
                .subList(0, historicalSize).stream()
                .map(state -> scoredByState.get(state.stateSignature()))
                .toList();
        Set<String> historicalStates = historicalPrefix.stream()
                .map(value -> value.state().stateSignature())
                .collect(TreeSet::new, Set::add, Set::addAll);

        Map<String, SupportBucket> buckets = new TreeMap<>();
        for (Map.Entry<String, List<FrozenCandidate>> entry
                : snapshot.statesBySupport().entrySet()) {
            List<ScoredCandidate> states = entry.getValue().stream()
                    .map(state -> scoredByState.get(state.stateSignature()))
                    .toList();
            ScoredCandidate staticFirst = states.get(0);
            ScoredCandidate representative = states.stream()
                    .min(SCORE_ORDER)
                    .orElseThrow();
            buckets.put(entry.getKey(), new SupportBucket(
                    entry.getKey(),
                    states,
                    staticFirst,
                    representative,
                    historicalStates.contains(
                            representative.state().stateSignature())));
        }

        List<ScoredCandidate> supportRepresentatives = buckets.values().stream()
                .map(SupportBucket::representative)
                .sorted(SCORE_ORDER)
                .toList();
        int effectiveExactCandidateLimit = Math.min(
                exactCandidateLimit, supportRepresentatives.size());
        SelectionPlan globalStatePlan = selectionPlan(
                Strategy.GLOBAL_STATE,
                globalOrder,
                effectiveExactCandidateLimit,
                snapshot.supportDenominator());
        SelectionPlan supportBucketPlan = selectionPlan(
                Strategy.SUPPORT_BUCKET,
                supportRepresentatives,
                effectiveExactCandidateLimit,
                snapshot.supportDenominator());

        return new Evaluation(
                snapshot,
                globalOrder,
                historicalPrefix,
                buckets,
                globalStatePlan,
                supportBucketPlan,
                phase2Summary(globalOrder),
                bucketSummary(buckets),
                historicalSummary(
                        historicalPrefix,
                        buckets,
                        snapshot.supportDenominator()),
                objectiveDiagnostics(snapshot, globalOrder));
    }

    static String supportIdentity(
            SparseProductionNeutralMoveEnumerator.Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        List<String> removals = new ArrayList<>();
        List<String> additions = new ArrayList<>();
        candidate.delta().entrySet().stream()
                .filter(entry -> entry.getValue() != 0)
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(PatternCandidate::signature)))
                .forEach(entry -> {
                    String signature = entry.getKey().signature();
                    if (entry.getValue() < 0) {
                        removals.add("R:" + signature);
                    } else {
                        additions.add("A:" + signature);
                    }
                });
        if (removals.isEmpty() || additions.isEmpty()) {
            throw new IllegalArgumentException(
                    "candidate delta must contain removals and additions");
        }
        return String.join("|", removals) + "|"
                + String.join("|", additions);
    }

    private static Map<String, Double> objectiveByLocalSupport(
            SparseProductionNeutralMoveEnumerator.Metrics metrics) {
        Map<String, Double> objectiveBySupport = new LinkedHashMap<>();
        List<SparseProductionNeutralMoveEnumerator.SupportRun> supportRuns =
                metrics.supportRuns();
        int supportIndex = Math.min(
                metrics.seededSupportsVisited(), supportRuns.size());
        for (SparseProductionNeutralMoveEnumerator.MasterIteration iteration
                : metrics.masterIterations()) {
            if (!feasible(iteration.status())) {
                continue;
            }
            if (supportIndex >= supportRuns.size()) {
                break;
            }
            SparseProductionNeutralMoveEnumerator.SupportRun supportRun =
                    supportRuns.get(supportIndex++);
            if (Double.isFinite(iteration.objectiveValue())) {
                double objective = canonicalObjective(
                        iteration.objectiveValue());
                Double previous = objectiveBySupport.putIfAbsent(
                        supportRun.supportSignature(),
                        objective);
                if (previous != null
                        && Double.compare(previous, objective) != 0) {
                    throw new IllegalStateException(
                            "local support has conflicting master objectives: "
                                    + supportRun.supportSignature());
                }
            }
        }
        return objectiveBySupport;
    }

    private static double canonicalObjective(double value) {
        double nearestInteger = Math.rint(value);
        if (Math.abs(value - nearestInteger) <= 1e-6) {
            return nearestInteger == 0.0 ? 0.0 : nearestInteger;
        }
        return value;
    }

    private static boolean feasible(MPSolver.ResultStatus status) {
        return status == MPSolver.ResultStatus.OPTIMAL
                || status == MPSolver.ResultStatus.FEASIBLE;
    }

    private static SelectionPlan selectionPlan(
            Strategy strategy,
            List<ScoredCandidate> order,
            int limit,
            int supportDenominator) {
        List<ScoredCandidate> finalists = order.stream()
                .limit(limit)
                .toList();
        int distinctSupports = (int) finalists.stream()
                .map(value -> value.state().supportIdentity())
                .distinct()
                .count();
        return new SelectionPlan(
                strategy,
                finalists,
                distinctSupports,
                supportDenominator);
    }

    private static Phase2Summary phase2Summary(
            List<ScoredCandidate> allScored) {
        Map<ProductionNeutralMoveSearch.FastStatus, Integer> statusCounts =
                new EnumMap<>(ProductionNeutralMoveSearch.FastStatus.class);
        allScored.forEach(value -> statusCounts.merge(
                value.score().status(), 1, Integer::sum));
        int targetShapeStates = (int) allScored.stream()
                .filter(value -> value.score().targetShapeFeasible())
                .count();
        int targetShapeSupports = (int) allScored.stream()
                .filter(value -> value.score().targetShapeFeasible())
                .map(value -> value.state().supportIdentity())
                .distinct()
                .count();
        int bestTargetGroups = allScored.stream()
                .filter(value -> value.score().targetShapeFeasible())
                .mapToInt(value -> value.score().groups())
                .min()
                .orElse(-1);
        int bestFeasibleGroups = allScored.stream()
                .filter(value -> value.score().status()
                        == ProductionNeutralMoveSearch.FastStatus.FEASIBLE)
                .mapToInt(value -> value.score().groups())
                .filter(value -> value >= 0)
                .min()
                .orElse(-1);
        List<Long> elapsed = allScored.stream()
                .map(value -> value.score().elapsedMs())
                .sorted()
                .toList();
        long totalElapsed = elapsed.stream().mapToLong(Long::longValue).sum();
        long maxElapsed = elapsed.stream().mapToLong(Long::longValue)
                .max().orElse(0L);
        return new Phase2Summary(
                statusCounts,
                targetShapeStates,
                targetShapeSupports,
                bestTargetGroups,
                bestFeasibleGroups,
                totalElapsed,
                elapsed.isEmpty() ? 0.0
                        : (double) totalElapsed / elapsed.size(),
                medianLong(elapsed),
                maxElapsed);
    }

    private static BucketSummary bucketSummary(
            Map<String, SupportBucket> buckets) {
        int multiState = 0;
        int representativeChanged = 0;
        int targetShapeImproved = 0;
        int historicalMissed = 0;
        int maxStates = 0;
        int maxGroupImprovement = 0;
        Map<Integer, Integer> stateCountFrequency = new TreeMap<>();
        for (SupportBucket bucket : buckets.values()) {
            int size = bucket.states().size();
            stateCountFrequency.merge(size, 1, Integer::sum);
            maxStates = Math.max(maxStates, size);
            if (size > 1) {
                multiState++;
            }
            if (!bucket.staticFirst().state().stateSignature().equals(
                    bucket.representative().state().stateSignature())) {
                representativeChanged++;
            }
            if (!bucket.staticFirst().score().targetShapeFeasible()
                    && bucket.representative().score().targetShapeFeasible()) {
                targetShapeImproved++;
            }
            if (!bucket.representativeInHistoricalPrefix()) {
                historicalMissed++;
            }
            if (feasibleScore(bucket.staticFirst().score())
                    && feasibleScore(bucket.representative().score())) {
                maxGroupImprovement = Math.max(
                        maxGroupImprovement,
                        bucket.staticFirst().score().groups()
                                - bucket.representative().score().groups());
            }
        }
        return new BucketSummary(
                buckets.size(),
                multiState,
                representativeChanged,
                targetShapeImproved,
                historicalMissed,
                maxStates,
                maxGroupImprovement,
                stateCountFrequency);
    }

    private static HistoricalSummary historicalSummary(
            List<ScoredCandidate> historicalPrefix,
            Map<String, SupportBucket> buckets,
            int supportDenominator) {
        Set<String> historicalStates = historicalPrefix.stream()
                .map(value -> value.state().stateSignature())
                .collect(TreeSet::new, Set::add, Set::addAll);
        int distinctSupports = (int) historicalPrefix.stream()
                .map(value -> value.state().supportIdentity())
                .distinct()
                .count();
        int missedRepresentatives = (int) buckets.values().stream()
                .filter(bucket -> !historicalStates.contains(
                        bucket.representative().state().stateSignature()))
                .count();
        int bestTargetGroups = historicalPrefix.stream()
                .filter(value -> value.score().targetShapeFeasible())
                .mapToInt(value -> value.score().groups())
                .min()
                .orElse(-1);
        return new HistoricalSummary(
                historicalPrefix.size(),
                distinctSupports,
                supportDenominator,
                missedRepresentatives,
                bestTargetGroups);
    }

    private static ObjectiveDiagnostics objectiveDiagnostics(
            Snapshot snapshot,
            List<ScoredCandidate> allScored) {
        Map<String, ScoredCandidate> scoredByState = new LinkedHashMap<>();
        allScored.forEach(value -> scoredByState.put(
                value.state().stateSignature(), value));
        Map<Double, Map<String, ScoredCandidate>> statesByObjective =
                new TreeMap<>();
        int ambiguousStates = 0;
        int statesWithoutObjective = 0;
        for (FrozenCandidate state : snapshot.staticOrder()) {
            if (state.masterObjectives().isEmpty()) {
                statesWithoutObjective++;
                continue;
            }
            if (state.masterObjectives().size() > 1) {
                ambiguousStates++;
            }
            for (double objective : state.masterObjectives()) {
                statesByObjective
                        .computeIfAbsent(objective, ignored ->
                                new LinkedHashMap<>())
                        .put(state.stateSignature(),
                                scoredByState.get(state.stateSignature()));
            }
        }
        List<ObjectiveCrossBucket> crossBuckets = new ArrayList<>();
        statesByObjective.forEach((objective, byState) -> crossBuckets.add(
                objectiveCrossBucket(objective,
                        new ArrayList<>(byState.values()))));
        int largestIterationBucket = snapshot.masterObjectiveFrequency()
                .values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return new ObjectiveDiagnostics(
                snapshot.masterObjectiveFrequency(),
                crossBuckets,
                ambiguousStates,
                statesWithoutObjective,
                largestIterationBucket);
    }

    private static ObjectiveCrossBucket objectiveCrossBucket(
            double objective,
            List<ScoredCandidate> states) {
        int supportCount = (int) states.stream()
                .map(value -> value.state().supportIdentity())
                .distinct()
                .count();
        int feasibleStates = (int) states.stream()
                .filter(value -> feasibleScore(value.score()))
                .count();
        int targetStates = (int) states.stream()
                .filter(value -> value.score().targetShapeFeasible())
                .count();
        List<Integer> groups = states.stream()
                .filter(value -> feasibleScore(value.score()))
                .map(value -> value.score().groups())
                .filter(value -> value >= 0)
                .sorted()
                .toList();
        Map<Integer, Integer> frequency = new TreeMap<>();
        groups.forEach(value -> frequency.merge(value, 1, Integer::sum));
        return new ObjectiveCrossBucket(
                objective,
                states.size(),
                supportCount,
                feasibleStates,
                targetStates,
                groups.isEmpty() ? -1 : groups.get(0),
                medianInteger(groups),
                groups.isEmpty() ? -1 : groups.get(groups.size() - 1),
                frequency);
    }

    private static int scoreRank(ScoredCandidate value) {
        if (value.score().targetShapeFeasible()) {
            return 0;
        }
        if (value.score().status()
                == ProductionNeutralMoveSearch.FastStatus.FEASIBLE) {
            return 1;
        }
        return 2;
    }

    private static int reportableGroups(
            ProductionNeutralMoveSearch.FastUpperBound score) {
        return score.groups() < 0 ? Integer.MAX_VALUE : score.groups();
    }

    private static boolean feasibleScore(
            ProductionNeutralMoveSearch.FastUpperBound score) {
        return score.status() == ProductionNeutralMoveSearch.FastStatus.FEASIBLE
                && score.groups() >= 0;
    }

    private static double medianLong(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 != 0) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double medianInteger(List<Integer> sorted) {
        if (sorted.isEmpty()) {
            return -1.0;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 != 0) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    enum SnapshotStatus {
        MATCHES_HISTORICAL_ANCHOR,
        CANDIDATE_SNAPSHOT_DRIFT
    }

    enum Strategy {
        GLOBAL_STATE,
        SUPPORT_BUCKET
    }

    record SourceOccurrence(
            StructuredLocalPatternUniverseBuilder.UniverseScope scope,
            String localSupportSignature,
            OptionalDouble masterObjective) {

        SourceOccurrence {
            scope = Objects.requireNonNull(scope);
            localSupportSignature = Objects.requireNonNull(
                    localSupportSignature);
            masterObjective = Objects.requireNonNull(masterObjective);
        }
    }

    record FrozenCandidate(
            SparseProductionNeutralMoveEnumerator.Candidate candidate,
            String stateSignature,
            String supportIdentity,
            Set<StructuredLocalPatternUniverseBuilder.UniverseScope> scopes,
            List<SourceOccurrence> occurrences,
            Set<Double> masterObjectives) {

        FrozenCandidate {
            candidate = Objects.requireNonNull(candidate);
            stateSignature = Objects.requireNonNull(stateSignature);
            supportIdentity = Objects.requireNonNull(supportIdentity);
            if (scopes.isEmpty()) {
                throw new IllegalArgumentException("scopes must not be empty");
            }
            scopes = Collections.unmodifiableSet(EnumSet.copyOf(scopes));
            occurrences = List.copyOf(occurrences);
            masterObjectives = Collections.unmodifiableSet(
                    new TreeSet<>(masterObjectives));
        }
    }

    record LayerCoverage(int states, int supports) {

        LayerCoverage {
            if (states < 0 || supports < 0 || supports > states) {
                throw new IllegalArgumentException("invalid layer coverage");
            }
        }
    }

    record Snapshot(
            List<FrozenCandidate> staticOrder,
            List<FrozenCandidate> historicalOrder,
            Map<String, List<FrozenCandidate>> statesBySupport,
            Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                    LayerCoverage> layerCoverage,
            Map<Double, Integer> masterObjectiveFrequency,
            int rawCandidateOccurrences,
            int crossLayerDuplicateOccurrences,
            int unmappedObjectiveOccurrences,
            int historicalStateAnchor,
            SnapshotStatus status) {

        Snapshot {
            staticOrder = List.copyOf(staticOrder);
            historicalOrder = List.copyOf(historicalOrder);
            Map<String, List<FrozenCandidate>> bucketCopy = new TreeMap<>();
            statesBySupport.forEach((support, states) -> bucketCopy.put(
                    support, List.copyOf(states)));
            statesBySupport = Collections.unmodifiableMap(bucketCopy);
            Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                    LayerCoverage> layerCopy = new EnumMap<>(
                    StructuredLocalPatternUniverseBuilder.UniverseScope.class);
            layerCopy.putAll(layerCoverage);
            layerCoverage = Collections.unmodifiableMap(layerCopy);
            masterObjectiveFrequency = Collections.unmodifiableMap(
                    new TreeMap<>(masterObjectiveFrequency));
            status = Objects.requireNonNull(status);
        }

        int supportDenominator() {
            return statesBySupport.size();
        }
    }

    record ScoredCandidate(
            FrozenCandidate state,
            ProductionNeutralMoveSearch.FastUpperBound score) {

        ScoredCandidate {
            state = Objects.requireNonNull(state);
            score = Objects.requireNonNull(score);
        }
    }

    record SupportBucket(
            String supportIdentity,
            List<ScoredCandidate> states,
            ScoredCandidate staticFirst,
            ScoredCandidate representative,
            boolean representativeInHistoricalPrefix) {

        SupportBucket {
            supportIdentity = Objects.requireNonNull(supportIdentity);
            states = List.copyOf(states);
            staticFirst = Objects.requireNonNull(staticFirst);
            representative = Objects.requireNonNull(representative);
        }
    }

    record SelectionPlan(
            Strategy strategy,
            List<ScoredCandidate> finalists,
            int distinctSupports,
            int supportDenominator) {

        SelectionPlan {
            strategy = Objects.requireNonNull(strategy);
            finalists = List.copyOf(finalists);
        }
    }

    record Phase2Summary(
            Map<ProductionNeutralMoveSearch.FastStatus, Integer> statusCounts,
            int targetShapeStates,
            int targetShapeSupports,
            int bestTargetGroups,
            int bestFeasibleGroups,
            long totalElapsedMs,
            double averageElapsedMs,
            double medianElapsedMs,
            long maxElapsedMs) {

        Phase2Summary {
            Map<ProductionNeutralMoveSearch.FastStatus, Integer> copy =
                    new EnumMap<>(ProductionNeutralMoveSearch.FastStatus.class);
            copy.putAll(statusCounts);
            statusCounts = Collections.unmodifiableMap(copy);
        }
    }

    record BucketSummary(
            int buckets,
            int multiStateBuckets,
            int representativeChangedBuckets,
            int targetShapeImprovedBuckets,
            int historicalMissedRepresentatives,
            int maxStatesPerBucket,
            int maxGroupImprovement,
            Map<Integer, Integer> stateCountFrequency) {

        BucketSummary {
            stateCountFrequency = Collections.unmodifiableMap(
                    new TreeMap<>(stateCountFrequency));
        }
    }

    record HistoricalSummary(
            int evaluatedStates,
            int distinctSupports,
            int supportDenominator,
            int missedRepresentatives,
            int bestTargetGroups) {
    }

    record ObjectiveCrossBucket(
            double objective,
            int states,
            int supports,
            int feasibleStates,
            int targetShapeStates,
            int minimumGroups,
            double medianGroups,
            int maximumGroups,
            Map<Integer, Integer> groupFrequency) {

        ObjectiveCrossBucket {
            groupFrequency = Collections.unmodifiableMap(
                    new TreeMap<>(groupFrequency));
        }
    }

    record ObjectiveDiagnostics(
            Map<Double, Integer> masterIterationFrequency,
            List<ObjectiveCrossBucket> crossBuckets,
            int ambiguousObjectiveStates,
            int statesWithoutObjective,
            int largestMasterIterationBucket) {

        ObjectiveDiagnostics {
            masterIterationFrequency = Collections.unmodifiableMap(
                    new TreeMap<>(masterIterationFrequency));
            crossBuckets = List.copyOf(crossBuckets);
        }
    }

    record Evaluation(
            Snapshot snapshot,
            List<ScoredCandidate> globalOrder,
            List<ScoredCandidate> historicalPrefix,
            Map<String, SupportBucket> supportBuckets,
            SelectionPlan globalStatePlan,
            SelectionPlan supportBucketPlan,
            Phase2Summary phase2Summary,
            BucketSummary bucketSummary,
            HistoricalSummary historicalSummary,
            ObjectiveDiagnostics objectiveDiagnostics) {

        Evaluation {
            snapshot = Objects.requireNonNull(snapshot);
            globalOrder = List.copyOf(globalOrder);
            historicalPrefix = List.copyOf(historicalPrefix);
            supportBuckets = Collections.unmodifiableMap(
                    new TreeMap<>(supportBuckets));
            globalStatePlan = Objects.requireNonNull(globalStatePlan);
            supportBucketPlan = Objects.requireNonNull(supportBucketPlan);
            phase2Summary = Objects.requireNonNull(phase2Summary);
            bucketSummary = Objects.requireNonNull(bucketSummary);
            historicalSummary = Objects.requireNonNull(historicalSummary);
            objectiveDiagnostics = Objects.requireNonNull(objectiveDiagnostics);
        }
    }

    private static final class MutableFrozenCandidate {

        private final SparseProductionNeutralMoveEnumerator.Candidate candidate;
        private final String supportIdentity;
        private final EnumSet<StructuredLocalPatternUniverseBuilder.UniverseScope>
                scopes = EnumSet.noneOf(
                StructuredLocalPatternUniverseBuilder.UniverseScope.class);
        private final List<SourceOccurrence> occurrences = new ArrayList<>();
        private final Set<Double> masterObjectives = new TreeSet<>();

        private MutableFrozenCandidate(
                SparseProductionNeutralMoveEnumerator.Candidate candidate,
                String supportIdentity) {
            this.candidate = candidate;
            this.supportIdentity = supportIdentity;
        }

        private void add(
                SparseProductionNeutralMoveEnumerator.Candidate other,
                String otherSupportIdentity,
                SourceOccurrence occurrence) {
            if (!candidate.stateSignature().equals(other.stateSignature())) {
                throw new IllegalArgumentException("state signature mismatch");
            }
            if (!supportIdentity.equals(otherSupportIdentity)) {
                throw new IllegalStateException(
                        "one final state resolved to different signed supports: "
                                + candidate.stateSignature());
            }
            scopes.add(occurrence.scope());
            occurrences.add(occurrence);
            occurrence.masterObjective().ifPresent(masterObjectives::add);
        }

        private FrozenCandidate freeze() {
            return new FrozenCandidate(
                    candidate,
                    candidate.stateSignature(),
                    supportIdentity,
                    scopes,
                    occurrences,
                    masterObjectives);
        }
    }
}
