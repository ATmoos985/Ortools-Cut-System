package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Research-only exact order-compatibility kernel for a fixed pattern-usage set.
 *
 * <p>The value is deliberately a set function. It enumerates every full-roll
 * message configuration available to the selected patterns, then minimizes the
 * number of active configurations needed to cover every order exactly.</p>
 */
final class OrderCompatibilityKernelAnalyzer {

    private static final double EPS = 1e-6;
    private static final Comparator<DemandKey> DEMAND_ORDER =
            Comparator.comparingInt(DemandKey::width)
                    .thenComparing(DemandKey::message);
    private static final String SCIP_DETERMINISTIC_PARAMS =
            "parallel/maxnthreads = 1\n"
                    + "randomization/randomseedshift = 0\n"
                    + "randomization/permutationseed = 0\n"
                    + "randomization/lpseed = 0\n";

    private OrderCompatibilityKernelAnalyzer() {
    }

    static Analysis analyze(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> orderItems,
            Options options) {
        Objects.requireNonNull(options, "options");
        long startedAt = System.currentTimeMillis();

        ModelData data;
        try {
            data = prepare(solution, orderItems, options.maxConfigurations());
        } catch (ConfigurationLimitExceeded limit) {
            return emptyAnalysis(
                    Status.ENUMERATION_LIMIT,
                    limit.patternCount(),
                    limit.configurationCount(),
                    System.currentTimeMillis() - startedAt);
        }

        long enumerationMs = System.currentTimeMillis() - startedAt;
        SolverChoice groupChoice = createSolver(options);
        if (groupChoice == null) {
            return emptyAnalysis(
                    Status.SOLVER_UNAVAILABLE,
                    data.patterns().size(),
                    data.columns().size(),
                    enumerationMs);
        }

        Model groupModel = buildModel(
                groupChoice, data, Level.GROUPS, null, null, null, options);
        StageResult groupStage = solve(groupModel, options.groupTimeLimitMs());
        long groupSolveMs = groupStage.elapsedMs();
        int patternCount = data.patterns().size();

        if (!groupStage.feasible()) {
            return new Analysis(
                    mapStatus(groupStage.status()),
                    groupChoice.name(),
                    groupStage.status(),
                    MPSolver.ResultStatus.NOT_SOLVED,
                    MPSolver.ResultStatus.NOT_SOLVED,
                    false,
                    false,
                    patternCount,
                    -1,
                    lowerBound(patternCount, groupStage.bestBound()),
                    -1,
                    -1,
                    -1,
                    -1,
                    data.columns().size(),
                    groupModel.solver().numVariables(),
                    groupModel.solver().numConstraints(),
                    groupStage.nodes(),
                    groupStage.bestBound(),
                    Double.NaN,
                    enumerationMs,
                    groupSolveMs,
                    0L,
                    System.currentTimeMillis() - startedAt,
                    List.of());
        }

        Snapshot best = groupStage.snapshot();
        boolean groupOptimal = groupStage.status() == MPSolver.ResultStatus.OPTIMAL;
        int provenLowerBound = groupOptimal
                ? best.groups()
                : lowerBound(patternCount, groupStage.bestBound());
        MPSolver.ResultStatus oddStatus = MPSolver.ResultStatus.NOT_SOLVED;
        MPSolver.ResultStatus oneStatus = MPSolver.ResultStatus.NOT_SOLVED;
        boolean shapeOptimal = false;
        long shapeSolveMs = 0L;
        long totalNodes = groupStage.nodes();
        int variableCount = groupModel.solver().numVariables();
        int constraintCount = groupModel.solver().numConstraints();

        if (groupOptimal && options.shapeTimeLimitMs() > 0) {
            long shapeStartedAt = System.currentTimeMillis();
            long shapeDeadline = safeDeadline(shapeStartedAt, options.shapeTimeLimitMs());

            SolverChoice oddChoice = createSolver(options);
            if (oddChoice != null) {
                Model oddModel = buildModel(
                        oddChoice, data, Level.ODD, best.groups(), null, best, options);
                StageResult oddStage = solve(oddModel, remainingMs(shapeDeadline));
                oddStatus = oddStage.status();
                totalNodes = addNodes(totalNodes, oddStage.nodes());
                variableCount = Math.max(variableCount, oddModel.solver().numVariables());
                constraintCount = Math.max(constraintCount, oddModel.solver().numConstraints());
                if (oddStage.feasible()) {
                    best = oddStage.snapshot();
                }

                if (oddStage.status() == MPSolver.ResultStatus.OPTIMAL
                        && remainingMs(shapeDeadline) > 0) {
                    SolverChoice oneChoice = createSolver(options);
                    if (oneChoice != null) {
                        Model oneModel = buildModel(
                                oneChoice,
                                data,
                                Level.ONE,
                                best.groups(),
                                best.oddGroups(),
                                best,
                                options);
                        StageResult oneStage = solve(oneModel, remainingMs(shapeDeadline));
                        oneStatus = oneStage.status();
                        totalNodes = addNodes(totalNodes, oneStage.nodes());
                        variableCount = Math.max(
                                variableCount, oneModel.solver().numVariables());
                        constraintCount = Math.max(
                                constraintCount, oneModel.solver().numConstraints());
                        if (oneStage.feasible()) {
                            best = oneStage.snapshot();
                        }
                        shapeOptimal = oneStage.status() == MPSolver.ResultStatus.OPTIMAL;
                    }
                }
            }
            shapeSolveMs = System.currentTimeMillis() - shapeStartedAt;
        }

        double relativeGap = relativeGap(best.groups(), groupStage.bestBound());
        int exactGroups = groupOptimal ? best.groups() : -1;
        int exactExtra = groupOptimal ? exactGroups - patternCount : -1;
        return new Analysis(
                groupOptimal ? Status.OPTIMAL : Status.FEASIBLE,
                groupChoice.name(),
                groupStage.status(),
                oddStatus,
                oneStatus,
                groupOptimal,
                shapeOptimal,
                patternCount,
                best.groups(),
                provenLowerBound,
                exactGroups,
                exactExtra,
                best.oddGroups(),
                best.oneGroups(),
                data.columns().size(),
                variableCount,
                constraintCount,
                totalNodes,
                groupStage.bestBound(),
                relativeGap,
                enumerationMs,
                groupSolveMs,
                shapeSolveMs,
                System.currentTimeMillis() - startedAt,
                splitWitnesses(data, best));
    }

