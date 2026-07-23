package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPSolver;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.mip.AssignmentMIPSolver;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Research-only root-LP column-generation prototype whose columns are complete
 * order-level sequence groups rather than bare cutting patterns.
 *
 * <p>This class deliberately lives under {@code src/test}. It is an
 * architecture experiment, not a production branch-and-price implementation.</p>
 */
final class OrderGroupColumnPricingPrototype {

    private OrderGroupColumnPricingPrototype() {
    }

    static Result solve(Input input, Options options) {
        return OrderGroupColumnPricingEngine.solve(input, options);
    }

    static Result solveCore(
            Input input,
            Options options,
            List<GroupColumn> initialColumns,
            OrderGroupColumnIncumbentSeeder.SeedResult seed,
            long totalStartedAt) {
        return OrderGroupColumnPricingEngine.solveCore(
                input, options, initialColumns, seed, totalStartedAt);
    }

    enum Phase {
        FEASIBILITY,
        OPTIMIZATION
    }

    enum ColumnRole {
        EVEN_CORE,
        ODD_REQUIRED,
        RESIDUAL_CLOSURE,
        SMALL_FALLBACK,
        ONE_CAR_FALLBACK
    }

    enum TerminationStatus {
        NO_NEGATIVE_COLUMN_FOUND,
        COLUMN_CAP_REACHED,
        TIME_LIMIT,
        ITERATION_LIMIT,
        MASTER_FAILED,
        ARTIFICIAL_SLACK_REMAINS,
        INTEGER_MASTER_OPTIMAL,
        INTEGER_MASTER_FEASIBLE,
        INTEGER_MASTER_INFEASIBLE,
        SEMANTIC_MISMATCH
    }

    record DemandKey(int width, String messageText) implements Comparable<DemandKey> {
        DemandKey {
            if (width <= 0) {
                throw new IllegalArgumentException("width must be positive");
            }
            messageText = Objects.toString(messageText, "").trim();
        }

        @Override
        public int compareTo(DemandKey other) {
            int widthCompare = Integer.compare(width, other.width);
            return widthCompare != 0
                    ? widthCompare
                    : messageText.compareTo(other.messageText);
        }

        String signature() {
            return width + ":" + messageText;
        }
    }

