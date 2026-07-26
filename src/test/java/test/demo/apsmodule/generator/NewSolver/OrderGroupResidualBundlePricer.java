package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Metrics;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Research-only incumbent residual bundle pricing (audit design section 10.3).
 *
 * <p>Removes 2..K related group columns from an exact incumbent, treats their
 * summed resource vector (per-order coverage, cars, waste, odd-group count,
 * one-car-group count) as hard equality residual constraints, enumerates every
 * replacement column the universe can materialize inside that residual without
 * any reduced-cost gate or component-wise subset filter, and accepts a joint
 * replacement only when a small exact MIP proves the bundle can be rebuilt
 * with strictly fewer columns under full conservation.</p>
 */
final class OrderGroupResidualBundlePricer {

    private static final double SMALL_GROUP_TIE_COST = 1e-4;
    private static final String SCIP_PARAMS =
            "parallel/maxnthreads = 1\n"
                    + "randomization/randomseedshift = 42\n"
                    + "randomization/permutationseed = 42\n"
                    + "randomization/lpseed = 42\n";

    private OrderGroupResidualBundlePricer() {
    }

    record Options(
            int minBundleSize,
            int maxBundleSize,
            int maxBundlesPerSize,
            int maxCandidateColumns,
            long perBundleMipMs,
            long totalBudgetMs,
            int maxSwaps) {

        Options {
            if (minBundleSize < 2 || maxBundleSize < minBundleSize
                    || maxBundlesPerSize <= 0 || maxCandidateColumns <= 0
                    || perBundleMipMs <= 0 || totalBudgetMs <= 0
                    || maxSwaps <= 0) {
                throw new IllegalArgumentException("invalid bundle-pricer options");
            }
        }

        static Options defaults() {
            return new Options(2, 4, 400, 20_000, 3_000L, 120_000L, 16);
        }
    }

    record SwapRecord(
            List<String> removedSignatures,
            List<String> addedSignatures,
            int groupDelta) {

        SwapRecord {
            removedSignatures = List.copyOf(removedSignatures);
            addedSignatures = List.copyOf(addedSignatures);
        }
    }

    record Result(
            List<GroupColumn> columns,
            Metrics metrics,
            int bundlesScanned,
            int bundlesSkipped,
            int bundlesTruncated,
            int swapsApplied,
            boolean budgetExhausted,
            List<SwapRecord> swaps,
            long elapsedMs) {

        Result {
            columns = List.copyOf(columns);
            swaps = List.copyOf(swaps);
        }
    }

    record CandidateSet(
            List<GroupColumn> columns,
            boolean truncated,
            Set<String> blockedFamilies) {

        CandidateSet {
            columns = List.copyOf(columns);
            blockedFamilies = Set.copyOf(blockedFamilies);
        }
    }

    /**
     * Rebuilds a group column from its full signature
     * ({@code rollWidth|w1xc1,...|w1=[m1, m2];...|cars=k}) so an improved
     * incumbent can be persisted as plain text and resumed in a later run.
     * Self-validating: throws when the rebuilt column does not round-trip to
     * the exact input signature. Assumes order messages contain none of
     * {@code | ; , [ ]} (true for PSR order codes).
     */
    static GroupColumn parseColumn(Input input, String signature) {
        String[] parts = signature.split("\\|");
        if (parts.length != 4 || !parts[3].startsWith("cars=")) {
            throw new IllegalArgumentException(
                    "not a group-column signature: " + signature);
        }
        int rollWidth = Integer.parseInt(parts[0]);
        Map<Integer, Integer> cuts = new TreeMap<>();
        for (String cut : parts[1].split(",")) {
            int split = cut.indexOf('x');
            cuts.put(
                    Integer.parseInt(cut.substring(0, split)),
                    Integer.parseInt(cut.substring(split + 1)));
        }
        Map<Integer, List<String>> config = new TreeMap<>();
        for (String entry : parts[2].split(";")) {
            int equals = entry.indexOf('=');
            int width = Integer.parseInt(entry.substring(0, equals));
            String body = entry.substring(equals + 1);
            if (!body.startsWith("[") || !body.endsWith("]")) {
                throw new IllegalArgumentException(
                        "bad station config in signature: " + signature);
            }
            config.put(width, List.of(
                    body.substring(1, body.length() - 1).split(", ", -1)));
        }
        int cars = Integer.parseInt(parts[3].substring("cars=".length()));
        GroupColumn column = GroupColumn.create(
                input, new PatternCandidate(cuts, rollWidth), config, cars);
        if (!column.signature().equals(signature)) {
            throw new IllegalStateException("signature round-trip mismatch: "
                    + signature + " -> " + column.signature());
        }
        return column;
    }

