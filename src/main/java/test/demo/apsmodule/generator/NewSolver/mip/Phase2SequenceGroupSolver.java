package test.demo.apsmodule.generator.NewSolver.mip;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 2 sequence-group optimizer.
 *
 * <p>Columns are full-roll message configurations for an existing cutting
 * pattern. The final MIP minimizes the number of active columns, which is the
 * proxy closest to exported sequence groups after roll compaction.
 */
public class Phase2SequenceGroupSolver {

    private static final Logger log = LoggerFactory.getLogger(Phase2SequenceGroupSolver.class);

    private static final int INITIAL_TOP_MESSAGES_PER_PATTERN = 3;
    private static final int MAX_COLUMN_GENERATION_ITERATIONS = 10;
    private static final int PRICING_ADD_LIMIT = 20;
    private static final int MAX_COLUMNS = 400;
    private static final long LP_TIME_LIMIT_MS = 6_000L;
    private static final long MIP_TIME_LIMIT_MS = 10_000L;
    private static final double REDUCED_COST_EPSILON = 1e-4;
    private static final double SLACK_PENALTY = 10_000.0;
    private static final double ROLL_TIE_BREAKER = 1e-6;
    private static final double WASTE_TIE_BREAKER = 1e-9;
    private static final double PARITY_PENALTY = 0.3;

    private final SolverParameters params;

    public Phase2SequenceGroupSolver(SolverParameters params) {
        this.params = params;
    }

    /**
     * Compatibility entry point for callers that only need the assignment map.
     * Prefer {@link #solveWithSolution(Map, List)} when pattern usages may change.
     */
    public Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> solve(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> groupItems) {
        SolveResult result = solveWithSolution(solution, groupItems);
        return result == null ? null : result.assignments();
    }

    public SolveResult solveWithSolution(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> groupItems) {
        return solveWithSolution(solution, groupItems, null);
    }

    public SolveResult solveWithSolution(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> groupItems,
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> seedAssignments) {
        log.info("--- Phase 2: sequence-group column generation ---");

        if (solution == null || solution.isEmpty() || groupItems == null || groupItems.isEmpty()) {
            return null;
        }

        Phase2Data data = buildData(solution, groupItems);
        if (data.demands().isEmpty() || data.messagesByWidth().isEmpty()) {
            return null;
        }

        long startTime = System.currentTimeMillis();
        List<Column> columns = buildInitialColumns(data, seedAssignments);
        if (columns.isEmpty()) {
            log.warn("Phase2 skipped: no initial columns");
            return null;
        }
        SolveResult seedResult = buildSeedResult(solution, seedAssignments, data);
        if (seedResult != null) {
            int seedBlocks = seedResult.assignments().values().stream().mapToInt(List::size).sum();
            log.info("Phase2 seed baseline: patterns={} blocks={} rolls={}",
                    seedResult.solution().size(), seedBlocks, data.incumbentRolls());
        }
        SolveResult bestFallback = seedResult;

        // Per-pattern independent solve: each pattern's assignment is optimized
        // independently using a tiny MIP (~300ms each). This avoids the global MIP
        // scalability problem while still minimizing sequence groups per pattern.
        SolveResult result = solvePerPatternIndependently(solution, seedAssignments, data, startTime);
        if (result != null && activeBlockCount(result) < activeBlockCount(bestFallback)) {
            log.info("Phase2 per-pattern completed: blocks {} -> {} elapsed={}ms",
                    activeBlockCount(bestFallback), activeBlockCount(result),
                    System.currentTimeMillis() - startTime);
            return result;
        }
        log.info("Phase2 per-pattern: no improvement over seed ({}ms), returning seed",
                System.currentTimeMillis() - startTime);
        return bestFallback;
    }

