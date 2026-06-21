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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    // Global set-partition time budget. With a valid seed the model is always
    // feasible, so a longer budget pays off; without a seed the structured pool
    // is often infeasible (pure columns force the top message to over-produce
    // while exact equality forbids it), so we cap the wasted spin tightly.
    private static final long SP_TIME_LIMIT_SEEDED_MS = 5_000L;
    private static final long SP_TIME_LIMIT_UNSEEDED_MS = 2_000L;
    private static final double ROLL_TIE_BREAKER = 1e-6;
    private static final double WASTE_TIE_BREAKER = 1e-9;
    private static final double PARITY_PENALTY = 0.3;

    // Structured column pool for the primary global set-partition path.
    // The pool is deliberately size-capped (pure-roll + remainder + seed columns)
    // so the final MIP never explodes the way unbounded column generation did.
    private static final int POOL_TOP_MESSAGES_PER_WIDTH = 4;
    private static final int POOL_REMAINDER_TOP_ORDERS = 3;
    private static final int POOL_MAX_COLUMNS_PER_PATTERN = 40;
    private static final double SLACK_PENALTY_SP = 1e6;

    /** 固定 SCIP 随机化种子，让 Phase2 装配结果在相同输入下可复现。 */
    private static final String SCIP_DETERMINISTIC_PARAMS =
            "randomization/randomseedshift = 0\n"
          + "randomization/permutationseed = 0\n"
          + "randomization/lpseed = 0\n";

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
        SolveResult seedResult = buildSeedResult(solution, seedAssignments, data);
        if (seedResult != null) {
            int seedBlocks = seedResult.assignments().values().stream().mapToInt(List::size).sum();
            log.info("Phase2 seed baseline: patterns={} blocks={} rolls={}",
                    seedResult.solution().size(), seedBlocks, data.incumbentRolls());
        }
        SolveResult bestFallback = seedResult;

        // Primary path: one-shot global set-partition over a structured, size-capped
        // column pool. Each column is a full-roll config, so minimizing Σy directly
        // minimizes sequence groups. Unlike per-pattern, this re-allocates demand
        // across patterns. Pattern roll counts stay fixed, so waste/over-production
        // are unaffected — it can only reduce groups, never regress.
        boolean hasSeed = seedAssignments != null && !seedAssignments.isEmpty();
        long spTimeLimit = hasSeed ? SP_TIME_LIMIT_SEEDED_MS : SP_TIME_LIMIT_UNSEEDED_MS;
        List<Column> pool = buildStructuredColumnPool(data, seedAssignments);
        if (!pool.isEmpty()) {
            SolveResult global = solveGlobalSetPartition(pool, data, solution, startTime, spTimeLimit);
            if (global != null && activeBlockCount(global) < activeBlockCount(bestFallback)) {
                log.info("Phase2 global set-partition improved: blocks {} -> {} columns={} elapsed={}ms",
                        activeBlockCount(bestFallback), activeBlockCount(global), pool.size(),
                        System.currentTimeMillis() - startTime);
                return global;
            }
        }

        // Secondary fallback: per-pattern independent solve. Cannot re-allocate across
        // patterns, but is a robust local improver when the global model finds nothing.
        SolveResult result = solvePerPatternIndependently(solution, seedAssignments, data, startTime);
        if (result != null && activeBlockCount(result) < activeBlockCount(bestFallback)) {
            log.info("Phase2 per-pattern improved: blocks {} -> {} elapsed={}ms",
                    activeBlockCount(bestFallback), activeBlockCount(result),
                    System.currentTimeMillis() - startTime);
            return result;
        }
        log.info("Phase2: no improvement over seed ({}ms), returning seed",
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

    /**
     * Builds a structured, size-capped column pool for the global set-partition MIP.
     *
     * <p>Three column families, none of which take the cross-width Cartesian product
     * that made unbounded column generation explode:
     * <ol>
     *   <li><b>Seed columns</b> — the Stage5 assignment, so the MIP can always
     *       reproduce the current result (a natural lower bound, never regresses).</li>
     *   <li><b>Pure-roll columns</b> — each width filled with a single order, paired
     *       across widths by demand rank (rank0×rank0, …), top-M orders per width.</li>
     *   <li><b>Remainder columns</b> — for multi-slot widths (k≥2), the "j slots of a
     *       secondary order + main order fills the rest" configs that absorb the
     *       non-divisible remainder into few shared rolls.</li>
     * </ol>
     */
    private List<Column> buildStructuredColumnPool(
            Phase2Data data,
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> seedAssignments) {
        List<Column> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int seedColumns = addSeedColumns(columns, seen, data, seedAssignments);
        if (seedColumns > 0) {
            log.info("Phase2 pool seed columns={}", seedColumns);
        }

        for (int patternIndex = 0; patternIndex < data.patterns().size(); patternIndex++) {
            PatternCandidate pattern = data.patterns().get(patternIndex);
            if (!isPatternMessageCovered(pattern, data.messagesByWidth())) {
                continue;
            }

            int patternStart = columns.size();

            // (2) Pure-roll columns: each width = one order, paired by demand rank.
            for (int rank = 0; rank < POOL_TOP_MESSAGES_PER_WIDTH; rank++) {
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

            // (3) Remainder columns: absorb non-divisible leftovers on multi-slot widths.
            Map<Integer, List<String>> baseConfig = topMessageConfig(pattern, data.messagesByWidth());
            outer:
            for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                int width = cut.getKey();
                int slots = cut.getValue();
                if (slots <= 1) {
                    continue;
                }
                List<MessageDemand> messages = data.messagesByWidth().getOrDefault(width, Collections.emptyList());
                if (messages.isEmpty()) {
                    continue;
                }
                String mainMessage = messages.get(0).message();
                int orderLimit = Math.min(POOL_REMAINDER_TOP_ORDERS, messages.size());
                for (int orderRank = 1; orderRank < orderLimit; orderRank++) {
                    String secondary = messages.get(orderRank).message();
                    for (int j = 1; j < slots; j++) {
                        Map<Integer, List<String>> mixed = copyConfig(baseConfig);
                        List<String> slotMessages = new ArrayList<>(slots);
                        for (int s = 0; s < j; s++) slotMessages.add(secondary);
                        for (int s = j; s < slots; s++) slotMessages.add(mainMessage);
                        mixed.put(width, slotMessages);
                        addColumn(columns, seen, createColumn(patternIndex, pattern, mixed, data.demands()));
                        if (columns.size() - patternStart >= POOL_MAX_COLUMNS_PER_PATTERN) {
                            break outer;
                        }
                    }
                }
            }
        }

        log.info("Phase2 structured column pool: total={} patterns={}", columns.size(), data.patterns().size());
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

    // ── Global set-partition MIP ──────────────────────────────────────────────

    /**
     * One-shot global set-partition over the structured column pool.
     *
     * <p>min Σ y[col]  (active configs = sequence groups)
     * <br>s.t. Σ contrib[col][order]·n[col] + slack[order] = demand[order]  (exact, no over-production)
     * <br>     Σ_{col∈pattern p} n[col] = usage[p]                          (A-layer roll counts fixed)
     * <br>     n[col] ≤ usage[p]·y[col]                                     (linking)
     *
     * <p>The per-order slack (heavily penalized) only guarantees feasibility; the
     * seed columns make a zero-slack solution always reachable, so a non-zero slack
     * result is discarded in favour of the seed.
     */
    private SolveResult solveGlobalSetPartition(List<Column> columns,
                                                Phase2Data data,
                                                Map<PatternCandidate, Integer> solution,
                                                long startTime,
                                                long timeLimitMs) {
        if (columns.isEmpty()) {
            return null;
        }
        try {
            MPSolver solver = null;
            try { solver = MPSolver.createSolver("SCIP"); } catch (Exception ignored) {}
            if (solver != null) {
                solver.setSolverSpecificParametersAsString(SCIP_DETERMINISTIC_PARAMS);
            } else {
                try { solver = MPSolver.createSolver("CBC"); } catch (Exception ignored2) {}
            }
            if (solver == null) return null;

            Map<PatternCandidate, List<Integer>> colsByPattern = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                colsByPattern.computeIfAbsent(columns.get(i).pattern(), k -> new ArrayList<>()).add(i);
            }

            List<MPVariable> nVars = new ArrayList<>();
            List<MPVariable> yVars = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                int cap = Math.max(0, solution.getOrDefault(columns.get(i).pattern(), 0));
                nVars.add(solver.makeIntVar(0, cap, "n" + i));
                yVars.add(solver.makeBoolVar("y" + i));
            }

            // Demand: exact equality with a penalized slack for guaranteed feasibility.
            List<MPVariable> slackVars = new ArrayList<>();
            for (Map.Entry<DemandKey, Integer> e : data.demands().entrySet()) {
                DemandKey key = e.getKey();
                MPVariable slack = solver.makeNumVar(0, e.getValue(), "sp_" + key.hashCode());
                slackVars.add(slack);
                MPConstraint c = solver.makeConstraint(e.getValue(), e.getValue(), "d_" + key.hashCode());
                for (int i = 0; i < columns.size(); i++) {
                    int contrib = columns.get(i).contributions().getOrDefault(key, 0);
                    if (contrib > 0) c.setCoefficient(nVars.get(i), contrib);
                }
                c.setCoefficient(slack, 1.0);
            }

            // Per-pattern roll count fixed to the A-layer solution: waste/over unchanged.
            for (Map.Entry<PatternCandidate, List<Integer>> pe : colsByPattern.entrySet()) {
                Integer usage = solution.get(pe.getKey());
                if (usage == null || usage <= 0) continue;
                MPConstraint pc = solver.makeConstraint(usage, usage,
                        "pat_" + Math.abs(pe.getKey().signature().hashCode()));
                for (int idx : pe.getValue()) {
                    pc.setCoefficient(nVars.get(idx), 1.0);
                }
            }

            // Linking: n[col] <= usage[p] * y[col]
            for (int i = 0; i < columns.size(); i++) {
                int cap = Math.max(1, solution.getOrDefault(columns.get(i).pattern(), 0));
                MPConstraint link = solver.makeConstraint(-solver.infinity(), 0, "lk" + i);
                link.setCoefficient(nVars.get(i), 1.0);
                link.setCoefficient(yVars.get(i), -(double) cap);
            }

            // Objective: min Σ y[col] + tiny tie-breakers + heavy slack penalty.
            MPObjective obj = solver.objective();
            for (int i = 0; i < columns.size(); i++) {
                obj.setCoefficient(yVars.get(i), 1.0);
                obj.setCoefficient(nVars.get(i),
                        ROLL_TIE_BREAKER + columns.get(i).waste() * WASTE_TIE_BREAKER);
            }
            for (MPVariable slack : slackVars) {
                obj.setCoefficient(slack, SLACK_PENALTY_SP);
            }
            obj.setMinimization();

            solver.setTimeLimit(timeLimitMs);
            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Phase2 global set-partition failed: {} ({}ms), columns={}",
                        status, timeLimitMs, columns.size());
                return null;
            }

            double totalSlack = slackVars.stream().mapToDouble(MPVariable::solutionValue).sum();
            if (totalSlack > 0.5) {
                log.info("Phase2 global set-partition left slack={} (exact unreachable), falling back to seed",
                        totalSlack);
                return null;
            }

            log.info("Phase2 global set-partition completed: {} columns={}", status, columns.size());
            return extractSolveResult(columns, nVars);
        } catch (Exception ex) {
            log.error("Phase2 global set-partition exception", ex);
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
            if (solver != null) {
                solver.setSolverSpecificParametersAsString(SCIP_DETERMINISTIC_PARAMS);
            } else {
                try { solver = MPSolver.createSolver("CBC"); } catch (Exception ignored2) {}
            }
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

}