    static Result improve(Input input, List<GroupColumn> incumbent, Options options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + options.totalBudgetMs();

        List<GroupColumn> current = incumbent.stream()
                .sorted(Comparator.comparing(GroupColumn::signature))
                .toList();
        verifyExact(input, current, "incumbent");

        List<SwapRecord> swaps = new ArrayList<>();
        int scanned = 0;
        int skipped = 0;
        int truncatedBundles = 0;
        boolean budgetExhausted = false;
        // Bundles whose replacement MIP was PROVEN infeasible under a complete
        // (untruncated) candidate enumeration. The proof is only reusable while
        // every family that blocked a candidate back then is still present in
        // the kept set, so the blocked-family set is stored alongside the key.
        Map<String, Set<String>> provenFailed = new java.util.HashMap<>();

        boolean improved = true;
        restart:
        while (improved && swaps.size() < options.maxSwaps()) {
            improved = false;
            int maxSize = Math.min(options.maxBundleSize(), current.size());
            for (int size = options.minBundleSize(); size <= maxSize; size++) {
                for (int[] bundle : rankedBundles(current, size, options.maxBundlesPerSize())) {
                    if (System.currentTimeMillis() >= deadline) {
                        budgetExhausted = true;
                        break restart;
                    }
                    List<GroupColumn> removed = new ArrayList<>(bundle.length);
                    for (int index : bundle) {
                        removed.add(current.get(index));
                    }
                    List<GroupColumn> kept = new ArrayList<>(current.size() - bundle.length);
                    Set<Integer> removedIndices = new HashSet<>();
                    for (int index : bundle) {
                        removedIndices.add(index);
                    }
                    for (int index = 0; index < current.size(); index++) {
                        if (!removedIndices.contains(index)) {
                            kept.add(current.get(index));
                        }
                    }
                    Set<String> keptFamilies = new LinkedHashSet<>();
                    for (GroupColumn column : kept) {
                        keptFamilies.add(column.familySignature());
                    }

                    String bundleKey = removed.stream()
                            .map(GroupColumn::signature)
                            .sorted()
                            .reduce((left, right) -> left + "\u001f" + right)
                            .orElseThrow();
                    Set<String> blockedThen = provenFailed.get(bundleKey);
                    if (blockedThen != null && keptFamilies.containsAll(blockedThen)) {
                        skipped++;
                        continue;
                    }

                    scanned++;
                    Attempt attempt = tryBundle(
                            input, keptFamilies, removed, options, deadline);
                    if (attempt.candidateTruncated()) {
                        truncatedBundles++;
                    }
                    if (attempt.replacement() == null) {
                        if (attempt.provenNoImprovement()
                                && !attempt.candidateTruncated()) {
                            provenFailed.put(bundleKey, attempt.blockedFamilies());
                        }
                        continue;
                    }

                    List<GroupColumn> next = new ArrayList<>(kept);
                    next.addAll(attempt.replacement());
                    next.sort(Comparator.comparing(GroupColumn::signature));
                    verifyExact(input, next, "post-swap solution");
                    swaps.add(new SwapRecord(
                            removed.stream().map(GroupColumn::signature).sorted().toList(),
                            attempt.replacement().stream()
                                    .map(GroupColumn::signature).sorted().toList(),
                            attempt.replacement().size() - removed.size()));
                    current = List.copyOf(next);
                    improved = true;
                    continue restart;
                }
            }
        }

        return new Result(
                current,
                Metrics.fromColumns(current),
                scanned,
                skipped,
                truncatedBundles,
                swaps.size(),
                budgetExhausted,
                swaps,
                System.currentTimeMillis() - startedAt);
    }