    record Input(
            List<SolverOrderItem> items,
            List<PatternCandidate> universe,
            SolverParameters params,
            int exactCars,
            int exactWaste,
            int exactOddGroups,
            int exactOneCarGroups,
            Map<DemandKey, Integer> demand,
            Map<Integer, List<DemandKey>> ordersByWidth) {

        Input(
                List<SolverOrderItem> items,
                List<PatternCandidate> universe,
                SolverParameters params,
                int exactCars,
                int exactWaste,
                int exactOddGroups,
                int exactOneCarGroups) {
            this(
                    normalizeItems(items),
                    normalizeUniverse(universe),
                    Objects.requireNonNull(params, "params").copy(),
                    exactCars,
                    exactWaste,
                    exactOddGroups,
                    exactOneCarGroups,
                    aggregateDemand(items),
                    groupOrdersByWidth(items));
        }

        Input {
            items = List.copyOf(items);
            universe = List.copyOf(universe);
            params = params.copy();
            demand = Collections.unmodifiableMap(new TreeMap<>(demand));
            Map<Integer, List<DemandKey>> widthCopy = new TreeMap<>();
            ordersByWidth.forEach((width, keys) ->
                    widthCopy.put(width, keys.stream().sorted().toList()));
            ordersByWidth = Collections.unmodifiableMap(widthCopy);
            if (items.isEmpty() || universe.isEmpty()) {
                throw new IllegalArgumentException("items and universe must not be empty");
            }
            if (exactCars <= 0 || exactWaste < 0
                    || exactOddGroups < 0 || exactOneCarGroups < 0) {
                throw new IllegalArgumentException("invalid exact targets");
            }
            Set<Integer> knownWidths = ordersByWidth.keySet();
            for (PatternCandidate pattern : universe) {
                if (!knownWidths.containsAll(pattern.getPattern().keySet())) {
                    throw new IllegalArgumentException(
                            "pattern contains a width without orders: " + pattern.signature());
                }
            }
        }

        private static List<SolverOrderItem> normalizeItems(List<SolverOrderItem> source) {
            Objects.requireNonNull(source, "items");
            if (source.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("items contains null");
            }
            return source.stream()
                    .sorted(Comparator.comparingInt(SolverOrderItem::getWidth)
                            .thenComparing(item ->
                                    Objects.toString(item.getMessageText(), "")))
                    .toList();
        }

        private static List<PatternCandidate> normalizeUniverse(
                List<PatternCandidate> source) {
            Objects.requireNonNull(source, "universe");
            Map<String, PatternCandidate> unique = new TreeMap<>();
            for (PatternCandidate pattern : source) {
                Objects.requireNonNull(pattern, "universe contains null");
                unique.putIfAbsent(pattern.signature(), pattern);
            }
            return List.copyOf(unique.values());
        }

        private static Map<DemandKey, Integer> aggregateDemand(
                List<SolverOrderItem> source) {
            Objects.requireNonNull(source, "items");
            Map<DemandKey, Integer> result = new TreeMap<>();
            for (SolverOrderItem item : source) {
                if (item == null || item.getDemand() <= 0) {
                    throw new IllegalArgumentException("order demand must be positive");
                }
                result.merge(
                        new DemandKey(item.getWidth(), item.getMessageText()),
                        item.getDemand(),
                        Math::addExact);
            }
            return result;
        }

        private static Map<Integer, List<DemandKey>> groupOrdersByWidth(
                List<SolverOrderItem> source) {
            Map<Integer, Set<DemandKey>> grouped = new TreeMap<>();
            for (SolverOrderItem item : source) {
                DemandKey key = new DemandKey(item.getWidth(), item.getMessageText());
                grouped.computeIfAbsent(item.getWidth(), ignored -> new LinkedHashSet<>())
                        .add(key);
            }
            Map<Integer, List<DemandKey>> result = new TreeMap<>();
            grouped.forEach((width, keys) -> result.put(width, keys.stream().sorted().toList()));
            return result;
        }
    }

    record Options(
            int maxIterations,
            int maxColumns,
            int maxAddedPerIteration,
            int maxPerPatternPerIteration,
            int maxLocalConfigsPerWidth,
            int beamWidth,
            int maxColumnsPerPattern,
            int maxCarCandidatesPerPattern,
            long pricingTimeLimitMs,
            long integerCompletionTimeLimitMs,
            int integerRepairCandidates,
            long integerRepairTimeLimitMs,
            long lpTimeLimitMs,
            long integerTimeLimitMs,
            double dualAlpha,
            double reducedCostEpsilon,
            double artificialEpsilon,
            int noColumnPatience,
            boolean automaticIncumbentSeed,
            long patternSeedTimeLimitMs) {

        Options {
            if (maxIterations <= 0 || maxColumns <= 0 || maxAddedPerIteration <= 0
                    || maxPerPatternPerIteration <= 0 || maxLocalConfigsPerWidth <= 0
                    || beamWidth <= 0 || maxColumnsPerPattern <= 0
                    || maxCarCandidatesPerPattern <= 0 || pricingTimeLimitMs <= 0
                    || integerCompletionTimeLimitMs <= 0
                    || integerRepairCandidates <= 0 || integerRepairTimeLimitMs <= 0
                    || lpTimeLimitMs <= 0 || integerTimeLimitMs <= 0
                    || dualAlpha <= 0.0 || dualAlpha > 1.0
                    || reducedCostEpsilon <= 0.0 || artificialEpsilon <= 0.0
                    || noColumnPatience <= 0
                    || patternSeedTimeLimitMs <= 0) {
                throw new IllegalArgumentException("invalid prototype options");
            }
        }

        static Options defaults() {
            return new Options(
                    25,
                    500,
                    60,
                    16,
                    8,
                    64,
                    256,
                    24,
                    10_000L,
                    2_000L,
                    5_000,
                    10_000L,
                    5_000L,
                    30_000L,
                    0.7,
                    1e-7,
                    1e-7,
                    3,
                    true,
                    10_000L);
        }

        static Options unitTestDefaults() {
            return new Options(
                    12,
                    80,
                    12,
                    4,
                    8,
                    16,
                    32,
                    24,
                    5_000L,
                    1_000L,
                    200,
                    3_000L,
                    2_000L,
                    5_000L,
                    0.7,
                    1e-8,
                    1e-8,
                    2,
                    false,
                    3_000L);
        }

        Options withoutAutomaticIncumbentSeed() {
            return new Options(
                    maxIterations,
                    maxColumns,
                    maxAddedPerIteration,
                    maxPerPatternPerIteration,
                    maxLocalConfigsPerWidth,
                    beamWidth,
                    maxColumnsPerPattern,
                    maxCarCandidatesPerPattern,
                    pricingTimeLimitMs,
                    integerCompletionTimeLimitMs,
                    integerRepairCandidates,
                    integerRepairTimeLimitMs,
                    lpTimeLimitMs,
                    integerTimeLimitMs,
                    dualAlpha,
                    reducedCostEpsilon,
                    artificialEpsilon,
                    noColumnPatience,
                    false,
                    patternSeedTimeLimitMs);
        }
    }