    private Phase2Data buildData(Map<PatternCandidate, Integer> solution, List<SolverOrderItem> groupItems) {
        Map<DemandKey, Integer> demands = new LinkedHashMap<>();
        Map<Integer, Map<String, Integer>> messageDemandByWidth = new LinkedHashMap<>();

        for (SolverOrderItem item : groupItems) {
            String message = item.getMessageText() == null ? "" : item.getMessageText().trim();
            DemandKey key = new DemandKey(item.getWidth(), message);
            demands.merge(key, item.getDemand(), Integer::sum);
            messageDemandByWidth
                    .computeIfAbsent(item.getWidth(), ignored -> new LinkedHashMap<>())
                    .merge(message, item.getDemand(), Integer::sum);
        }

        Map<Integer, List<MessageDemand>> messagesByWidth = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Integer>> widthEntry : messageDemandByWidth.entrySet()) {
            List<MessageDemand> messages = widthEntry.getValue().entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(entry -> new MessageDemand(entry.getKey(), entry.getValue()))
                    .sorted(Comparator
                            .comparingInt(MessageDemand::demand).reversed()
                            .thenComparing(MessageDemand::message))
                    .toList();
            messagesByWidth.put(widthEntry.getKey(), messages);
        }

        List<PatternCandidate> patterns = solution.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();

        int incumbentRolls = solution.values().stream()
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        int incumbentWaste = solution.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .mapToInt(entry -> entry.getKey().getRealWaste(params.getTotalWidth()) * entry.getValue())
                .sum();