    /** Bundles ordered by pairwise order-support overlap, then index order. */
    private static List<int[]> rankedBundles(
            List<GroupColumn> columns, int size, int limit) {
        int count = columns.size();
        long[][] overlap = new long[count][count];
        for (int left = 0; left < count; left++) {
            Set<DemandKey> leftKeys = columns.get(left).coverage().keySet();
            for (int right = left + 1; right < count; right++) {
                long shared = 0;
                for (DemandKey key : columns.get(right).coverage().keySet()) {
                    if (leftKeys.contains(key)) {
                        shared++;
                    }
                }
                overlap[left][right] = shared;
                overlap[right][left] = shared;
            }
        }

        record Scored(int[] indices, long score) {
        }
        List<Scored> scored = new ArrayList<>();
        int[] combo = new int[size];
        enumerateCombinations(count, size, 0, 0, combo, indices -> {
            long score = 0;
            for (int left = 0; left < indices.length; left++) {
                for (int right = left + 1; right < indices.length; right++) {
                    score += overlap[indices[left]][indices[right]];
                }
            }
            scored.add(new Scored(indices.clone(), score));
        });
        scored.sort(Comparator
                .comparingLong(Scored::score).reversed()
                .thenComparing(Scored::indices, OrderGroupResidualBundlePricer::compareIndices));
        return scored.stream()
                .limit(limit)
                .map(Scored::indices)
                .toList();
    }