    record GroupColumn(
            PatternCandidate pattern,
            Map<Integer, List<String>> stationConfig,
            int cars,
            Map<DemandKey, Integer> coverage,
            int totalWaste,
            boolean odd,
            boolean oneCar,
            boolean small,
            ColumnRole role,
            String familySignature,
            String resourceSignature,
            String signature) {

        GroupColumn {
            pattern = Objects.requireNonNull(pattern, "pattern");
            stationConfig = immutableConfig(stationConfig);
            coverage = Collections.unmodifiableMap(new TreeMap<>(coverage));
            role = Objects.requireNonNull(role, "role");
            familySignature = Objects.requireNonNull(familySignature);
            resourceSignature = Objects.requireNonNull(resourceSignature);
            signature = Objects.requireNonNull(signature);
            if (cars <= 0 || totalWaste < 0) {
                throw new IllegalArgumentException("invalid group-column resources");
            }
        }

        static GroupColumn create(
                Input input,
                PatternCandidate pattern,
                Map<Integer, List<String>> stationConfig,
                int cars) {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(pattern, "pattern");
            if (cars <= 0) {
                throw new IllegalArgumentException("cars must be positive");
            }
            Map<Integer, List<String>> canonical = immutableConfig(stationConfig);
            if (!canonical.keySet().equals(new LinkedHashSet<>(pattern.getPattern().keySet()))) {
                throw new IllegalArgumentException(
                        "station config widths do not match pattern: " + pattern.signature());
            }

            Map<DemandKey, Integer> coverage = new TreeMap<>();
            int closureCount = 0;
            for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                List<String> messages = canonical.get(cut.getKey());
                if (messages == null || messages.size() != cut.getValue()) {
                    throw new IllegalArgumentException(
                            "station count mismatch at width " + cut.getKey());
                }
                for (String message : messages) {
                    DemandKey key = new DemandKey(cut.getKey(), message);
                    if (!input.demand().containsKey(key)) {
                        throw new IllegalArgumentException(
                                "unknown order in station config: " + key.signature());
                    }
                    coverage.merge(key, cars, Math::addExact);
                }
            }
            for (Map.Entry<DemandKey, Integer> entry : coverage.entrySet()) {
                int demand = input.demand().get(entry.getKey());
                if (entry.getValue() > demand) {
                    throw new IllegalArgumentException(
                            "column over-covers order " + entry.getKey().signature());
                }
                if (entry.getValue() == demand) {
                    closureCount++;
                }
            }

            boolean odd = cars % 2 != 0;
            boolean oneCar = cars == 1;
            boolean small = cars <= SequenceGroupPostProcessor.SMALL_CAR_MAX_CARS;
            ColumnRole role;
            if (oneCar) {
                role = ColumnRole.ONE_CAR_FALLBACK;
            } else if (odd) {
                role = ColumnRole.ODD_REQUIRED;
            } else if (closureCount > 0) {
                role = ColumnRole.RESIDUAL_CLOSURE;
            } else if (small) {
                role = ColumnRole.SMALL_FALLBACK;
            } else {
                role = ColumnRole.EVEN_CORE;
            }

            int totalWaste = Math.multiplyExact(
                    pattern.getRealWaste(input.params().getTotalWidth()), cars);
            String configSignature = canonicalConfigSignature(canonical);
            String family = pattern.signature() + "|" + configSignature;
            String signature = family + "|cars=" + cars;
            String resource = OrderGroupColumnPricingPrototype.resourceSignature(
                    coverage, cars, totalWaste, odd, oneCar, small);
            return new GroupColumn(
                    pattern,
                    canonical,
                    cars,
                    coverage,
                    totalWaste,
                    odd,
                    oneCar,
                    small,
                    role,
                    family,
                    resource,
                    signature);
        }