        return new Phase2Data(patterns, demands, messagesByWidth, incumbentRolls, incumbentWaste);
    }

    private List<Column> buildInitialColumns(
            Phase2Data data,
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> seedAssignments) {
        List<Column> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int seedColumns = addSeedColumns(columns, seen, data, seedAssignments);
        if (seedColumns > 0) {
            log.info("Phase2 initial seed columns={}", seedColumns);
        }

        for (int patternIndex = 0; patternIndex < data.patterns().size(); patternIndex++) {
            PatternCandidate pattern = data.patterns().get(patternIndex);
            if (!isPatternMessageCovered(pattern, data.messagesByWidth())) {
                continue;
            }

            for (int rank = 0; rank < INITIAL_TOP_MESSAGES_PER_PATTERN; rank++) {
                Map<Integer, List<String>> config = new LinkedHashMap<>();
                for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                    int width = cut.getKey();
                    List<MessageDemand> messages = data.messagesByWidth().get(width);
                    config.put(width, repeatMessage(
                            messages.get(Math.min(rank, messages.size() - 1)).message(),
                            cut.getValue()));
                }
                addColumn(columns, seen, createColumn(patternIndex, pattern, config, data.demands()));
            }


        }

        return columns;
    }


    // ── String helpers ──────────────────────────────────────────────────────

    private static String buildColumnSignature(int patternIndex, Map<Integer, List<String>> config) {
        StringBuilder sb = new StringBuilder().append(patternIndex).append("@@");
        new java.util.TreeMap<>(config).forEach((width, msgs) -> {
            List<String> sorted = new java.util.ArrayList<>(msgs);
            Collections.sort(sorted);
            sb.append(width).append('=').append(String.join(",", sorted)).append('|');
        });
        return sb.toString();
    }

    private static List<String> repeatMessage(String message, int kw) {
        List<String> list = new java.util.ArrayList<>(kw);
        for (int i = 0; i < kw; i++) list.add(message);
        return list;
    }

    private static Map<Integer, List<String>> copyConfig(Map<Integer, List<String>> config) {
        Map<Integer, List<String>> copy = new LinkedHashMap<>();
        config.forEach((w, msgs) -> copy.put(w, new java.util.ArrayList<>(msgs)));
        return copy;
    }

    // ── Column construction ──────────────────────────────────────────────────

    private Column createColumn(int patternIndex, PatternCandidate pattern,
                                Map<Integer, List<String>> config,
                                Map<DemandKey, Integer> demands) {
        Map<DemandKey, Integer> contributions = new LinkedHashMap<>();
        config.forEach((width, msgs) -> {
            for (String msg : msgs) {
                DemandKey key = new DemandKey(width, msg);
                if (demands.containsKey(key)) {
                    contributions.merge(key, 1, Integer::sum);
                }
            }
        });
        int waste = pattern.getRealWaste(params.getTotalWidth());
        String sig = buildColumnSignature(patternIndex, config);
        return new Column(patternIndex, pattern, config, contributions, waste, sig);
    }

    private static void addColumn(List<Column> columns, Set<String> seen, Column col) {
        if (col != null && seen.add(col.signature())) {
            columns.add(col);
        }
    }

    private static boolean isPatternMessageCovered(PatternCandidate pattern,
                                                    Map<Integer, List<MessageDemand>> msgByWidth) {
        for (int w : pattern.getPattern().keySet()) {
            if (!msgByWidth.containsKey(w) || msgByWidth.get(w).isEmpty()) return false;
        }
        return true;
    }

    private static Map<Integer, List<String>> topMessageConfig(PatternCandidate pattern,
                                                                Map<Integer, List<MessageDemand>> msgByWidth) {
        Map<Integer, List<String>> config = new LinkedHashMap<>();
        pattern.getPattern().forEach((w, kw) -> {
            List<MessageDemand> msgs = msgByWidth.getOrDefault(w, Collections.emptyList());
            String top = msgs.isEmpty() ? "" : msgs.get(0).message();
            config.put(w, repeatMessage(top, kw));
        });
        return config;
    }

    // ── Seed column initialization ───────────────────────────────────────────

    private int addSeedColumns(List<Column> columns, Set<String> seen, Phase2Data data,
                                Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> seedAssignments) {
        if (seedAssignments == null) return 0;
        int count = 0;
        for (int pIdx = 0; pIdx < data.patterns().size(); pIdx++) {
            PatternCandidate pattern = data.patterns().get(pIdx);
            List<AssignmentMIPSolver.AssignmentBlock> blocks = seedAssignments.get(pattern);
            if (blocks == null) continue;
            for (AssignmentMIPSolver.AssignmentBlock block : blocks) {
                Map<Integer, List<String>> sc = block.getStationConfig();
                if (sc == null || sc.isEmpty()) continue;
                Column col = createColumn(pIdx, pattern, sc, data.demands());
                if (col != null && seen.add(col.signature())) {
                    columns.add(col);
                    count++;
                }
            }
        }
        return count;
    }

    // ── Seed baseline construction ───────────────────────────────────────────

    private SolveResult buildSeedResult(Map<PatternCandidate, Integer> solution,
                                         Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> seedAssignments,
                                         Phase2Data data) {
        if (seedAssignments == null || seedAssignments.isEmpty()) return null;
        Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> assignments = new LinkedHashMap<>();
        for (PatternCandidate pattern : data.patterns()) {
            List<AssignmentMIPSolver.AssignmentBlock> blocks = seedAssignments.get(pattern);
            if (blocks != null && !blocks.isEmpty()) {
                assignments.put(pattern, blocks);
            }
        }
        SolveResult result = new SolveResult(new LinkedHashMap<>(solution), assignments);
        String invalid = validateSolution(result.assignments(), data);
        if (invalid != null) {
            log.warn("Phase2 Stage5 seed baseline is not valid for Phase2 constraints: {}", invalid);
            return null;
        }
        return result;
    }

    private String validateSolution(Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> assignments,
                                     Phase2Data data) {
        Map<DemandKey, Integer> produced = new LinkedHashMap<>();
        assignments.values().stream().flatMap(List::stream).forEach(block -> {
            Map<Integer, List<String>> sc = block.getStationConfig();
            if (sc == null) return;
            sc.forEach((w, msgs) -> {
                for (String msg : msgs) {
                    produced.merge(new DemandKey(w, msg), block.getCount(), Integer::sum);
                }
            });
        });
        List<String> deficits = new java.util.ArrayList<>();
        for (Map.Entry<DemandKey, Integer> e : data.demands().entrySet()) {
            int actual = produced.getOrDefault(e.getKey(), 0);
            if (actual < e.getValue()) {
                deficits.add(e.getKey().width() + "|" + e.getKey().message()
                        + "(need=" + e.getValue() + ",got=" + actual + ")");
            }
        }
        if (!deficits.isEmpty()) {
            return "demand deficits: " + String.join(", ",
                    deficits.subList(0, Math.min(3, deficits.size())));
        }
        return null;
    }

    private static int activeBlockCount(SolveResult result) {
        if (result == null) return Integer.MAX_VALUE;
        return result.assignments().values().stream().mapToInt(List::size).sum();
    }

    // ── Column generation: master LP ─────────────────────────────────────────

    private MasterLPResult solveMasterLP(List<Column> columns, Phase2Data data) {
        try {
            MPSolver solver = MPSolver.createSolver("GLOP");
            if (solver == null) return new MasterLPResult(false, Map.of());

            List<MPVariable> lambdas = new java.util.ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                lambdas.add(solver.makeNumVar(0, solver.infinity(), "l" + i));
            }

            // Demand constraints + shortage slack for LP feasibility
            Map<DemandKey, MPConstraint> demandCtrs = new LinkedHashMap<>();
            for (Map.Entry<DemandKey, Integer> e : data.demands().entrySet()) {
                DemandKey key = e.getKey();
                MPVariable slack = solver.makeNumVar(0, e.getValue(), "s_" + key.hashCode());
                MPConstraint c = solver.makeConstraint(e.getValue(), solver.infinity(),
                        "d_" + key.hashCode());
                for (int i = 0; i < columns.size(); i++) {
                    int contrib = columns.get(i).contributions().getOrDefault(key, 0);
                    if (contrib > 0) c.setCoefficient(lambdas.get(i), contrib);
                }
                c.setCoefficient(slack, 1.0);
                // slack in objective with high penalty
                solver.objective().setCoefficient(slack, SLACK_PENALTY);
                demandCtrs.put(key, c);
            }

            // Global roll bound
            MPConstraint rollBound = solver.makeConstraint(0, data.incumbentRolls(), "rolls");
            lambdas.forEach(lam -> rollBound.setCoefficient(lam, 1.0));

            // Objective: min total usage (proxy; duals guide pricing)
            lambdas.forEach(lam -> solver.objective().setCoefficient(lam, 1.0));
            solver.objective().setMinimization();

            long lpIterMs = Math.max(500, LP_TIME_LIMIT_MS / (MAX_COLUMN_GENERATION_ITERATIONS + 1));
            solver.setTimeLimit(lpIterMs);
            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                return new MasterLPResult(false, Map.of());
            }

            Map<DemandKey, Double> duals = new LinkedHashMap<>();
            demandCtrs.forEach((key, c) -> duals.put(key, c.dualValue()));
            return new MasterLPResult(true, duals);
        } catch (Exception ex) {
            log.warn("Phase2 master LP exception", ex);
            return new MasterLPResult(false, Map.of());
        }
    }

    // ── Column generation: pricing subproblem (greedy per pattern) ──────────

    private List<PricedColumn> priceColumns(Phase2Data data,
                                             Map<DemandKey, Double> duals,
                                             Set<String> seen) {
        List<PricedColumn> result = new java.util.ArrayList<>();
        for (int pIdx = 0; pIdx < data.patterns().size(); pIdx++) {
            PatternCandidate pattern = data.patterns().get(pIdx);
            if (!isPatternMessageCovered(pattern, data.messagesByWidth())) continue;

            Map<Integer, List<String>> bestConfig = new LinkedHashMap<>();
            double totalDual = 0.0;

            for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                int width = cut.getKey();
                int kw = cut.getValue();
                List<MessageDemand> msgs = data.messagesByWidth().getOrDefault(width, Collections.emptyList());
                String bestMsg = null;
                double bestDual = Double.NEGATIVE_INFINITY;
                for (MessageDemand md : msgs) {
                    double d = duals.getOrDefault(new DemandKey(width, md.message()), 0.0);
                    if (d > bestDual) { bestDual = d; bestMsg = md.message(); }
                }
                if (bestMsg == null) { bestConfig = null; break; }
                bestConfig.put(width, repeatMessage(bestMsg, kw));
                totalDual += bestDual * kw;
            }

            if (bestConfig == null) continue;
            double rc = 1.0 - totalDual;
            if (rc < -REDUCED_COST_EPSILON) {
                Column col = createColumn(pIdx, pattern, bestConfig, data.demands());
                if (col != null && !seen.contains(col.signature())) {
                    result.add(new PricedColumn(col, rc));
                }
            }
        }
        result.sort(Comparator.comparingDouble(PricedColumn::reducedCost));
        return result;
    }

    // ── Final integer MIP ─────────────────────────────────────────────────────

    private SolveResult solveFinalMip(List<Column> columns, Phase2Data data, Map<PatternCandidate, Integer> solution) {
        try {
            MPSolver solver = null;
            try { solver = MPSolver.createSolver("SCIP"); } catch (Exception ignored) {}
            if (solver == null) {
                try { solver = MPSolver.createSolver("CBC"); } catch (Exception ignored2) {}
            }
            if (solver == null) return null;

            int bigM = data.incumbentRolls() + params.getTotalOverCap();

            List<MPVariable> nVars = new java.util.ArrayList<>();
            List<MPVariable> yVars = new java.util.ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                nVars.add(solver.makeIntVar(0, bigM, "n" + i));
                yVars.add(solver.makeBoolVar("y" + i));
            }

            // Demand: Σ contrib[col][key] * n[col] >= demand[key]
            for (Map.Entry<DemandKey, Integer> e : data.demands().entrySet()) {
                DemandKey key = e.getKey();
                MPConstraint c = solver.makeConstraint(e.getValue(), solver.infinity(),
                        "d_" + key.hashCode());
                for (int i = 0; i < columns.size(); i++) {
                    int contrib = columns.get(i).contributions().getOrDefault(key, 0);
                    if (contrib > 0) c.setCoefficient(nVars.get(i), contrib);
                }
            }

            // Waste cap: Σ waste[col] * n[col] <= incumbentWaste
            MPConstraint wasteCap = solver.makeConstraint(0, data.incumbentWaste(), "waste");
            for (int i = 0; i < columns.size(); i++) {
                wasteCap.setCoefficient(nVars.get(i), columns.get(i).waste());
            }

            // Per-pattern equality: Σ_{col for p} n[col] = usage[p]
            // Fixes each pattern's roll count — makes MIP nearly decomposable per pattern
            Map<PatternCandidate, List<Integer>> colsByPattern = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                colsByPattern.computeIfAbsent(columns.get(i).pattern(), k -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<PatternCandidate, List<Integer>> pe : colsByPattern.entrySet()) {
                Integer usage = solution.get(pe.getKey());
                if (usage == null || usage <= 0) continue;
                MPConstraint pc = solver.makeConstraint(usage, usage,
                        "pat_" + Math.abs(pe.getKey().signature().hashCode()));
                for (int idx : pe.getValue()) {
                    pc.setCoefficient(nVars.get(idx), 1.0);
                }
            }

            // Linking: n[col] <= bigM * y[col]
            for (int i = 0; i < columns.size(); i++) {
                MPConstraint link = solver.makeConstraint(-solver.infinity(), 0, "lk" + i);
                link.setCoefficient(nVars.get(i), 1.0);
                link.setCoefficient(yVars.get(i), -bigM);
            }

            // Objective: min Σ y[col] + tiny × n + tiny × waste×n
            MPObjective obj = solver.objective();
            for (int i = 0; i < columns.size(); i++) {
                obj.setCoefficient(yVars.get(i), 1.0);
                obj.setCoefficient(nVars.get(i),
                        ROLL_TIE_BREAKER + columns.get(i).waste() * WASTE_TIE_BREAKER);
            }
            obj.setMinimization();

            solver.setTimeLimit(MIP_TIME_LIMIT_MS);
            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Phase2 final MIP failed: {} ({}ms), columns={}",
                        status, MIP_TIME_LIMIT_MS, columns.size());
                return null;
            }

            log.info("Phase2 final MIP completed: {} columns={}", status, columns.size());
            return extractSolveResult(columns, nVars);
        } catch (Exception ex) {
            log.error("Phase2 final MIP exception", ex);
            return null;
        }
    }

    private SolveResult extractSolveResult(List<Column> columns, List<MPVariable> nVars) {
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> assignments = new LinkedHashMap<>();

        for (int i = 0; i < columns.size(); i++) {
            int count = (int) Math.round(nVars.get(i).solutionValue());
            if (count <= 0) continue;
            Column col = columns.get(i);
            solution.merge(col.pattern(), count, Integer::sum);
            assignments.computeIfAbsent(col.pattern(), k -> new java.util.ArrayList<>())
                       .add(AssignmentMIPSolver.AssignmentBlock.fromStationConfig(col.config(), count));
        }
        return new SolveResult(solution, assignments);
    }


    // ── Per-pattern independent optimization ─────────────────────────────────

    /**
     * Optimizes each pattern's message assignment independently.
     * For each pattern with more than 1 block, solves a tiny MIP:
     *   min active configs  s.t.  exact demand coverage, exact roll count
     * Each pattern MIP has ~10-30 columns and solves in <300ms.
     */
    private SolveResult solvePerPatternIndependently(
            Map<PatternCandidate, Integer> solution,
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> seedAssignments,
            Phase2Data data,
            long startTime) {
        if (seedAssignments == null) return null;

        Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> assignments = new LinkedHashMap<>();
        int improved = 0;

        for (int pIdx = 0; pIdx < data.patterns().size(); pIdx++) {
            PatternCandidate pattern = data.patterns().get(pIdx);
            List<AssignmentMIPSolver.AssignmentBlock> seedBlocks = seedAssignments.get(pattern);
            if (seedBlocks == null || seedBlocks.isEmpty()) continue;

            if (seedBlocks.size() <= 1) {
                assignments.put(pattern, seedBlocks);
                continue;
            }

            int usage = solution.getOrDefault(pattern, 0);
            List<AssignmentMIPSolver.AssignmentBlock> optimized =
                    solveOnePatternMIP(pIdx, pattern, seedBlocks, usage, data);

            if (optimized != null && optimized.size() < seedBlocks.size()) {
                assignments.put(pattern, optimized);
                improved++;
                log.debug("Phase2 pattern improved: {} blocks {} -> {}",
                        pattern.signature().substring(0, Math.min(30, pattern.signature().length())),
                        seedBlocks.size(), optimized.size());
            } else {
                assignments.put(pattern, seedBlocks);
            }
        }

        if (improved > 0) {
            log.info("Phase2 per-pattern: {} patterns improved in {}ms",
                    improved, System.currentTimeMillis() - startTime);
        }
        return new SolveResult(new LinkedHashMap<>(solution), assignments);
    }

    /**
     * Solves a tiny MIP for one pattern: given the pattern's allocated demand
     * (extracted from seedBlocks), find the minimum number of distinct full-roll
     * configs that exactly cover that demand.
     */
    private List<AssignmentMIPSolver.AssignmentBlock> solveOnePatternMIP(
            int pIdx,
            PatternCandidate pattern,
            List<AssignmentMIPSolver.AssignmentBlock> seedBlocks,
            int usage,
            Phase2Data data) {
        if (usage <= 0) return null;

        // Extract per-pattern demand target from Stage5 seed allocation
        Map<DemandKey, Integer> target = new LinkedHashMap<>();
        for (AssignmentMIPSolver.AssignmentBlock block : seedBlocks) {
            Map<Integer, List<String>> sc = block.getStationConfig();
            if (sc == null) continue;
            sc.forEach((w, msgs) -> {
                for (String msg : msgs) {
                    target.merge(new DemandKey(w, msg), block.getCount(), Integer::sum);
                }
            });
        }

        // Generate columns for this pattern only
        List<Column> cols = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Seed columns (one per seed block)
        for (AssignmentMIPSolver.AssignmentBlock block : seedBlocks) {
            Map<Integer, List<String>> sc = block.getStationConfig();
            if (sc != null) addColumn(cols, seen, createColumn(pIdx, pattern, sc, data.demands()));
        }

        // 2. Top-rank alternatives (top-3 messages per width, cross-combined)
        for (int rank = 0; rank < INITIAL_TOP_MESSAGES_PER_PATTERN; rank++) {
            Map<Integer, List<String>> config = new LinkedHashMap<>();
            boolean valid = true;
            for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                int w = cut.getKey();
                int kw = cut.getValue();
                List<MessageDemand> msgs = data.messagesByWidth().getOrDefault(w, Collections.emptyList());
                if (msgs.isEmpty()) { valid = false; break; }
                config.put(w, repeatMessage(msgs.get(Math.min(rank, msgs.size() - 1)).message(), kw));
            }
            if (valid) addColumn(cols, seen, createColumn(pIdx, pattern, config, data.demands()));
        }

        // 3. Focused: for each (width, message) in target, add a column
        //    with that message for that width, top message for others
        Map<Integer, List<String>> baseConfig = topMessageConfig(pattern, data.messagesByWidth());
        for (Map.Entry<DemandKey, Integer> t : target.entrySet()) {
            int w = t.getKey().width();
            if (!pattern.getPattern().containsKey(w)) continue;
            int kw = pattern.getPattern().get(w);
            Map<Integer, List<String>> focused = copyConfig(baseConfig);
            focused.put(w, repeatMessage(t.getKey().message(), kw));
            addColumn(cols, seen, createColumn(pIdx, pattern, focused, data.demands()));
        }

        if (cols.size() <= seedBlocks.size()) return null; // nothing new to try

        // Solve tiny MIP
        try {
            MPSolver solver = null;
            try { solver = MPSolver.createSolver("SCIP"); } catch (Exception ignored) {}
            if (solver == null) try { solver = MPSolver.createSolver("CBC"); } catch (Exception ignored2) {}
            if (solver == null) return null;

            List<MPVariable> nVars = new java.util.ArrayList<>();
            List<MPVariable> yVars = new java.util.ArrayList<>();
            List<MPVariable> kHalfVars = new java.util.ArrayList<>();
            List<MPVariable> r2Vars = new java.util.ArrayList<>();
            for (int i = 0; i < cols.size(); i++) {
                nVars.add(solver.makeIntVar(0, usage, "n" + i));
                yVars.add(solver.makeBoolVar("y" + i));
                kHalfVars.add(solver.makeIntVar(0, usage / 2, "kh" + i));
                r2Vars.add(solver.makeBoolVar("r2" + i));
            }

            // Exact demand constraints from Stage5 allocation
            for (Map.Entry<DemandKey, Integer> t : target.entrySet()) {
                DemandKey key = t.getKey();
                MPConstraint c = solver.makeConstraint(t.getValue(), t.getValue(), "d" + key.hashCode());
                for (int i = 0; i < cols.size(); i++) {
                    int contrib = cols.get(i).contributions().getOrDefault(key, 0);
                    if (contrib > 0) c.setCoefficient(nVars.get(i), contrib);
                }
            }

            // Exact usage count
            MPConstraint usageCtr = solver.makeConstraint(usage, usage, "use");
            nVars.forEach(nv -> usageCtr.setCoefficient(nv, 1.0));

            // Linking: n[col] <= usage * y[col]
            for (int i = 0; i < cols.size(); i++) {
                MPConstraint link = solver.makeConstraint(-solver.infinity(), 0, "lk" + i);
                link.setCoefficient(nVars.get(i), 1.0);
                link.setCoefficient(yVars.get(i), -usage);
            }

            // Parity: n[col] = 2*kHalf[col] + r2[col]  (r2=1 means odd-car group)
            for (int i = 0; i < cols.size(); i++) {
                MPConstraint pc = solver.makeConstraint(0, 0, "par" + i);
                pc.setCoefficient(nVars.get(i), 1.0);
                pc.setCoefficient(kHalfVars.get(i), -2.0);
                pc.setCoefficient(r2Vars.get(i), -1.0);
            }

            // Objective: minimize active configs + parity penalty
            MPObjective obj = solver.objective();
            for (int i = 0; i < cols.size(); i++) {
                obj.setCoefficient(yVars.get(i), 1.0);
                obj.setCoefficient(nVars.get(i), ROLL_TIE_BREAKER);
                obj.setCoefficient(r2Vars.get(i), PARITY_PENALTY);
            }
            obj.setMinimization();

            solver.setTimeLimit(300L); // 300ms per pattern is sufficient

            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                return null;
            }

            List<AssignmentMIPSolver.AssignmentBlock> blocks = new java.util.ArrayList<>();
            for (int i = 0; i < cols.size(); i++) {
                int count = (int) Math.round(nVars.get(i).solutionValue());
                if (count > 0) {
                    blocks.add(AssignmentMIPSolver.AssignmentBlock.fromStationConfig(
                            cols.get(i).config(), count));
                }
            }
            return blocks.isEmpty() ? null : blocks;
        } catch (Exception ex) {
            log.debug("Phase2 per-pattern MIP exception for pattern {}: {}", pIdx, ex.getMessage());
            return null;
        }
    }


    public record SolveResult(
            Map<PatternCandidate, Integer> solution,
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> assignments) {
    }

    private record Phase2Data(
            List<PatternCandidate> patterns,
            Map<DemandKey, Integer> demands,
            Map<Integer, List<MessageDemand>> messagesByWidth,
            int incumbentRolls,
            int incumbentWaste) {
    }

    private record DemandKey(int width, String message) {
    }

    private record MessageDemand(String message, int demand) {
    }

    private record Column(
            int patternIndex,
            PatternCandidate pattern,
            Map<Integer, List<String>> config,
            Map<DemandKey, Integer> contributions,
            int waste,
            String signature) {
    }

    private record WidthOption(
            List<String> messages,
            int support,
            int distinctMessageCount,
            int oddMultiplicityCount) {
    }

    private record PricedColumn(Column column, double reducedCost) {
    }

    private record ActiveColumn(Column column, int count) {
    }

    private record MasterLPResult(boolean feasible, Map<DemandKey, Double> duals) {
    }
}