    /**
     * Checks a final configuration-group threshold without conflating it with
     * pattern-usage parity.
     *
     * <p>A feasible result is an existence proof. A timeout without a solution
     * remains unknown and must never be interpreted as infeasible.</p>
     */
    static ThresholdAnalysis checkThreshold(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> orderItems,
            int maxGroups,
            int exactOddGroups,
            int exactOneGroups,
            Options options) {
        Objects.requireNonNull(options, "options");
        if (maxGroups <= 0) {
            throw new IllegalArgumentException("maxGroups must be positive");
        }
        if (exactOddGroups < 0 || exactOneGroups < 0) {
            throw new IllegalArgumentException(
                    "exactOddGroups and exactOneGroups must not be negative");
        }

        long startedAt = System.currentTimeMillis();
        ModelData data;
        try {
            data = prepare(solution, orderItems, options.maxConfigurations());
        } catch (ConfigurationLimitExceeded limit) {
            return emptyThresholdAnalysis(
                    ThresholdStatus.ENUMERATION_LIMIT,
                    MPSolver.ResultStatus.NOT_SOLVED,
                    limit.patternCount(),
                    limit.configurationCount(),
                    maxGroups,
                    exactOddGroups,
                    exactOneGroups,
                    System.currentTimeMillis() - startedAt);
        }

        long enumerationMs = System.currentTimeMillis() - startedAt;
        SolverChoice choice = createSolver(options);
        if (choice == null) {
            return emptyThresholdAnalysis(
                    ThresholdStatus.SOLVER_UNAVAILABLE,
                    MPSolver.ResultStatus.NOT_SOLVED,
                    data.patterns().size(),
                    data.columns().size(),
                    maxGroups,
                    exactOddGroups,
                    exactOneGroups,
                    enumerationMs);
        }

        Model model = buildModel(
                choice, data, Level.ONE, null, null, null, options);
        MPConstraint groupLimit = model.solver().makeConstraint(
                0, maxGroups, "threshold_groups");
        for (MPVariable variable : model.active()) {
            groupLimit.setCoefficient(variable, 1);
        }
        MPConstraint oddTarget = model.solver().makeConstraint(
                exactOddGroups, exactOddGroups, "threshold_odd");
        for (MPVariable variable : model.odd()) {
            oddTarget.setCoefficient(variable, 1);
        }
        MPConstraint oneTarget = model.solver().makeConstraint(
                exactOneGroups, exactOneGroups, "threshold_one");
        for (MPVariable variable : model.one()) {
            oneTarget.setCoefficient(variable, 1);
        }

        StageResult stage = solve(model, options.groupTimeLimitMs());
        Snapshot snapshot = stage.snapshot();
        ThresholdStatus status = mapThresholdStatus(stage.status());
        return new ThresholdAnalysis(
                status,
                choice.name(),
                stage.status(),
                maxGroups,
                exactOddGroups,
                exactOneGroups,
                data.patterns().size(),
                snapshot == null ? -1 : snapshot.groups(),
                snapshot == null ? -1 : snapshot.oddGroups(),
                snapshot == null ? -1 : snapshot.oneGroups(),
                data.columns().size(),
                model.solver().numVariables(),
                model.solver().numConstraints(),
                stage.nodes(),
                enumerationMs,
                stage.elapsedMs(),
                System.currentTimeMillis() - startedAt,
                snapshot == null ? List.of() : splitWitnesses(data, snapshot));
    }