        private static Map<Integer, List<String>> immutableConfig(
                Map<Integer, List<String>> source) {
            Objects.requireNonNull(source, "stationConfig");
            Map<Integer, List<String>> result = new TreeMap<>();
            for (Map.Entry<Integer, List<String>> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getKey() <= 0
                        || entry.getValue() == null || entry.getValue().isEmpty()) {
                    throw new IllegalArgumentException("invalid station config");
                }
                List<String> messages = entry.getValue().stream()
                        .map(value -> Objects.toString(value, "").trim())
                        .sorted()
                        .toList();
                result.put(entry.getKey(), messages);
            }
            return Collections.unmodifiableMap(result);
        }
    }

    record DualVector(
            Map<DemandKey, Double> demand,
            double cars,
            double waste,
            double odd,
            double oneCar,
            Map<String, Double> family) {

        DualVector {
            demand = Collections.unmodifiableMap(new TreeMap<>(demand));
            family = Collections.unmodifiableMap(new TreeMap<>(family));
        }

        static DualVector blend(DualVector current, DualVector previous, double alpha) {
            Map<DemandKey, Double> demand = new TreeMap<>();
            Set<DemandKey> demandKeys = new LinkedHashSet<>(current.demand().keySet());
            demandKeys.addAll(previous.demand().keySet());
            for (DemandKey key : demandKeys) {
                demand.put(key, alpha * current.demand().getOrDefault(key, 0.0)
                        + (1.0 - alpha) * previous.demand().getOrDefault(key, 0.0));
            }
            Map<String, Double> family = new TreeMap<>();
            Set<String> families = new LinkedHashSet<>(current.family().keySet());
            families.addAll(previous.family().keySet());
            for (String key : families) {
                family.put(key, alpha * current.family().getOrDefault(key, 0.0)
                        + (1.0 - alpha) * previous.family().getOrDefault(key, 0.0));
            }
            return new DualVector(
                    demand,
                    alpha * current.cars() + (1.0 - alpha) * previous.cars(),
                    alpha * current.waste() + (1.0 - alpha) * previous.waste(),
                    alpha * current.odd() + (1.0 - alpha) * previous.odd(),
                    alpha * current.oneCar() + (1.0 - alpha) * previous.oneCar(),
                    family);
        }
    }

    static double rawReducedCost(GroupColumn column, DualVector dual, Phase phase) {
        double cost = phase == Phase.FEASIBILITY
                ? OrderGroupRestrictedMaster.FEASIBILITY_COLUMN_COST
                : OrderGroupRestrictedMaster.optimizationCost(column);
        double contribution = dual.cars() * column.cars()
                + dual.waste() * column.totalWaste()
                + dual.odd() * (column.odd() ? 1.0 : 0.0)
                + dual.oneCar() * (column.oneCar() ? 1.0 : 0.0)
                + dual.family().getOrDefault(column.familySignature(), 0.0);
        for (Map.Entry<DemandKey, Integer> entry : column.coverage().entrySet()) {
            contribution += dual.demand().getOrDefault(entry.getKey(), 0.0)
                    * entry.getValue();
        }
        return cost - contribution;
    }

    static String canonicalConfigSignature(Map<Integer, List<String>> config) {
        return config.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue().stream()
                        .map(value -> Objects.toString(value, "").trim())
                        .sorted()
                        .toList())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    static String resourceSignature(
            Map<DemandKey, Integer> coverage,
            int cars,
            int waste,
            boolean odd,
            boolean oneCar,
            boolean small) {
        String coveragePart = coverage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().signature() + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
        return coveragePart + "|cars=" + cars + "|waste=" + waste
                + "|odd=" + odd + "|one=" + oneCar + "|small=" + small;
    }

    static boolean sameResourceVector(GroupColumn left, GroupColumn right) {
        return left.resourceSignature().equals(right.resourceSignature());
    }

    static List<GroupColumn> roleDiverseSelection(
            Collection<GroupColumn> candidates,
            int limit,
            int maxPerPattern,
            Map<String, Double> rawReducedCosts) {
        return OrderGroupColumnRoleSelector.select(
                candidates, limit, maxPerPattern, rawReducedCosts);
    }

    static final class ColumnPool {
        private final int capacity;
        private final Map<String, GroupColumn> bySignature = new LinkedHashMap<>();
        private final Map<String, String> signatureByResource = new LinkedHashMap<>();

        ColumnPool(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.capacity = capacity;
        }

        int addAll(Collection<GroupColumn> candidates) {
            int added = 0;
            for (GroupColumn candidate : candidates) {
                if (size() >= capacity) {
                    break;
                }
                if (add(candidate)) {
                    added++;
                }
            }
            return added;
        }

        boolean add(GroupColumn candidate) {
            if (bySignature.containsKey(candidate.signature())) {
                return false;
            }
            String existingSignature =
                    signatureByResource.get(candidate.resourceSignature());
            if (existingSignature != null) {
                if (existingSignature.compareTo(candidate.signature()) <= 0) {
                    return false;
                }
                bySignature.remove(existingSignature);
            }
            bySignature.put(candidate.signature(), candidate);
            signatureByResource.put(candidate.resourceSignature(), candidate.signature());
            return true;
        }

        boolean contains(String signature) {
            return bySignature.containsKey(signature);
        }

        int size() {
            return bySignature.size();
        }

        List<GroupColumn> columns() {
            return bySignature.values().stream()
                    .sorted(Comparator.comparing(GroupColumn::signature))
                    .toList();
        }

        void retain(Set<String> signatures) {
            bySignature.entrySet().removeIf(
                    entry -> !signatures.contains(entry.getKey()));
            signatureByResource.clear();
            bySignature.values().forEach(column ->
                    signatureByResource.put(
                            column.resourceSignature(), column.signature()));
        }
    }

    record Metrics(
            int groups,
            int oddGroups,
            int oneCarGroups,
            int smallGroups,
            int cars,
            int waste) {

        static Metrics empty() {
            return new Metrics(0, 0, 0, 0, 0, 0);
        }

        static Metrics fromColumns(List<GroupColumn> columns) {
            return new Metrics(
                    columns.size(),
                    (int) columns.stream().filter(GroupColumn::odd).count(),
                    (int) columns.stream().filter(GroupColumn::oneCar).count(),
                    (int) columns.stream().filter(GroupColumn::small).count(),
                    columns.stream().mapToInt(GroupColumn::cars).sum(),
                    columns.stream().mapToInt(GroupColumn::totalWaste).sum());
        }
    }

    record IterationTrace(
            int iteration,
            Phase phase,
            int poolSize,
            int addedColumns,
            double lpObjective,
            double maxArtificial,
            double dualL1,
            double bestRawReducedCost,
            double worstRawReducedCost) {
    }

    record ConvertedOutput(
            Map<PatternCandidate, Integer> solution,
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> assignments,
            List<CuttingInstruction> instructions) {
    }

    record Result(
            TerminationStatus status,
            TerminationStatus pricingTermination,
            List<GroupColumn> pool,
            List<GroupColumn> selectedColumns,
            Map<PatternCandidate, Integer> solution,
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> assignments,
            List<CuttingInstruction> instructions,
            Metrics directMetrics,
            Metrics displayedMetrics,
            boolean semanticConsistent,
            int iterations,
            double finalLpObjective,
            double maxArtificial,
            MPSolver.ResultStatus integerStatus,
            double integerObjective,
            long integerNodes,
            List<IterationTrace> traces,
            OrderGroupColumnPricingOracle.Diagnostics pricingDiagnostics,
            long lpMs,
            long pricingMs,
            long integerMs,
            long totalMs,
            MPSolver.ResultStatus repairStatus,
            int repairCandidateCount,
            int repairSelectedCount,
            double repairMaxArtificial,
            long repairMs,
            OrderGroupColumnIncumbentSeeder.SeedResult seed) {

        boolean feasible() {
            return status == TerminationStatus.INTEGER_MASTER_OPTIMAL
                    || status == TerminationStatus.INTEGER_MASTER_FEASIBLE;
        }

        static Result withoutIntegerSolution(
                TerminationStatus status,
                TerminationStatus pricingTermination,
                List<GroupColumn> pool,
                int iterations,
                OrderGroupRestrictedMaster.LpResult lp,
                List<IterationTrace> traces,
                OrderGroupColumnPricingOracle.Diagnostics diagnostics,
                long lpMs,
                long pricingMs,
                long totalMs,
                OrderGroupColumnIncumbentSeeder.SeedResult seed) {
            return withoutIntegerSolution(
                    status,
                    pricingTermination,
                    pool,
                    iterations,
                    lp,
                    traces,
                    diagnostics,
                    lpMs,
                    pricingMs,
                    0L,
                    totalMs,
                    OrderGroupRestrictedMaster.IntegerResult.notSolved(),
                    MPSolver.ResultStatus.NOT_SOLVED,
                    0,
                    0,
                    Double.POSITIVE_INFINITY,
                    0L,
                    seed);
        }

        static Result withoutIntegerSolution(
                TerminationStatus status,
                TerminationStatus pricingTermination,
                List<GroupColumn> pool,
                int iterations,
                OrderGroupRestrictedMaster.LpResult lp,
                List<IterationTrace> traces,
                OrderGroupColumnPricingOracle.Diagnostics diagnostics,
                long lpMs,
                long pricingMs,
                long integerMs,
                long totalMs,
                OrderGroupRestrictedMaster.IntegerResult integerResult,
                MPSolver.ResultStatus repairStatus,
                int repairCandidateCount,
                int repairSelectedCount,
                double repairMaxArtificial,
                long repairMs,
                OrderGroupColumnIncumbentSeeder.SeedResult seed) {
            return new Result(
                    status,
                    pricingTermination,
                    List.copyOf(pool),
                    List.of(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    Metrics.empty(),
                    Metrics.empty(),
                    false,
                    iterations,
                    lp == null ? Double.NaN : lp.objectiveValue(),
                    lp == null ? Double.POSITIVE_INFINITY : lp.maxArtificialValue(),
                    integerResult.status(),
                    integerResult.objectiveValue(),
                    integerResult.nodes(),
                    List.copyOf(traces),
                    diagnostics,
                    lpMs,
                    pricingMs,
                    integerMs,
                    totalMs,
                    repairStatus,
                    repairCandidateCount,
                    repairSelectedCount,
                    repairMaxArtificial,
                    repairMs,
                    seed);
        }
    }
}
