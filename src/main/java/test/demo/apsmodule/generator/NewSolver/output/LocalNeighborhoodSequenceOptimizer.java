package test.demo.apsmodule.generator.NewSolver.output;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Experimental local neighbourhood search for sequence-group reduction.
 *
 * <p>Disabled by default. Enable with {@code -Dcutting.lns.enabled=true}.
 * The first version keeps cars and waste fixed inside a closed neighbourhood and
 * solves a small set-partitioning model over full-roll configuration columns.
 */
public class LocalNeighborhoodSequenceOptimizer {

    private static final Logger log = LoggerFactory.getLogger(LocalNeighborhoodSequenceOptimizer.class);

    private static final int MAX_FREE_ORDERS = 8;
    private static final int MAX_FREE_PATTERNS = 10;
    private static final int MAX_COLUMNS = 2000;
    private static final int DEFAULT_MAX_ITERATIONS = 5;
    private static final int DEFAULT_MAX_NEIGHBORHOODS = 30;
    private static final long DEFAULT_TIME_LIMIT_MS = 5000L;
    private static final int MAX_SEED_COUNT = 3;
    private static final int COLUMN_ENUMERATION_GUARD = 20000;
    private static final String SCIP_DETERMINISTIC_PARAMS =
            "randomization/randomseedshift = 0\n"
          + "randomization/permutationseed = 0\n"
          + "randomization/lpseed = 0\n";

    private static boolean orToolsLoaded;

    private final SolverParameters params;

    public LocalNeighborhoodSequenceOptimizer(SolverParameters params) {
        this.params = params;
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean("cutting.lns.enabled");
    }

    public LnsResult improve(List<CuttingInstruction> originalInstructions,
            List<SolverOrderItem> groupItems) {
        if (originalInstructions == null || originalInstructions.isEmpty()
                || groupItems == null || groupItems.isEmpty()) {
            return LnsResult.notImproved(originalInstructions, "empty-input");
        }
        if (!loadOrTools()) {
            return LnsResult.notImproved(originalInstructions, "ortools-unavailable");
        }

        List<CuttingInstruction> current = cloneInstructions(originalInstructions);
        int originalGroups = SequenceGroupPostProcessor.computeGroupStats(current).groups();
        int originalCars = totalCars(current);
        int originalWaste = totalWaste(current);
        Map<String, Integer> originalDemand = countAssignmentsByDemandKey(current);

        int maxIterations = Integer.getInteger("cutting.lns.maxIterations", DEFAULT_MAX_ITERATIONS);
        int maxNeighborhoods = Integer.getInteger("cutting.lns.maxNeighborhoods", DEFAULT_MAX_NEIGHBORHOODS);
        boolean improved = false;
        String lastReason = "no-improving-neighborhood";

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            int beforeGroups = SequenceGroupPostProcessor.computeGroupStats(current).groups();
            List<RollRecord> rolls = decompose(current);
            List<Neighborhood> neighborhoods = buildNeighborhoods(rolls, maxNeighborhoods);
            boolean acceptedThisIteration = false;

            for (Neighborhood neighborhood : neighborhoods) {
                SolveAttempt attempt = solveNeighborhood(neighborhood);
                lastReason = attempt.reason();
                if (!attempt.feasible()) {
                    continue;
                }

                List<CuttingInstruction> candidate = rebuildWithNeighborhoodSolution(rolls, neighborhood, attempt);
                int candidateCars = totalCars(candidate);
                int candidateWaste = totalWaste(candidate);
                Map<String, Integer> candidateDemand = countAssignmentsByDemandKey(candidate);
                int afterGroups = SequenceGroupPostProcessor.computeGroupStats(candidate).groups();

                if (candidateCars == originalCars
                        && candidateWaste == originalWaste
                        && originalDemand.equals(candidateDemand)
                        && afterGroups < beforeGroups) {
                    log.info("LNS accepted: groups {} -> {}, waste={} cars={}, seeds={}, orders={}, patterns={}, columns={}, status={}, elapsed={}ms",
                            beforeGroups,
                            afterGroups,
                            candidateWaste,
                            candidateCars,
                            neighborhood.seedKeys(),
                            neighborhood.freeDemand().keySet(),
                            neighborhood.patterns().size(),
                            attempt.columns().size(),
                            attempt.status(),
                            attempt.elapsedMs());
                    current = candidate;
                    improved = true;
                    acceptedThisIteration = true;
                    break;
                }

                log.info("LNS rejected: groups {} -> {}, cars {}->{}, waste {}->{}, demandOk={}, seeds={}, columns={}, status={}",
                        beforeGroups,
                        afterGroups,
                        originalCars,
                        candidateCars,
                        originalWaste,
                        candidateWaste,
                        originalDemand.equals(candidateDemand),
                        neighborhood.seedKeys(),
                        attempt.columns().size(),
                        attempt.status());
            }

            if (!acceptedThisIteration) {
                break;
            }
        }