    private static int compareIndices(int[] left, int[] right) {
        for (int position = 0; position < Math.min(left.length, right.length); position++) {
            int compare = Integer.compare(left[position], right[position]);
            if (compare != 0) {
                return compare;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static void enumerateCombinations(
            int count,
            int size,
            int position,
            int nextIndex,
            int[] combo,
            java.util.function.Consumer<int[]> consumer) {
        if (position == size) {
            consumer.accept(combo);
            return;
        }
        for (int index = nextIndex; index <= count - (size - position); index++) {
            combo[position] = index;
            enumerateCombinations(count, size, position + 1, index + 1, combo, consumer);
        }
    }

    private record Attempt(
            List<GroupColumn> replacement,
            boolean candidateTruncated,
            boolean provenNoImprovement,
            Set<String> blockedFamilies) {
    }

    private static Attempt tryBundle(
            Input input,
            Set<String> keptFamilies,
            List<GroupColumn> removed,
            Options options,
            long deadline) {
        Map<DemandKey, Integer> residual = new TreeMap<>();
        int residualCars = 0;
        int residualWaste = 0;
        int residualOdd = 0;
        int residualOne = 0;
        for (GroupColumn column : removed) {
            column.coverage().forEach((key, value) ->
                    residual.merge(key, value, Math::addExact));
            residualCars = Math.addExact(residualCars, column.cars());
            residualWaste = Math.addExact(residualWaste, column.totalWaste());
            residualOdd += column.odd() ? 1 : 0;
            residualOne += column.oneCar() ? 1 : 0;
        }

        CandidateSet candidates = enumerateResidualColumns(
                input,
                residual,
                residualCars,
                residualWaste,
                residualOdd,
                residualOne,
                keptFamilies,
                removed,
                options.maxCandidateColumns(),
                deadline);
        if (candidates.columns().size() < 2) {
            return new Attempt(
                    null, candidates.truncated(), false,
                    candidates.blockedFamilies());
        }

        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            return new Attempt(
                    null, candidates.truncated(), false,
                    candidates.blockedFamilies());
        }
        MipOutcome outcome = solveBundleMip(
                residual,
                residualCars,
                residualWaste,
                residualOdd,
                residualOne,
                candidates.columns(),
                removed.size(),
                Math.min(options.perBundleMipMs(), remaining));
        return new Attempt(
                outcome.replacement(),
                candidates.truncated(),
                outcome.provenNoImprovement(),
                candidates.blockedFamilies());
    }

    /**
     * Exhaustively materializes every group column the universe supports inside
     * the residual resource vector. No dual signal, no reduced-cost gate, no
     * component-wise subset filter; the only exclusions are family collisions
     * with kept columns and the explicit candidate cap (reported, never silent).
     *
     * <p>Unlike {@code ColumnPool} this keeps resource-signature twins: under
     * the family-at-most-once constraint two columns with identical resources
     * but different families are not interchangeable, and a replacement may
     * legitimately need both.</p>
     */
    static CandidateSet enumerateResidualColumns(
            Input input,
            Map<DemandKey, Integer> residual,
            int residualCars,
            int residualWaste,
            int residualOdd,
            int residualOne,
            Set<String> keptFamilies,
            Collection<GroupColumn> seedColumns,
            int maxCandidateColumns,
            long deadline) {
        Map<String, GroupColumn> bySignature = new TreeMap<>();
        Set<String> blockedFamilies = new java.util.TreeSet<>();
        boolean truncated = false;

        Map<Integer, List<DemandKey>> residualByWidth = new TreeMap<>();
        residual.forEach((key, quantity) -> {
            if (quantity > 0) {
                residualByWidth
                        .computeIfAbsent(key.width(), ignored -> new ArrayList<>())
                        .add(key);
            }
        });

        seedColumns.stream()
                .sorted(Comparator.comparing(GroupColumn::signature))
                .forEach(column -> bySignature.putIfAbsent(column.signature(), column));

        int totalWidth = input.params().getTotalWidth();
        patterns:
        for (PatternCandidate pattern : input.universe()) {
            if (bySignature.size() >= maxCandidateColumns) {
                truncated = true;
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                truncated = true;
                break;
            }
            Map<Integer, Integer> cuts = pattern.getPattern();
            if (!residualByWidth.keySet().containsAll(cuts.keySet())) {
                continue;
            }
            int wastePerCar = pattern.getRealWaste(totalWidth);
            for (int cars = 1; cars <= residualCars; cars++) {
                if ((long) wastePerCar * cars > residualWaste) {
                    break;
                }
                boolean feasible = true;
                for (Map.Entry<Integer, Integer> cut : cuts.entrySet()) {
                    int capacity = 0;
                    for (DemandKey key : residualByWidth.get(cut.getKey())) {
                        capacity += Math.min(cut.getValue(), residual.get(key) / cars);
                    }
                    if (capacity < cut.getValue()) {
                        feasible = false;
                        break;
                    }
                }
                if (!feasible) {
                    break;
                }
                if (residualOne == 0 && cars == 1) {
                    continue;
                }
                if (residualOdd == 0 && cars % 2 == 1) {
                    continue;
                }

                List<List<List<String>>> perWidth = new ArrayList<>();
                for (Map.Entry<Integer, Integer> cut : cuts.entrySet()) {
                    perWidth.add(localConfigs(
                            residualByWidth.get(cut.getKey()),
                            residual,
                            cut.getValue(),
                            cars));
                }
                List<Integer> widths = new ArrayList<>(cuts.keySet());
                Map<Integer, List<String>> config = new TreeMap<>();
                if (!materialize(
                        input,
                        pattern,
                        cars,
                        widths,
                        perWidth,
                        0,
                        config,
                        keptFamilies,
                        bySignature,
                        blockedFamilies,
                        maxCandidateColumns)) {
                    truncated = true;
                    break patterns;
                }
            }
        }

        return new CandidateSet(
                bySignature.values().stream()
                        .sorted(Comparator.comparing(GroupColumn::signature))
                        .toList(),
                truncated,
                blockedFamilies);
    }

    /** Returns false when the candidate cap stops materialization. */
    private static boolean materialize(
            Input input,
            PatternCandidate pattern,
            int cars,
            List<Integer> widths,
            List<List<List<String>>> perWidth,
            int widthIndex,
            Map<Integer, List<String>> config,
            Set<String> keptFamilies,
            Map<String, GroupColumn> bySignature,
            Set<String> blockedFamilies,
            int maxCandidateColumns) {
        if (bySignature.size() >= maxCandidateColumns) {
            return false;
        }
        if (widthIndex == widths.size()) {
            GroupColumn column = GroupColumn.create(
                    input, pattern, new TreeMap<>(config), cars);
            if (keptFamilies.contains(column.familySignature())) {
                blockedFamilies.add(column.familySignature());
            } else {
                bySignature.putIfAbsent(column.signature(), column);
            }
            return true;
        }
        for (List<String> messages : perWidth.get(widthIndex)) {
            config.put(widths.get(widthIndex), messages);
            boolean keepGoing = materialize(
                    input,
                    pattern,
                    cars,
                    widths,
                    perWidth,
                    widthIndex + 1,
                    config,
                    keptFamilies,
                    bySignature,
                    blockedFamilies,
                    maxCandidateColumns);
            config.remove(widths.get(widthIndex));
            if (!keepGoing) {
                return false;
            }
        }
        return true;
    }

    /** All multisets of residual orders filling exactly {@code slots} stations. */
    private static List<List<String>> localConfigs(
            List<DemandKey> orders,
            Map<DemandKey, Integer> residual,
            int slots,
            int cars) {
        List<List<String>> result = new ArrayList<>();
        int[] counts = new int[orders.size()];
        enumerateCounts(orders, residual, cars, 0, slots, counts, result);
        return result;
    }

    private static void enumerateCounts(
            List<DemandKey> orders,
            Map<DemandKey, Integer> residual,
            int cars,
            int orderIndex,
            int remainingSlots,
            int[] counts,
            List<List<String>> result) {
        if (orderIndex == orders.size() - 1) {
            DemandKey last = orders.get(orderIndex);
            if ((long) remainingSlots * cars <= residual.get(last)) {
                counts[orderIndex] = remainingSlots;
                collect(orders, counts, result);
                counts[orderIndex] = 0;
            }
            return;
        }
        DemandKey key = orders.get(orderIndex);
        int max = Math.min(remainingSlots, residual.get(key) / cars);
        for (int count = 0; count <= max; count++) {
            counts[orderIndex] = count;
            enumerateCounts(
                    orders, residual, cars, orderIndex + 1,
                    remainingSlots - count, counts, result);
        }
        counts[orderIndex] = 0;
    }

    private static void collect(
            List<DemandKey> orders, int[] counts, List<List<String>> result) {
        List<String> messages = new ArrayList<>();
        for (int index = 0; index < orders.size(); index++) {
            for (int occurrence = 0; occurrence < counts[index]; occurrence++) {
                messages.add(orders.get(index).messageText());
            }
        }
        if (!messages.isEmpty()) {
            messages.sort(String::compareTo);
            result.add(List.copyOf(messages));
        }
    }

    /**
     * {@code provenNoImprovement} is true only when the solver PROVED the
     * bundle admits no strictly smaller conserved replacement over the given
     * candidates; timeouts and solver failures stay unproven.
     */
    private record MipOutcome(
            List<GroupColumn> replacement, boolean provenNoImprovement) {
    }

    private static MipOutcome solveBundleMip(
            Map<DemandKey, Integer> residual,
            int residualCars,
            int residualWaste,
            int residualOdd,
            int residualOne,
            List<GroupColumn> candidates,
            int bundleSize,
            long timeLimitMs) {
        MPSolver scip = MPSolver.createSolver("SCIP");
        if (scip != null) {
            scip.setSolverSpecificParametersAsString(SCIP_PARAMS);
        }
        MPSolver solver = scip != null ? scip : MPSolver.createSolver("CBC");
        if (solver == null) {
            return new MipOutcome(null, false);
        }
        solver.setTimeLimit(Math.max(1L, timeLimitMs));

        List<MPVariable> variables = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            variables.add(solver.makeBoolVar("x_" + index));
        }

        Map<DemandKey, MPConstraint> demandConstraints = new TreeMap<>();
        for (Map.Entry<DemandKey, Integer> entry : residual.entrySet()) {
            demandConstraints.put(entry.getKey(), solver.makeConstraint(
                    entry.getValue(), entry.getValue(),
                    "r_" + entry.getKey().signature()));
        }
        MPConstraint carsConstraint =
                solver.makeConstraint(residualCars, residualCars, "cars");
        MPConstraint wasteConstraint =
                solver.makeConstraint(residualWaste, residualWaste, "waste");
        MPConstraint oddConstraint =
                solver.makeConstraint(residualOdd, residualOdd, "odd");
        MPConstraint oneConstraint =
                solver.makeConstraint(residualOne, residualOne, "one");
        MPConstraint improvement =
                solver.makeConstraint(0, bundleSize - 1, "improvement");

        Map<String, MPConstraint> familyConstraints = new TreeMap<>();
        MPObjective objective = solver.objective();
        for (int index = 0; index < candidates.size(); index++) {
            GroupColumn column = candidates.get(index);
            MPVariable variable = variables.get(index);
            for (Map.Entry<DemandKey, Integer> entry : column.coverage().entrySet()) {
                MPConstraint constraint = demandConstraints.get(entry.getKey());
                if (constraint == null) {
                    throw new IllegalStateException(
                            "candidate covers order outside residual: "
                                    + column.signature());
                }
                constraint.setCoefficient(variable, entry.getValue());
            }
            carsConstraint.setCoefficient(variable, column.cars());
            wasteConstraint.setCoefficient(variable, column.totalWaste());
            oddConstraint.setCoefficient(variable, column.odd() ? 1.0 : 0.0);
            oneConstraint.setCoefficient(variable, column.oneCar() ? 1.0 : 0.0);
            improvement.setCoefficient(variable, 1.0);
            familyConstraints
                    .computeIfAbsent(column.familySignature(), ignored ->
                            solver.makeConstraint(0.0, 1.0,
                                    "f_" + familyConstraints.size()))
                    .setCoefficient(variable, 1.0);
            objective.setCoefficient(
                    variable,
                    1.0 + (column.small() ? SMALL_GROUP_TIE_COST : 0.0));
        }
        objective.setMinimization();

        MPSolver.ResultStatus status = solver.solve();
        if (status == MPSolver.ResultStatus.INFEASIBLE) {
            return new MipOutcome(null, true);
        }
        if (status != MPSolver.ResultStatus.OPTIMAL
                && status != MPSolver.ResultStatus.FEASIBLE) {
            return new MipOutcome(null, false);
        }
        List<GroupColumn> selected = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            if (variables.get(index).solutionValue() > 0.5) {
                selected.add(candidates.get(index));
            }
        }
        if (selected.size() >= bundleSize
                || !conserves(selected, residual, residualCars,
                        residualWaste, residualOdd, residualOne)) {
            return new MipOutcome(null, false);
        }
        return new MipOutcome(
                selected.stream()
                        .sorted(Comparator.comparing(GroupColumn::signature))
                        .toList(),
                false);
    }

    private static boolean conserves(
            List<GroupColumn> columns,
            Map<DemandKey, Integer> residual,
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
            cars += column.cars();
            waste += column.totalWaste();
            odd += column.odd() ? 1 : 0;
            one += column.oneCar() ? 1 : 0;
            if (!families.add(column.familySignature())) {
                return false;
            }
        }
        return coverage.equals(residual)
                && cars == residualCars
                && waste == residualWaste
                && odd == residualOdd
                && one == residualOne;
    }

    private static void verifyExact(
            Input input, List<GroupColumn> columns, String context) {
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
                throw new IllegalStateException(
                        context + " has duplicate family: "
                                + column.familySignature());
            }
        }
        if (!coverage.equals(input.demand())
                || cars != input.exactCars()
                || waste != input.exactWaste()
                || odd != input.exactOddGroups()
                || one != input.exactOneCarGroups()) {
            throw new IllegalStateException(context + " is not exact: cars="
                    + cars + " waste=" + waste + " odd=" + odd + " one=" + one);
        }
    }
}