    private static ModelData prepare(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> orderItems,
            long maxConfigurations) {
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(orderItems, "orderItems");
        if (solution.isEmpty()) {
            throw new IllegalArgumentException("solution must not be empty");
        }
        if (orderItems.isEmpty()) {
            throw new IllegalArgumentException("orderItems must not be empty");
        }

        Map<String, ActivePattern> bySignature = new TreeMap<>();
        for (Map.Entry<PatternCandidate, Integer> entry : solution.entrySet()) {
            PatternCandidate pattern = Objects.requireNonNull(
                    entry.getKey(), "solution contains a null pattern");
            Integer usageValue = Objects.requireNonNull(
                    entry.getValue(), "solution contains a null usage");
            int usage = usageValue;
            if (usage <= 0) {
                throw new IllegalArgumentException(
                        "pattern usage must be positive: " + pattern.signature()
                                + "=" + usage);
            }
            for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                if (cut.getValue() == null || cut.getValue() <= 0) {
                    throw new IllegalArgumentException(
                            "pattern coefficient must be positive: " + pattern.signature());
                }
            }
            String signature = pattern.signature();
            ActivePattern previous = bySignature.put(
                    signature, new ActivePattern(pattern, usage, signature));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate pattern signature: " + signature);
            }
        }
        List<ActivePattern> patterns = List.copyOf(bySignature.values());

        Map<DemandKey, Integer> demandByOrder = new TreeMap<>(DEMAND_ORDER);
        for (SolverOrderItem item : orderItems) {
            if (item == null) {
                throw new IllegalArgumentException("orderItems contains null");
            }
            if (item.getDemand() <= 0) {
                throw new IllegalArgumentException(
                        "order demand must be positive: width=" + item.getWidth()
                                + ", message=" + item.getMessageText()
                                + ", demand=" + item.getDemand());
            }
            DemandKey key = new DemandKey(
                    item.getWidth(), Objects.toString(item.getMessageText(), "").trim());
            demandByOrder.merge(key, item.getDemand(), Math::addExact);
        }
        List<Map.Entry<DemandKey, Integer>> demandEntries =
                List.copyOf(demandByOrder.entrySet());
        List<Demand> demands = new ArrayList<>(demandEntries.size());
        Map<DemandKey, Integer> orderIndex = new LinkedHashMap<>();
        Map<Integer, List<String>> messagesByWidth = new TreeMap<>();
        Map<Integer, Integer> widthDemand = new TreeMap<>();
        for (int index = 0; index < demandEntries.size(); index++) {
            Map.Entry<DemandKey, Integer> entry = demandEntries.get(index);
            demands.add(new Demand(entry.getKey(), entry.getValue()));
            orderIndex.put(entry.getKey(), index);
            messagesByWidth
                    .computeIfAbsent(entry.getKey().width(), ignored -> new ArrayList<>())
                    .add(entry.getKey().message());
            widthDemand.merge(
                    entry.getKey().width(), entry.getValue(), Math::addExact);
        }
        messagesByWidth.replaceAll((width, messages) -> List.copyOf(messages));

        Map<Integer, Integer> production = new TreeMap<>();
        for (ActivePattern active : patterns) {
            for (Map.Entry<Integer, Integer> cut : active.pattern().getPattern().entrySet()) {
                int amount = Math.multiplyExact(cut.getValue(), active.usage());
                production.merge(cut.getKey(), amount, Math::addExact);
            }
        }
        if (!production.equals(widthDemand)) {
            throw new IllegalArgumentException(
                    "order-compatibility analysis requires exact width production: demand="
                            + widthDemand + ", production=" + production);
        }

        BigInteger expectedTotal = BigInteger.ZERO;
        List<Integer> expectedPerPattern = new ArrayList<>(patterns.size());
        BigInteger cap = BigInteger.valueOf(maxConfigurations);
        for (ActivePattern active : patterns) {
            BigInteger patternConfigurations = BigInteger.ONE;
            for (Map.Entry<Integer, Integer> cut
                    : new TreeMap<>(active.pattern().getPattern()).entrySet()) {
                List<String> messages = messagesByWidth.get(cut.getKey());
                if (messages == null || messages.isEmpty()) {
                    throw new IllegalArgumentException(
                            "pattern width has no order messages: width=" + cut.getKey()
                                    + ", pattern=" + active.signature());
                }
                patternConfigurations = patternConfigurations.multiply(
                        combinationsWithRepetition(messages.size(), cut.getValue()));
            }
            expectedTotal = expectedTotal.add(patternConfigurations);
            if (patternConfigurations.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                    || expectedTotal.compareTo(cap) > 0
                    || expectedTotal.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new ConfigurationLimitExceeded(
                        patterns.size(), saturatingLong(expectedTotal));
            }
            expectedPerPattern.add(patternConfigurations.intValueExact());
        }

        List<Column> columns = new ArrayList<>(expectedTotal.intValueExact());
        List<List<Integer>> columnsByPattern = new ArrayList<>(patterns.size());
        for (int patternIndex = 0; patternIndex < patterns.size(); patternIndex++) {
            ActivePattern active = patterns.get(patternIndex);
            List<Integer> patternColumns = new ArrayList<>(expectedPerPattern.get(patternIndex));
            enumeratePattern(
                    patternIndex,
                    active,
                    messagesByWidth,
                    orderIndex,
                    demands.size(),
                    columns,
                    patternColumns);
            columnsByPattern.add(List.copyOf(patternColumns));
        }
        if (columns.size() != expectedTotal.intValueExact()) {
            throw new IllegalStateException(
                    "configuration enumeration mismatch: expected=" + expectedTotal
                            + ", actual=" + columns.size());
        }
        return new ModelData(
                patterns,
                List.copyOf(demands),
                List.copyOf(columns),
                List.copyOf(columnsByPattern));
    }

    private static void enumeratePattern(
            int patternIndex,
            ActivePattern active,
            Map<Integer, List<String>> messagesByWidth,
            Map<DemandKey, Integer> orderIndex,
            int demandCount,
            List<Column> columns,
            List<Integer> patternColumns) {
        List<Integer> widths = new ArrayList<>(active.pattern().getPattern().keySet());
        Collections.sort(widths);
        List<List<List<String>>> widthChoices = new ArrayList<>(widths.size());
        for (int width : widths) {
            int slots = active.pattern().getPattern().get(width);
            widthChoices.add(multisets(messagesByWidth.get(width), slots));
        }
        enumerateCartesian(
                patternIndex,
                active,
                widths,
                widthChoices,
                0,
                new LinkedHashMap<>(),
                orderIndex,
                demandCount,
                columns,
                patternColumns);
    }

    private static void enumerateCartesian(
            int patternIndex,
            ActivePattern active,
            List<Integer> widths,
            List<List<List<String>>> widthChoices,
            int depth,
            Map<Integer, List<String>> current,
            Map<DemandKey, Integer> orderIndex,
            int demandCount,
            List<Column> columns,
            List<Integer> patternColumns) {
        if (depth == widths.size()) {
            int[] contributions = new int[demandCount];
            for (Map.Entry<Integer, List<String>> entry : current.entrySet()) {
                for (String message : entry.getValue()) {
                    Integer index = orderIndex.get(new DemandKey(entry.getKey(), message));
                    if (index == null) {
                        throw new IllegalStateException(
                                "configuration references an unknown order: width="
                                        + entry.getKey() + ", message=" + message);
                    }
                    contributions[index]++;
                }
            }
            Map<Integer, List<String>> config = deepCopyConfig(current);
            String signature = configurationSignature(config);
            patternColumns.add(columns.size());
            columns.add(new Column(
                    patternIndex, active.signature(), config, contributions, signature));
            return;
        }

        int width = widths.get(depth);
        for (List<String> choice : widthChoices.get(depth)) {
            current.put(width, choice);
            enumerateCartesian(
                    patternIndex,
                    active,
                    widths,
                    widthChoices,
                    depth + 1,
                    current,
                    orderIndex,
                    demandCount,
                    columns,
                    patternColumns);
        }
        current.remove(width);
    }

    private static List<List<String>> multisets(List<String> messages, int slots) {
        List<List<String>> result = new ArrayList<>();
        chooseMultiset(messages, slots, 0, new ArrayList<>(), result);
        return List.copyOf(result);
    }

    private static void chooseMultiset(
            List<String> messages,
            int slots,
            int start,
            List<String> current,
            List<List<String>> result) {
        if (current.size() == slots) {
            result.add(List.copyOf(current));
            return;
        }
        for (int index = start; index < messages.size(); index++) {
            current.add(messages.get(index));
            chooseMultiset(messages, slots, index, current, result);
            current.remove(current.size() - 1);
        }
    }

    private static BigInteger combinationsWithRepetition(int messages, int slots) {
        if (messages <= 0 || slots <= 0) {
            throw new IllegalArgumentException(
                    "messages and slots must be positive: messages=" + messages
                            + ", slots=" + slots);
        }
        int n = messages + slots - 1;
        int k = Math.min(slots, n - slots);
        BigInteger result = BigInteger.ONE;
        for (int index = 1; index <= k; index++) {
            result = result
                    .multiply(BigInteger.valueOf(n - k + index))
                    .divide(BigInteger.valueOf(index));
        }
        return result;
    }

    private static Model buildModel(
            SolverChoice choice,
            ModelData data,
            Level level,
            Integer fixedGroups,
            Integer fixedOdd,
            Snapshot hint,
            Options options) {
        MPSolver solver = choice.solver();
        int columnCount = data.columns().size();
        MPVariable[] count = new MPVariable[columnCount];
        MPVariable[] active = new MPVariable[columnCount];
        MPVariable[] half = level.includesOdd() ? new MPVariable[columnCount] : null;
        MPVariable[] odd = level.includesOdd() ? new MPVariable[columnCount] : null;
        MPVariable[] one = level.includesOne() ? new MPVariable[columnCount] : null;

        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            Column column = data.columns().get(columnIndex);
            int usage = data.patterns().get(column.patternIndex()).usage();
            count[columnIndex] = solver.makeIntVar(0, usage, "n_" + columnIndex);
            active[columnIndex] = solver.makeBoolVar("y_" + columnIndex);

            MPConstraint upper = solver.makeConstraint(
                    -MPSolver.infinity(), 0, "upper_" + columnIndex);
            upper.setCoefficient(count[columnIndex], 1);
            upper.setCoefficient(active[columnIndex], -usage);

            // Keep activation linkage identical in every lexicographic stage.
            // This strengthens the GROUPS relaxation without changing the
            // optimal integer configuration or objective semantics.
            MPConstraint lower = solver.makeConstraint(
                    -MPSolver.infinity(), 0, "lower_" + columnIndex);
            lower.setCoefficient(active[columnIndex], 1);
            lower.setCoefficient(count[columnIndex], -1);

            if (level.includesOdd()) {
                half[columnIndex] = solver.makeIntVar(
                        0, usage / 2, "half_" + columnIndex);
                odd[columnIndex] = solver.makeBoolVar("odd_" + columnIndex);
                MPConstraint parity = solver.makeConstraint(
                        0, 0, "parity_" + columnIndex);
                parity.setCoefficient(count[columnIndex], 1);
                parity.setCoefficient(half[columnIndex], -2);
                parity.setCoefficient(odd[columnIndex], -1);
            }

            if (level.includesOne()) {
                one[columnIndex] = solver.makeBoolVar("one_" + columnIndex);

                MPConstraint oneLower = solver.makeConstraint(
                        0, MPSolver.infinity(), "one_lower_" + columnIndex);
                oneLower.setCoefficient(count[columnIndex], 1);
                oneLower.setCoefficient(active[columnIndex], -2);
                oneLower.setCoefficient(one[columnIndex], 1);

                MPConstraint oneUpper = solver.makeConstraint(
                        -MPSolver.infinity(), usage, "one_upper_" + columnIndex);
                oneUpper.setCoefficient(count[columnIndex], 1);
                oneUpper.setCoefficient(one[columnIndex], usage - 1);

                MPConstraint oneActive = solver.makeConstraint(
                        -MPSolver.infinity(), 0, "one_active_" + columnIndex);
                oneActive.setCoefficient(one[columnIndex], 1);
                oneActive.setCoefficient(active[columnIndex], -1);
            }
        }

        for (int patternIndex = 0;
                patternIndex < data.patterns().size();
                patternIndex++) {
            ActivePattern pattern = data.patterns().get(patternIndex);
            MPConstraint usage = solver.makeConstraint(
                    pattern.usage(), pattern.usage(), "pattern_" + patternIndex);
            for (int columnIndex : data.columnsByPattern().get(patternIndex)) {
                usage.setCoefficient(count[columnIndex], 1);
            }
        }

        for (int demandIndex = 0; demandIndex < data.demands().size(); demandIndex++) {
            Demand demand = data.demands().get(demandIndex);
            MPConstraint exact = solver.makeConstraint(
                    demand.amount(), demand.amount(), "demand_" + demandIndex);
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                int contribution = data.columns().get(columnIndex)
                        .contributions()[demandIndex];
                if (contribution > 0) {
                    exact.setCoefficient(count[columnIndex], contribution);
                }
            }
        }

        if (fixedGroups != null) {
            MPConstraint groups = solver.makeConstraint(
                    fixedGroups, fixedGroups, "fixed_groups");
            for (MPVariable variable : active) {
                groups.setCoefficient(variable, 1);
            }
        }
        if (fixedOdd != null) {
            MPConstraint odds = solver.makeConstraint(
                    fixedOdd, fixedOdd, "fixed_odd");
            for (MPVariable variable : odd) {
                odds.setCoefficient(variable, 1);
            }
        }

        MPObjective objective = solver.objective();
        MPVariable[] objectiveVariables = switch (level) {
            case GROUPS -> active;
            case ODD -> odd;
            case ONE -> one;
        };
        for (MPVariable variable : objectiveVariables) {
            objective.setCoefficient(variable, 1);
        }
        objective.setMinimization();

        if (hint != null) {
            applyHint(solver, count, active, half, odd, one, hint);
        }
        configureSolver(choice, options);
        return new Model(solver, count, active, half, odd, one);
    }

    private static void applyHint(
            MPSolver solver,
            MPVariable[] count,
            MPVariable[] active,
            MPVariable[] half,
            MPVariable[] odd,
            MPVariable[] one,
            Snapshot hint) {
        List<MPVariable> variables = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < count.length; index++) {
            int cars = hint.counts()[index];
            variables.add(count[index]);
            values.add((double) cars);
            variables.add(active[index]);
            values.add(cars > 0 ? 1.0 : 0.0);
            if (half != null) {
                variables.add(half[index]);
                values.add((double) (cars / 2));
                variables.add(odd[index]);
                values.add((double) (cars % 2));
            }
            if (one != null) {
                variables.add(one[index]);
                values.add(cars == 1 ? 1.0 : 0.0);
            }
        }
        solver.setHint(
                variables.toArray(MPVariable[]::new),
                values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    private static StageResult solve(Model model, long timeLimitMs) {
        if (timeLimitMs <= 0) {
            return new StageResult(
                    MPSolver.ResultStatus.NOT_SOLVED,
                    null,
                    Double.NaN,
                    Double.NaN,
                    0L,
                    -1L);
        }
        model.solver().setTimeLimit(timeLimitMs);
        long startedAt = System.currentTimeMillis();
        MPSolver.ResultStatus status = model.solver().solve();
        long elapsedMs = System.currentTimeMillis() - startedAt;
        boolean feasible = status == MPSolver.ResultStatus.OPTIMAL
                || status == MPSolver.ResultStatus.FEASIBLE;
        Snapshot snapshot = feasible ? snapshot(model.count()) : null;
        return new StageResult(
                status,
                snapshot,
                feasible ? safeObjectiveValue(model.solver().objective()) : Double.NaN,
                safeBestBound(model.solver().objective()),
                elapsedMs,
                safeNodes(model.solver()));
    }

    private static Snapshot snapshot(MPVariable[] count) {
        int[] values = new int[count.length];
        int groups = 0;
        int oddGroups = 0;
        int oneGroups = 0;
        for (int index = 0; index < count.length; index++) {
            int cars = (int) Math.round(count[index].solutionValue());
            if (cars < 0) {
                throw new IllegalStateException("negative configuration usage at " + index);
            }
            values[index] = cars;
            if (cars > 0) {
                groups++;
                if (cars % 2 != 0) {
                    oddGroups++;
                }
                if (cars == 1) {
                    oneGroups++;
                }
            }
        }
        return new Snapshot(values, groups, oddGroups, oneGroups);
    }

    private static List<PatternSplit> splitWitnesses(ModelData data, Snapshot snapshot) {
        List<PatternSplit> splits = new ArrayList<>();
        for (int patternIndex = 0;
                patternIndex < data.patterns().size();
                patternIndex++) {
            List<ConfigUse> uses = new ArrayList<>();
            for (int columnIndex : data.columnsByPattern().get(patternIndex)) {
                int cars = snapshot.counts()[columnIndex];
                if (cars > 0) {
                    Column column = data.columns().get(columnIndex);
                    uses.add(new ConfigUse(
                            cars, column.stationConfig(), column.configurationSignature()));
                }
            }
            if (uses.size() <= 1) {
                continue;
            }
            uses.sort(Comparator.comparingInt(ConfigUse::cars).reversed()
                    .thenComparing(ConfigUse::configurationSignature));

            ActivePattern pattern = data.patterns().get(patternIndex);
            Set<Integer> varyingWidths = new LinkedHashSet<>();
            Map<Integer, List<String>> varyingMessages = new LinkedHashMap<>();
            for (int width : new TreeSet<>(pattern.pattern().getPattern().keySet())) {
                Set<List<String>> configurations = new LinkedHashSet<>();
                Set<String> messages = new TreeSet<>();
                for (ConfigUse use : uses) {
                    List<String> widthMessages = use.stationConfig()
                            .getOrDefault(width, List.of());
                    configurations.add(widthMessages);
                    messages.addAll(widthMessages);
                }
                if (configurations.size() > 1) {
                    varyingWidths.add(width);
                    varyingMessages.put(width, List.copyOf(messages));
                }
            }
            splits.add(new PatternSplit(
                    pattern.signature(),
                    pattern.usage(),
                    List.copyOf(uses),
                    Set.copyOf(varyingWidths),
                    Map.copyOf(varyingMessages)));
        }
        splits.sort(Comparator.comparing(PatternSplit::patternSignature));
        return List.copyOf(splits);
    }

    private static SolverChoice createSolver(Options options) {
        MPSolver solver = null;
        try {
            solver = MPSolver.createSolver("SCIP");
        } catch (RuntimeException ignored) {
            // Optional fallback below.
        }
        if (solver != null) {
            return new SolverChoice(solver, "SCIP");
        }
        if (!options.allowCbcFallback()) {
            return null;
        }
        try {
            solver = MPSolver.createSolver("CBC");
        } catch (RuntimeException ignored) {
            solver = null;
        }
        return solver == null ? null : new SolverChoice(solver, "CBC");
    }

    private static void configureSolver(SolverChoice choice, Options options) {
        try {
            choice.solver().setNumThreads(1);
        } catch (RuntimeException ignored) {
            // Some fallback backends do not expose thread control.
        }
        if (!"SCIP".equals(choice.name())) {
            return;
        }
        String parameters = SCIP_DETERMINISTIC_PARAMS;
        if (options.nodeLimit() > 0) {
            parameters += "limits/nodes = " + options.nodeLimit() + "\n";
        }
        choice.solver().setSolverSpecificParametersAsString(parameters);
    }

    private static Analysis emptyAnalysis(
            Status status,
            int patternCount,
            long configurationCount,
            long elapsedMs) {
        return new Analysis(
                status,
                "",
                MPSolver.ResultStatus.NOT_SOLVED,
                MPSolver.ResultStatus.NOT_SOLVED,
                MPSolver.ResultStatus.NOT_SOLVED,
                false,
                false,
                patternCount,
                -1,
                patternCount,
                -1,
                -1,
                -1,
                -1,
                configurationCount,
                0,
                0,
                -1L,
                Double.NaN,
                Double.NaN,
                elapsedMs,
                0L,
                0L,
                elapsedMs,
                List.of());
    }

    private static ThresholdAnalysis emptyThresholdAnalysis(
            ThresholdStatus status,
            MPSolver.ResultStatus solverStatus,
            int patternCount,
            long configurationCount,
            int maxGroups,
            int exactOddGroups,
            int exactOneGroups,
            long elapsedMs) {
        return new ThresholdAnalysis(
                status,
                "",
                solverStatus,
                maxGroups,
                exactOddGroups,
                exactOneGroups,
                patternCount,
                -1,
                -1,
                -1,
                configurationCount,
                0,
                0,
                -1L,
                elapsedMs,
                0L,
                elapsedMs,
                List.of());
    }

    private static Map<Integer, List<String>> deepCopyConfig(
            Map<Integer, List<String>> source) {
        Map<Integer, List<String>> copy = new LinkedHashMap<>();
        new TreeMap<>(source).forEach((width, messages) ->
                copy.put(width, List.copyOf(messages)));
        return Collections.unmodifiableMap(copy);
    }

    private static String configurationSignature(Map<Integer, List<String>> config) {
        List<String> parts = new ArrayList<>();
        new TreeMap<>(config).forEach((width, messages) ->
                parts.add(width + "=[" + String.join("+", messages) + "]"));
        return String.join(";", parts);
    }

    private static long safeDeadline(long now, long durationMs) {
        if (durationMs >= Long.MAX_VALUE - now) {
            return Long.MAX_VALUE;
        }
        return now + durationMs;
    }

    private static long remainingMs(long deadline) {
        return Math.max(0L, deadline - System.currentTimeMillis());
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

    private static int lowerBound(int patternCount, double solverBound) {
        if (!Double.isFinite(solverBound)) {
            return patternCount;
        }
        return Math.max(patternCount, (int) Math.ceil(solverBound - EPS));
    }

    private static double relativeGap(int feasibleGroups, double bestBound) {
        if (!Double.isFinite(bestBound) || feasibleGroups < 0) {
            return Double.NaN;
        }
        return Math.max(0.0,
                (feasibleGroups - bestBound) / Math.max(1.0, Math.abs(feasibleGroups)));
    }

    private static double safeObjectiveValue(MPObjective objective) {
        try {
            return objective.value();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static double safeBestBound(MPObjective objective) {
        try {
            return objective.bestBound();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static long safeNodes(MPSolver solver) {
        try {
            return solver.nodes();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static long saturatingLong(BigInteger value) {
        BigInteger maximum = BigInteger.valueOf(Long.MAX_VALUE);
        return value.compareTo(maximum) > 0 ? Long.MAX_VALUE : value.longValueExact();
    }

    private static Status mapStatus(MPSolver.ResultStatus status) {
        return switch (status) {
            case OPTIMAL -> Status.OPTIMAL;
            case FEASIBLE -> Status.FEASIBLE;
            case INFEASIBLE -> Status.INFEASIBLE;
            case NOT_SOLVED -> Status.NOT_SOLVED;
            default -> Status.ABNORMAL;
        };
    }

    static ThresholdStatus mapThresholdStatus(MPSolver.ResultStatus status) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case OPTIMAL, FEASIBLE -> ThresholdStatus.FEASIBLE;
            case INFEASIBLE -> ThresholdStatus.INFEASIBLE;
            case NOT_SOLVED -> ThresholdStatus.UNKNOWN;
            default -> ThresholdStatus.ABNORMAL;
        };
    }

    enum Status {
        OPTIMAL,
        FEASIBLE,
        INFEASIBLE,
        NOT_SOLVED,
        ABNORMAL,
        ENUMERATION_LIMIT,
        SOLVER_UNAVAILABLE
    }

    enum ThresholdStatus {
        FEASIBLE,
        INFEASIBLE,
        UNKNOWN,
        ABNORMAL,
        ENUMERATION_LIMIT,
        SOLVER_UNAVAILABLE
    }

    record Options(
            long groupTimeLimitMs,
            long shapeTimeLimitMs,
            long maxConfigurations,
            long nodeLimit,
            boolean allowCbcFallback) {

        Options {
            if (groupTimeLimitMs <= 0) {
                throw new IllegalArgumentException("groupTimeLimitMs must be positive");
            }
            if (shapeTimeLimitMs < 0) {
                throw new IllegalArgumentException("shapeTimeLimitMs must not be negative");
            }
            if (maxConfigurations <= 0) {
                throw new IllegalArgumentException("maxConfigurations must be positive");
            }
            if (nodeLimit == 0 || nodeLimit < -1) {
                throw new IllegalArgumentException("nodeLimit must be -1 or positive");
            }
        }

        static Options regressionDefaults() {
            return new Options(180_000L, 60_000L, 100_000L, -1L, false);
        }
    }

    record ConfigUse(
            int cars,
            Map<Integer, List<String>> stationConfig,
            String configurationSignature) {

        ConfigUse {
            if (cars <= 0) {
                throw new IllegalArgumentException("cars must be positive");
            }
            stationConfig = deepCopyConfig(stationConfig);
            configurationSignature = Objects.requireNonNull(configurationSignature);
        }
    }

    record PatternSplit(
            String patternSignature,
            int patternUsage,
            List<ConfigUse> configurations,
            Set<Integer> varyingWidths,
            Map<Integer, List<String>> varyingMessages) {

        PatternSplit {
            patternSignature = Objects.requireNonNull(patternSignature);
            configurations = List.copyOf(configurations);
            varyingWidths = Set.copyOf(varyingWidths);
            Map<Integer, List<String>> messages = new TreeMap<>();
            varyingMessages.forEach((width, values) ->
                    messages.put(width, List.copyOf(values)));
            varyingMessages = Collections.unmodifiableMap(messages);
        }
    }

    record Analysis(
            Status status,
            String solverName,
            MPSolver.ResultStatus groupSolverStatus,
            MPSolver.ResultStatus oddSolverStatus,
            MPSolver.ResultStatus oneSolverStatus,
            boolean groupOptimal,
            boolean shapeOptimal,
            int patternCount,
            int feasibleGroups,
            int provenGroupLowerBound,
            int exactMinimumGroups,
            int exactExtraGroups,
            int oddGroups,
            int oneGroups,
            long configurationCount,
            int variableCount,
            int constraintCount,
            long nodes,
            double groupBestBound,
            double relativeGap,
            long enumerationMs,
            long groupSolveMs,
            long shapeSolveMs,
            long totalElapsedMs,
            List<PatternSplit> splitWitnesses) {

        Analysis {
            status = Objects.requireNonNull(status);
            solverName = Objects.requireNonNull(solverName);
            groupSolverStatus = Objects.requireNonNull(groupSolverStatus);
            oddSolverStatus = Objects.requireNonNull(oddSolverStatus);
            oneSolverStatus = Objects.requireNonNull(oneSolverStatus);
            splitWitnesses = List.copyOf(splitWitnesses);
        }
    }

    record ThresholdAnalysis(
            ThresholdStatus status,
            String solverName,
            MPSolver.ResultStatus solverStatus,
            int maxGroups,
            int exactOddGroups,
            int exactOneGroups,
            int patternCount,
            int feasibleGroups,
            int feasibleOddGroups,
            int feasibleOneGroups,
            long configurationCount,
            int variableCount,
            int constraintCount,
            long nodes,
            long enumerationMs,
            long solveMs,
            long totalElapsedMs,
            List<PatternSplit> splitWitnesses) {

        ThresholdAnalysis {
            status = Objects.requireNonNull(status);
            solverName = Objects.requireNonNull(solverName);
            solverStatus = Objects.requireNonNull(solverStatus);
            splitWitnesses = List.copyOf(splitWitnesses);
        }

        boolean feasible() {
            return status == ThresholdStatus.FEASIBLE;
        }
    }

    private enum Level {
        GROUPS,
        ODD,
        ONE;

        boolean includesOdd() {
            return this != GROUPS;
        }

        boolean includesOne() {
            return this == ONE;
        }
    }

    private record DemandKey(int width, String message) {
    }

    private record Demand(DemandKey key, int amount) {
    }

    private record ActivePattern(
            PatternCandidate pattern,
            int usage,
            String signature) {
    }

    private record Column(
            int patternIndex,
            String patternSignature,
            Map<Integer, List<String>> stationConfig,
            int[] contributions,
            String configurationSignature) {
    }

    private record ModelData(
            List<ActivePattern> patterns,
            List<Demand> demands,
            List<Column> columns,
            List<List<Integer>> columnsByPattern) {
    }

    private record SolverChoice(MPSolver solver, String name) {
    }

    private record Model(
            MPSolver solver,
            MPVariable[] count,
            MPVariable[] active,
            MPVariable[] half,
            MPVariable[] odd,
            MPVariable[] one) {
    }

    private record Snapshot(
            int[] counts,
            int groups,
            int oddGroups,
            int oneGroups) {
    }

    private record StageResult(
            MPSolver.ResultStatus status,
            Snapshot snapshot,
            double objectiveValue,
            double bestBound,
            long elapsedMs,
            long nodes) {

        boolean feasible() {
            return status == MPSolver.ResultStatus.OPTIMAL
                    || status == MPSolver.ResultStatus.FEASIBLE;
        }
    }

    private static final class ConfigurationLimitExceeded extends RuntimeException {
        private final int patternCount;
        private final long configurationCount;

        private ConfigurationLimitExceeded(int patternCount, long configurationCount) {
            this.patternCount = patternCount;
            this.configurationCount = configurationCount;
        }

        int patternCount() {
            return patternCount;
        }

        long configurationCount() {
            return configurationCount;
        }
    }
}