        int finalGroups = SequenceGroupPostProcessor.computeGroupStats(current).groups();
        if (improved && finalGroups < originalGroups) {
            return new LnsResult(true, current, originalGroups, finalGroups, "improved");
        }
        return LnsResult.notImproved(originalInstructions, lastReason);
    }

    private static synchronized boolean loadOrTools() {
        if (orToolsLoaded) {
            return true;
        }
        try {
            Loader.loadNativeLibraries();
            orToolsLoaded = true;
            return true;
        } catch (Exception e) {
            log.warn("OR-Tools load failed for LNS", e);
            return false;
        }
    }

    private List<Neighborhood> buildNeighborhoods(List<RollRecord> rolls, int maxNeighborhoods) {
        Map<String, Set<String>> configsByKey = new LinkedHashMap<>();
        for (RollRecord roll : rolls) {
            for (String key : roll.demandCounts().keySet()) {
                configsByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                        .add(roll.configSignature());
            }
        }

        List<String> fragmentedKeys = configsByKey.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .sorted(Comparator
                        .<Map.Entry<String, Set<String>>>comparingInt(entry -> entry.getValue().size()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();

        List<Neighborhood> neighborhoods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int start = 0; start < fragmentedKeys.size() && neighborhoods.size() < maxNeighborhoods; start++) {
            for (int seedCount = 1; seedCount <= MAX_SEED_COUNT
                    && start + seedCount <= fragmentedKeys.size()
                    && neighborhoods.size() < maxNeighborhoods; seedCount++) {
                List<String> seeds = fragmentedKeys.subList(start, start + seedCount);
                Neighborhood neighborhood = buildNeighborhood(rolls, seeds);
                if (neighborhood == null) {
                    continue;
                }
                String signature = neighborhood.selectedRollIndexes().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                if (seen.add(signature)) {
                    neighborhoods.add(neighborhood);
                }
            }
        }
        return neighborhoods;
    }

    private Neighborhood buildNeighborhood(List<RollRecord> rolls, List<String> seedKeys) {
        Set<Integer> selectedIndexes = new LinkedHashSet<>();
        Set<String> seedSet = new HashSet<>(seedKeys);
        for (RollRecord roll : rolls) {
            if (roll.demandCounts().keySet().stream().anyMatch(seedSet::contains)) {
                selectedIndexes.add(roll.index());
            }
        }
        if (selectedIndexes.isEmpty()) {
            return null;
        }

        Map<String, Integer> freeDemand = new TreeMap<>();
        Map<String, PatternKey> patterns = new LinkedHashMap<>();
        List<RollRecord> selectedRolls = new ArrayList<>();
        int cars = 0;
        int waste = 0;
        for (int index : selectedIndexes) {
            RollRecord roll = rolls.get(index);
            selectedRolls.add(roll);
            cars++;
            waste += roll.pattern().waste();
            patterns.putIfAbsent(roll.pattern().signature(), roll.pattern());
            for (Map.Entry<String, Integer> entry : roll.demandCounts().entrySet()) {
                freeDemand.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }

        if (freeDemand.size() > MAX_FREE_ORDERS || patterns.size() > MAX_FREE_PATTERNS) {
            return null;
        }
        return new Neighborhood(List.copyOf(seedKeys), selectedIndexes, freeDemand,
                new ArrayList<>(patterns.values()), List.copyOf(selectedRolls), cars, waste);
    }

    private SolveAttempt solveNeighborhood(Neighborhood neighborhood) {
        long start = System.currentTimeMillis();
        List<Column> columns = buildColumns(neighborhood);
        if (columns.isEmpty()) {
            return SolveAttempt.infeasible("no-columns", columns, 0L);
        }

        SolveSolution stage1 = solveColumns(neighborhood, columns, null, false);
        if (stage1 == null || stage1.counts().isEmpty()) {
            return SolveAttempt.infeasible("stage1-infeasible", columns, System.currentTimeMillis() - start);
        }

        SolveSolution stage2 = solveColumns(neighborhood, columns, stage1.activeColumns(), true);
        SolveSolution best = stage2 != null && !stage2.counts().isEmpty() ? stage2 : stage1;
        return new SolveAttempt(true, best.status(), columns, best.counts(), System.currentTimeMillis() - start);
    }

    private SolveSolution solveColumns(Neighborhood neighborhood, List<Column> columns,
            Integer activeCap, boolean secondaryObjective) {
        try {
            MPSolver solver = MPSolver.createSolver("SCIP");
            if (solver != null) {
                solver.setSolverSpecificParametersAsString(SCIP_DETERMINISTIC_PARAMS);
                try {
                    solver.setNumThreads(1);
                } catch (Exception ignored) {
                }
            } else {
                solver = MPSolver.createSolver("CBC");
            }
            if (solver == null) {
                return null;
            }

            int columnCount = columns.size();
            MPVariable[] nVars = new MPVariable[columnCount];
            MPVariable[] yVars = new MPVariable[columnCount];
            MPVariable[] oddVars = new MPVariable[columnCount];
            MPVariable[] largeVars = new MPVariable[columnCount];
            MPVariable[] halfVars = new MPVariable[columnCount];

            for (int i = 0; i < columnCount; i++) {
                nVars[i] = solver.makeIntVar(0, neighborhood.cars(), "n_" + i);
                yVars[i] = solver.makeBoolVar("y_" + i);

                MPConstraint upper = solver.makeConstraint(-MPSolver.infinity(), 0, "link_upper_" + i);
                upper.setCoefficient(nVars[i], 1);
                upper.setCoefficient(yVars[i], -neighborhood.cars());

                MPConstraint lower = solver.makeConstraint(0, MPSolver.infinity(), "link_lower_" + i);
                lower.setCoefficient(nVars[i], 1);
                lower.setCoefficient(yVars[i], -1);

                if (secondaryObjective) {
                    oddVars[i] = solver.makeBoolVar("odd_" + i);
                    largeVars[i] = solver.makeBoolVar("large_" + i);
                    halfVars[i] = solver.makeIntVar(0, neighborhood.cars(), "half_" + i);

                    MPConstraint parity = solver.makeConstraint(0, 0, "parity_" + i);
                    parity.setCoefficient(nVars[i], 1);
                    parity.setCoefficient(halfVars[i], -2);
                    parity.setCoefficient(oddVars[i], -1);

                    MPConstraint oddActive = solver.makeConstraint(-MPSolver.infinity(), 0, "odd_active_" + i);
                    oddActive.setCoefficient(oddVars[i], 1);
                    oddActive.setCoefficient(yVars[i], -1);

                    MPConstraint largeActive = solver.makeConstraint(-MPSolver.infinity(), 0, "large_active_" + i);
                    largeActive.setCoefficient(largeVars[i], 1);
                    largeActive.setCoefficient(yVars[i], -1);

                    MPConstraint largeLower = solver.makeConstraint(0, MPSolver.infinity(), "large_lower_" + i);
                    largeLower.setCoefficient(nVars[i], 1);
                    largeLower.setCoefficient(largeVars[i], -6);

                    MPConstraint largeUpper = solver.makeConstraint(-MPSolver.infinity(), 0, "large_upper_" + i);
                    largeUpper.setCoefficient(nVars[i], 1);
                    largeUpper.setCoefficient(yVars[i], -5);
                    largeUpper.setCoefficient(largeVars[i], -neighborhood.cars());
                }
            }

            for (Map.Entry<String, Integer> demand : neighborhood.freeDemand().entrySet()) {
                MPConstraint constraint = solver.makeConstraint(demand.getValue(), demand.getValue(),
                        "demand_" + Math.abs(demand.getKey().hashCode()));
                for (int i = 0; i < columnCount; i++) {
                    int contribution = columns.get(i).demandCounts().getOrDefault(demand.getKey(), 0);
                    if (contribution > 0) {
                        constraint.setCoefficient(nVars[i], contribution);
                    }
                }
            }

            MPConstraint cars = solver.makeConstraint(neighborhood.cars(), neighborhood.cars(), "cars");
            MPConstraint waste = solver.makeConstraint(neighborhood.waste(), neighborhood.waste(), "waste");
            MPConstraint active = activeCap == null
                    ? null
                    : solver.makeConstraint(0, activeCap, "active_cap");
            for (int i = 0; i < columnCount; i++) {
                cars.setCoefficient(nVars[i], 1);
                waste.setCoefficient(nVars[i], columns.get(i).pattern().waste());
                if (active != null) {
                    active.setCoefficient(yVars[i], 1);
                }
            }

            MPObjective objective = solver.objective();
            for (int i = 0; i < columnCount; i++) {
                if (secondaryObjective) {
                    int splitCost = columns.get(i).demandCounts().size();
                    objective.setCoefficient(yVars[i], 100.0 + splitCost);
                    objective.setCoefficient(largeVars[i], -100.0);
                    objective.setCoefficient(oddVars[i], 10.0);
                } else {
                    objective.setCoefficient(yVars[i], 1.0);
                }
            }
            objective.setMinimization();

            solver.setTimeLimit(Long.getLong("cutting.lns.timeLimitMs", DEFAULT_TIME_LIMIT_MS));
            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                return null;
            }

            Map<Integer, Integer> counts = new LinkedHashMap<>();
            int activeColumns = 0;
            for (int i = 0; i < columnCount; i++) {
                int count = (int) Math.round(nVars[i].solutionValue());
                if (count > 0) {
                    counts.put(i, count);
                    activeColumns++;
                }
            }
            return new SolveSolution(status.name(), counts, activeColumns);
        } catch (Exception e) {
            log.warn("LNS neighbourhood solve failed", e);
            return null;
        }
    }

    private List<Column> buildColumns(Neighborhood neighborhood) {
        Map<String, Column> required = new LinkedHashMap<>();
        for (RollRecord roll : neighborhood.selectedRolls()) {
            Column column = new Column(roll.pattern(), roll.config(), roll.demandCounts(), true);
            required.putIfAbsent(column.signature(), column);
        }

        List<Column> enumerated = new ArrayList<>();
        for (PatternKey pattern : neighborhood.patterns()) {
            List<Column> patternColumns = enumerateColumns(pattern, neighborhood.freeDemand());
            enumerated.addAll(patternColumns);
            if (enumerated.size() > COLUMN_ENUMERATION_GUARD) {
                break;
            }
        }
        enumerated.sort(Comparator
                .comparingInt((Column column) -> maxSupport(column, neighborhood.freeDemand())).reversed()
                .thenComparing(Comparator.comparingInt((Column column) -> column.demandCounts().size()).reversed())
                .thenComparing(Column::signature));

        List<Column> columns = new ArrayList<>(required.values());
        Set<String> seen = required.keySet().stream().collect(Collectors.toCollection(HashSet::new));
        int discarded = 0;
        for (Column column : enumerated) {
            if (!seen.add(column.signature())) {
                continue;
            }
            if (columns.size() >= MAX_COLUMNS) {
                discarded++;
                continue;
            }
            columns.add(column);
        }

        log.info("LNS column pool: required={} enumerated={} kept={} discarded={}",
                required.size(), enumerated.size(), columns.size(), discarded);
        return columns;
    }

    private List<Column> enumerateColumns(PatternKey pattern, Map<String, Integer> freeDemand) {
        Map<Integer, List<String>> messagesByWidth = new LinkedHashMap<>();
        for (String key : freeDemand.keySet()) {
            DemandKey demandKey = DemandKey.parse(key);
            messagesByWidth.computeIfAbsent(demandKey.width(), ignored -> new ArrayList<>())
                    .add(demandKey.message());
        }
        for (List<String> messages : messagesByWidth.values()) {
            messages.sort(Comparator
                    .comparingInt((String message) -> demandForMessage(messagesByWidth, freeDemand, message)).reversed()
                    .thenComparing(message -> message));
        }

        List<Map.Entry<Integer, Integer>> widths = new ArrayList<>(pattern.subRolls().entrySet());
        List<List<WidthOption>> optionsByWidth = new ArrayList<>();
        for (Map.Entry<Integer, Integer> width : widths) {
            List<String> messages = messagesByWidth.getOrDefault(width.getKey(), List.of());
            if (messages.isEmpty()) {
                return List.of();
            }
            List<WidthOption> options = new ArrayList<>();
            buildWidthOptions(width.getKey(), width.getValue(), messages, freeDemand, 0,
                    new ArrayList<>(), options);
            if (options.isEmpty()) {
                return List.of();
            }
            optionsByWidth.add(options);
        }

        List<Column> columns = new ArrayList<>();
        buildColumnsForPattern(pattern, widths, optionsByWidth, 0, new LinkedHashMap<>(), columns, freeDemand);
        return columns;
    }

    private int demandForMessage(Map<Integer, List<String>> messagesByWidth,
            Map<String, Integer> freeDemand,
            String message) {
        int total = 0;
        for (Integer width : messagesByWidth.keySet()) {
            total += freeDemand.getOrDefault(demandKey(width, message), 0);
        }
        return total;
    }

    private void buildWidthOptions(int width, int slots, List<String> messages,
            Map<String, Integer> freeDemand, int start, List<String> current,
            List<WidthOption> result) {
        if (current.size() == slots) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String message : current) {
                counts.merge(demandKey(width, message), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > freeDemand.getOrDefault(entry.getKey(), 0)) {
                    return;
                }
            }
            result.add(new WidthOption(width, List.copyOf(current), counts));
            return;
        }
        for (int i = start; i < messages.size(); i++) {
            current.add(messages.get(i));
            buildWidthOptions(width, slots, messages, freeDemand, i, current, result);
            current.remove(current.size() - 1);
        }
    }

    private void buildColumnsForPattern(PatternKey pattern,
            List<Map.Entry<Integer, Integer>> widths,
            List<List<WidthOption>> optionsByWidth,
            int offset,
            Map<Integer, List<String>> config,
            List<Column> columns,
            Map<String, Integer> freeDemand) {
        if (columns.size() >= COLUMN_ENUMERATION_GUARD) {
            return;
        }
        if (offset == widths.size()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<String>> configEntry : config.entrySet()) {
                int width = configEntry.getKey();
                List<String> messages = configEntry.getValue();
                for (String message : messages) {
                    counts.merge(demandKey(width, message), 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > freeDemand.getOrDefault(entry.getKey(), 0)) {
                    return;
                }
            }
            columns.add(new Column(pattern, copyConfig(config), counts, false));
            return;
        }

        int width = widths.get(offset).getKey();
        for (WidthOption option : optionsByWidth.get(offset)) {
            config.put(width, option.messages());
            buildColumnsForPattern(pattern, widths, optionsByWidth, offset + 1, config, columns, freeDemand);
            config.remove(width);
            if (columns.size() >= COLUMN_ENUMERATION_GUARD) {
                return;
            }
        }
    }

    private int maxSupport(Column column, Map<String, Integer> freeDemand) {
        int support = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : column.demandCounts().entrySet()) {
            if (entry.getValue() > 0) {
                support = Math.min(support, freeDemand.getOrDefault(entry.getKey(), 0) / entry.getValue());
            }
        }
        return support == Integer.MAX_VALUE ? 0 : support;
    }

    private List<CuttingInstruction> rebuildWithNeighborhoodSolution(
            List<RollRecord> rolls,
            Neighborhood neighborhood,
            SolveAttempt attempt) {
        Set<Integer> selected = neighborhood.selectedRollIndexes();
        int insertAt = selected.stream().mapToInt(Integer::intValue).min().orElse(0);
        List<CuttingInstruction> result = new ArrayList<>();
        List<RollRecord> run = new ArrayList<>();
        PatternKey runPattern = null;
        boolean inserted = false;

        for (RollRecord roll : rolls) {
            if (roll.index() == insertAt && !inserted) {
                flushRun(result, run, runPattern);
                run = new ArrayList<>();
                runPattern = null;
                result.addAll(buildInstructionsFromColumns(attempt));
                inserted = true;
            }
            if (selected.contains(roll.index())) {
                continue;
            }
            if (runPattern == null || !runPattern.equals(roll.pattern())) {
                flushRun(result, run, runPattern);
                run = new ArrayList<>();
                runPattern = roll.pattern();
            }
            run.add(roll);
        }
        flushRun(result, run, runPattern);
        if (!inserted) {
            result.addAll(buildInstructionsFromColumns(attempt));
        }
        return result;
    }

    private List<CuttingInstruction> buildInstructionsFromColumns(SolveAttempt attempt) {
        List<CuttingInstruction> result = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : attempt.counts().entrySet()) {
            Column column = attempt.columns().get(entry.getKey());
            CuttingInstruction instruction = createInstruction(column.pattern(), entry.getValue());
            List<StationAssignment> assignments = new ArrayList<>();
            for (int i = 0; i < entry.getValue(); i++) {
                for (Map.Entry<Integer, Integer> subRoll : column.pattern().subRolls().entrySet()) {
                    List<String> messages = column.config().getOrDefault(subRoll.getKey(), List.of());
                    for (String message : messages) {
                        assignments.add(new StationAssignment(subRoll.getKey(), message));
                    }
                }
            }
            instruction.setStationAssignments(assignments);
            result.add(instruction);
        }
        result.sort(Comparator.comparing(this::instructionRollSignature)
                .thenComparing(CuttingInstruction::getUsageCount, Comparator.reverseOrder()));
        return result;
    }

    private String instructionRollSignature(CuttingInstruction instruction) {
        List<List<StationAssignment>> rolls = simulateRolls(instruction);
        if (rolls.isEmpty()) {
            return "";
        }
        return rollContentSignature(rolls.get(0));
    }

    private void flushRun(List<CuttingInstruction> result, List<RollRecord> run, PatternKey pattern) {
        if (run == null || run.isEmpty() || pattern == null) {
            return;
        }
        CuttingInstruction instruction = createInstruction(pattern, run.size());
        List<StationAssignment> assignments = new ArrayList<>();
        for (RollRecord roll : run) {
            for (StationAssignment assignment : roll.assignments()) {
                assignments.add(new StationAssignment(assignment.getWidth(), assignment.getMessageText()));
            }
        }
        instruction.setStationAssignments(assignments);
        result.add(instruction);
    }

    private CuttingInstruction createInstruction(PatternKey pattern, int usageCount) {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setGroupKey(pattern.groupKey());
        instruction.setRollWidth(pattern.rollWidth());
        instruction.setLength(pattern.length());
        instruction.setSurfaceTreatment(pattern.surfaceTreatment());
        instruction.setThickness(pattern.thickness());
        instruction.setSubRolls(new LinkedHashMap<>(pattern.subRolls()));
        instruction.setUsageCount(usageCount);
        instruction.setPatternWidth(pattern.patternWidth());
        instruction.setWaste(pattern.waste());
        return instruction;
    }

    private List<RollRecord> decompose(List<CuttingInstruction> instructions) {
        List<RollRecord> rolls = new ArrayList<>();
        int index = 0;
        for (CuttingInstruction instruction : instructions) {
            PatternKey pattern = PatternKey.from(instruction);
            for (List<StationAssignment> roll : simulateRolls(instruction)) {
                Map<Integer, List<String>> config = configFromRoll(pattern, roll);
                Map<String, Integer> demandCounts = demandCounts(roll);
                rolls.add(new RollRecord(index++, pattern, roll, config, demandCounts,
                        rollContentSignature(roll)));
            }
        }
        return rolls;
    }

    private List<List<StationAssignment>> simulateRolls(CuttingInstruction instruction) {
        List<List<StationAssignment>> rolls = new ArrayList<>();
        Map<Integer, Queue<StationAssignment>> buckets = new LinkedHashMap<>();
        for (StationAssignment assignment : instruction.getStationAssignments()) {
            buckets.computeIfAbsent(assignment.getWidth(), ignored -> new ArrayDeque<>())
                    .add(assignment);
        }

        for (int i = 0; i < instruction.getUsageCount(); i++) {
            List<StationAssignment> roll = new ArrayList<>();
            for (Map.Entry<Integer, Integer> subRoll : instruction.getSubRolls().entrySet()) {
                Queue<StationAssignment> bucket = buckets.get(subRoll.getKey());
                for (int slot = 0; slot < subRoll.getValue(); slot++) {
                    if (bucket != null && !bucket.isEmpty()) {
                        StationAssignment assignment = bucket.poll();
                        roll.add(new StationAssignment(assignment.getWidth(), assignment.getMessageText()));
                    }
                }
            }
            if (!roll.isEmpty()) {
                rolls.add(roll);
            }
        }
        return rolls;
    }

    private Map<Integer, List<String>> configFromRoll(PatternKey pattern, List<StationAssignment> roll) {
        Map<Integer, List<String>> config = new LinkedHashMap<>();
        for (Integer width : pattern.subRolls().keySet()) {
            config.put(width, new ArrayList<>());
        }
        for (StationAssignment assignment : roll) {
            config.computeIfAbsent(assignment.getWidth(), ignored -> new ArrayList<>())
                    .add(Objects.toString(assignment.getMessageText(), ""));
        }
        for (List<String> messages : config.values()) {
            messages.sort(Comparator.naturalOrder());
        }
        return config;
    }

    private Map<String, Integer> demandCounts(List<StationAssignment> roll) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (StationAssignment assignment : roll) {
            counts.merge(demandKey(assignment.getWidth(), assignment.getMessageText()), 1, Integer::sum);
        }
        return counts;
    }

    private Map<String, Integer> countAssignmentsByDemandKey(List<CuttingInstruction> instructions) {
        Map<String, Integer> counts = new TreeMap<>();
        for (CuttingInstruction instruction : instructions) {
            for (StationAssignment assignment : instruction.getStationAssignments()) {
                counts.merge(demandKey(assignment.getWidth(), assignment.getMessageText()), 1, Integer::sum);
            }
        }
        return counts;
    }

    private int totalCars(List<CuttingInstruction> instructions) {
        return instructions.stream().mapToInt(CuttingInstruction::getUsageCount).sum();
    }

    private int totalWaste(List<CuttingInstruction> instructions) {
        return instructions.stream()
                .mapToInt(instruction -> instruction.getWaste() * instruction.getUsageCount())
                .sum();
    }

    private List<CuttingInstruction> cloneInstructions(List<CuttingInstruction> source) {
        List<CuttingInstruction> clones = new ArrayList<>();
        for (CuttingInstruction instruction : source) {
            CuttingInstruction clone = createInstruction(PatternKey.from(instruction), instruction.getUsageCount());
            List<StationAssignment> assignments = new ArrayList<>();
            for (StationAssignment assignment : instruction.getStationAssignments()) {
                assignments.add(new StationAssignment(assignment.getWidth(), assignment.getMessageText()));
            }
            clone.setStationAssignments(assignments);
            clones.add(clone);
        }
        return clones;
    }

    private String rollContentSignature(List<StationAssignment> roll) {
        return roll.stream()
                .map(assignment -> demandKey(assignment.getWidth(), assignment.getMessageText()))
                .sorted()
                .collect(Collectors.joining(","));
    }

    private Map<Integer, List<String>> copyConfig(Map<Integer, List<String>> config) {
        Map<Integer, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String>> entry : config.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return copy;
    }

    private static String demandKey(int width, String messageText) {
        return width + "|" + Objects.toString(messageText, "");
    }

    public record LnsResult(
            boolean improved,
            List<CuttingInstruction> instructions,
            int beforeGroups,
            int afterGroups,
            String reason) {

        static LnsResult notImproved(List<CuttingInstruction> instructions, String reason) {
            return new LnsResult(false, instructions, 0, 0, reason);
        }
    }

    private record Neighborhood(
            List<String> seedKeys,
            Set<Integer> selectedRollIndexes,
            Map<String, Integer> freeDemand,
            List<PatternKey> patterns,
            List<RollRecord> selectedRolls,
            int cars,
            int waste) {
    }

    private record SolveAttempt(
            boolean feasible,
            String status,
            List<Column> columns,
            Map<Integer, Integer> counts,
            long elapsedMs) {

        static SolveAttempt infeasible(String reason, List<Column> columns, long elapsedMs) {
            return new SolveAttempt(false, reason, columns, Map.of(), elapsedMs);
        }

        String reason() {
            return status;
        }
    }

    private record SolveSolution(String status, Map<Integer, Integer> counts, int activeColumns) {
    }

    private record RollRecord(
            int index,
            PatternKey pattern,
            List<StationAssignment> assignments,
            Map<Integer, List<String>> config,
            Map<String, Integer> demandCounts,
            String configSignature) {
    }

    private record Column(
            PatternKey pattern,
            Map<Integer, List<String>> config,
            Map<String, Integer> demandCounts,
            boolean required) {

        String signature() {
            return pattern.signature() + "@@" + config.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + String.join("/", entry.getValue()))
                    .collect(Collectors.joining("|"));
        }
    }

    private record PatternKey(
            String groupKey,
            int rollWidth,
            int length,
            String surfaceTreatment,
            int thickness,
            Map<Integer, Integer> subRolls,
            int patternWidth,
            int waste) {

        static PatternKey from(CuttingInstruction instruction) {
            return new PatternKey(
                    instruction.getGroupKey(),
                    instruction.getRollWidth(),
                    instruction.getLength(),
                    instruction.getSurfaceTreatment(),
                    instruction.getThickness(),
                    new LinkedHashMap<>(instruction.getSubRolls()),
                    instruction.getPatternWidth(),
                    instruction.getWaste());
        }

        String signature() {
            return rollWidth + "|" + subRolls.entrySet().stream()
                    .map(entry -> entry.getKey() + "x" + entry.getValue())
                    .collect(Collectors.joining(","));
        }
    }

    private record DemandKey(int width, String message) {

        static DemandKey parse(String key) {
            String[] parts = key.split("\\|", 2);
            return new DemandKey(Integer.parseInt(parts[0]), parts.length > 1 ? parts[1] : "");
        }
    }

    private record WidthOption(int width, List<String> messages, Map<String, Integer> demandCounts) {
    }
}
