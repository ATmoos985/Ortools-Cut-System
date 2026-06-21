package test.demo.apsmodule.generator.NewSolver.mip;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.model.SolverResult;
import test.demo.apsmodule.generator.NewSolver.scoring.PatternAlignmentScorer;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Final integer solve for the NewSolver pipeline.
 *
 * Goal order:
 * 1. Keep the solve feasible without under-production.
 * 2. Minimize total real waste.
 * 3. Minimize pattern count inside the protected waste band.
 */
public class MultiStageMIPSolver {

    private static final Logger log = LoggerFactory.getLogger(MultiStageMIPSolver.class);
    private static final int MAX_SOLVE_ATTEMPTS = 3;
    private static final double WASTE_PROTECTION_RATIO = 0.10;
    private static final int MIN_WASTE_PROTECTION_MM = 200;

    /**
     * 固定 SCIP 随机化种子，保证相同输入 → 相同解。没有这一步，分支定界轨迹
     * （以及命中时限时的当前 incumbent）每次都不同，diverse 花型集随之漂移。
     * 复现性 = 固定种子 + 确定性时间预算（见 generateDiverseSolutions）+ 确定性花型池。
     */
    private static final String SCIP_DETERMINISTIC_PARAMS =
            "randomization/randomseedshift = 0\n"
          + "randomization/permutationseed = 0\n"
          + "randomization/lpseed = 0\n";

    /**
     * SCIP randomization params for the configured A-layer seed (see
     * {@link SolverParameters#getALayerScipSeed()}). Seed 0 reproduces the legacy
     * {@link #SCIP_DETERMINISTIC_PARAMS}.
     */
    private String scipParams() {
        int s = params.getALayerScipSeed();
        if (s == 0) {
            return SCIP_DETERMINISTIC_PARAMS;
        }
        return "randomization/randomseedshift = " + s + "\n"
             + "randomization/permutationseed = " + s + "\n"
             + "randomization/lpseed = " + s + "\n";
    }

    /** diverse 每步 refine 的固定预算（去掉 wall-clock 依赖，保证复现）。 */
    private static final long REFINE_BUDGET_MS = 15_000L;
    /** diverse 循环的安全护栏：仅在确实逼近 deadline 时才提前停止。 */
    private static final long DIVERSE_MIN_REMAINING_MS = 3_000L;

    private final SolverParameters params;

    public MultiStageMIPSolver(SolverParameters params) {
        this.params = params;
    }

    public SolverResult solve(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        return solve(patterns, demands, allowOverSet, java.util.Collections.emptyList());
    }

    public SolverResult solve(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            List<SolverOrderItem> groupItems) {
        long startTime = System.currentTimeMillis();

        List<SolveCandidate> candidates = solveCandidates(patterns, demands, allowOverSet, groupItems);
        if (candidates.isEmpty()) {
            return SolverResult.failure(System.currentTimeMillis() - startTime);
        }

        return candidates.get(0).result();
    }

    public List<SolveCandidate> solveCandidates(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        return solveCandidates(patterns, demands, allowOverSet, java.util.Collections.emptyList());
    }

    public List<SolveCandidate> solveCandidates(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            List<SolverOrderItem> groupItems) {
        long startTime = System.currentTimeMillis();

        List<NamedSolution> finalSolutions = solvePrimaryPatternSelection(
                patterns, demands, allowOverSet, groupItems);
        if (finalSolutions.isEmpty()) {
            return Collections.emptyList();
        }

        long solveTimeMs = System.currentTimeMillis() - startTime;
        List<SolveCandidate> candidates = new ArrayList<>();
        for (NamedSolution solution : finalSolutions) {
            int totalRolls = solution.solution().values().stream().mapToInt(Integer::intValue).sum();
            int totalWaste = calculateTotalWaste(solution.solution());
            int totalOver = calculateTotalOver(solution.solution(), demands);
            candidates.add(new SolveCandidate(
                    solution.name(),
                    new SolverResult(solution.solution(), totalRolls, totalWaste, totalOver, solveTimeMs)));
        }
        return candidates;
    }

    /**
     * Legacy primary only (no diverse search). Used by multi-order multi-start for
     * alternate orders: each order's primary is what wins, and its diverse alternatives
     * never beat the primary, so skipping them keeps extra orders cheap (~legacy time).
     */
    public SolveCandidate solvePrimaryOnly(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        return solvePrimaryOnly(patterns, demands, allowOverSet, params.getALayerScipSeed());
    }

    /**
     * Legacy primary with an explicit A-layer SCIP seed. The seed steers the
     * selection MIP onto a different (but reproducible) 花型集 among tie-degenerate
     * optima, which is the cheap multi-start dimension that finds lower-group sets.
     */
    public SolveCandidate solvePrimaryOnly(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int seed) {
        SolverParameters seededParams = params.copy();
        seededParams.setALayerScipSeed(seed);
        LegacyOrderPatternSelectionSolver legacySolver = new LegacyOrderPatternSelectionSolver(seededParams);
        List<LegacyOrderPatternSelectionSolver.Result> results = legacySolver.solveCandidates(
                patterns, demands, allowOverSet);
        if (results.isEmpty()) {
            return null;
        }
        Map<PatternCandidate, Integer> sol = results.get(0).solution();
        int rolls = sol.values().stream().mapToInt(Integer::intValue).sum();
        int waste = calculateTotalWaste(sol);
        int over = calculateTotalOver(sol, demands);
        return new SolveCandidate(results.get(0).name(), new SolverResult(sol, rolls, waste, over, 0L));
    }

    private static final long DIVERSE_RESERVE_MS = 35_000L;

    private List<NamedSolution> solvePrimaryPatternSelection(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            List<SolverOrderItem> groupItems) {
        long deadlineMs = System.currentTimeMillis() + params.getTimeoutMs();
        long legacyDeadlineMs = deadlineMs - DIVERSE_RESERVE_MS;

        LegacyOrderPatternSelectionSolver legacySolver = new LegacyOrderPatternSelectionSolver(params);
        List<LegacyOrderPatternSelectionSolver.Result> legacySolutions = legacySolver.solveCandidates(
                patterns, demands, allowOverSet, legacyDeadlineMs);

        if (!legacySolutions.isEmpty()) {
            // Convert legacy solutions to NamedSolution list
            List<NamedSolution> allCandidates = new ArrayList<>();
            for (LegacyOrderPatternSelectionSolver.Result r : legacySolutions) {
                addSolutionCandidate(allCandidates, r.name(), r.solution());
            }

            // Use best legacy waste as the hard upper bound for diversity search
            int bestWaste = allCandidates.stream()
                    .mapToInt(s -> calculateTotalWaste(s.solution()))
                    .min().orElse(Integer.MAX_VALUE);
            Map<PatternCandidate, Integer> primarySolution = allCandidates.get(0).solution();
            int primaryOver = calculateTotalOver(primarySolution, demands);

            long remaining = deadlineMs - System.currentTimeMillis();
            if (remaining > 5000) {
                log.info("Legacy succeeded (waste={}mm), searching for diverse alternatives...", bestWaste);
                List<NamedSolution> diverse = generateDiverseSolutions(
                        patterns, demands, allowOverSet, primaryOver, bestWaste,
                        primarySolution, deadlineMs, groupItems);
                // Add any diverse candidates not already present
                for (NamedSolution d : diverse) {
                    if (!d.name().equals(allCandidates.get(0).name())) {
                        addSolutionCandidate(allCandidates, d.name(), d.solution());
                    }
                }
            }

            log.info("Pattern selection produced {} candidate(s)", allCandidates.size());
            return allCandidates;
        }

        log.warn("Legacy-order pattern selection failed, falling back to waste-first pattern selection");
        return solveFinalMIPWithRepair(patterns, demands, allowOverSet, params.getTopK(), deadlineMs);
    }

    private List<NamedSolution> solveFinalMIPWithRepair(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int currentTopK,
            long deadlineMs) {
        Set<String> seen = patterns.stream()
                .map(PatternCandidate::signature)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Integer, Integer> lastUnderByWidth = Collections.emptyMap();

        for (int attempt = 0; attempt < MAX_SOLVE_ATTEMPTS; attempt++) {
            Set<Integer> workingAllowOverSet = buildAllowOverSetForAttempt(
                    attempt, demands, allowOverSet, currentTopK);

            if (attempt > 0) {
                int repaired = repairPatternsForMIP(patterns, seen, demands, workingAllowOverSet, lastUnderByWidth);
                log.info("Repair step added {} patterns for under-produced widths {}", repaired, lastUnderByWidth.keySet());
            }

            log.info("--- Final integer MIP attempt {}/{} ---", attempt + 1, MAX_SOLVE_ATTEMPTS);
            log.debug("Patterns: {}, allowOver widths: {}", patterns.size(), workingAllowOverSet.size());

            long remaining = deadlineMs - System.currentTimeMillis();
            long stage1Time = Math.max(5000, remaining - 25000);
            Stage1SolveResult stage1Result = solveMIPStage1(patterns, demands, workingAllowOverSet, stage1Time);
            if (stage1Result == null) {
                log.warn("Stage1 feasibility solve failed on attempt {}/{}", attempt + 1, MAX_SOLVE_ATTEMPTS);
                continue;
            }

            if (stage1Result.totalUnder() > 0) {
                lastUnderByWidth = stage1Result.underByWidth();
                log.warn("Stage1 found under-production: totalUnder={} details={}",
                        stage1Result.totalUnder(), formatWidthMap(stage1Result.underByWidth()));
                continue;
            }

            log.info("Stage1 result: totalOver={}", stage1Result.totalOver());

            remaining = deadlineMs - System.currentTimeMillis();
            long stage2Time = Math.max(5000, remaining - 15000);
            Map<PatternCandidate, Integer> stage2Solution = solveMIPStage2BestWaste(
                    patterns, demands, workingAllowOverSet, stage1Result.totalOver(),
                    stage1Result.solution(), stage2Time);
            if (stage2Solution == null || stage2Solution.isEmpty()) {
                log.warn("Stage2 best-waste solve failed on attempt {}/{}", attempt + 1, MAX_SOLVE_ATTEMPTS);
                continue;
            }

            int bestWaste = calculateTotalWaste(stage2Solution);
            log.info("Stage2 result: bestWaste={}mm", bestWaste);

            List<NamedSolution> candidates = generateDiverseSolutions(
                    patterns, demands, workingAllowOverSet, stage1Result.totalOver(),
                    bestWaste, stage2Solution, deadlineMs, java.util.Collections.emptyList());

            if (!candidates.isEmpty()) {
                printSolutionSummary(candidates.get(0).solution(), demands);
                return candidates;
            }
        }

        if (!lastUnderByWidth.isEmpty()) {
            log.error("Final integer MIP failed after {} attempts. Remaining under-production: {}",
                    MAX_SOLVE_ATTEMPTS, formatWidthMap(lastUnderByWidth));
        } else {
            log.error("Final integer MIP failed after {} attempts", MAX_SOLVE_ATTEMPTS);
        }
        return Collections.emptyList();
    }

    private List<NamedSolution> generateDiverseSolutions(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int bestWaste,
            Map<PatternCandidate, Integer> primarySolution,
            long deadlineMs,
            List<SolverOrderItem> groupItems) {

        Map<PatternCandidate, Double> alignmentScores = groupItems.isEmpty()
                ? java.util.Collections.emptyMap()
                : PatternAlignmentScorer.score(patterns, groupItems);

        List<NamedSolution> solutions = new ArrayList<>();
        addSolutionCandidate(solutions, "best-waste", primarySolution);

        // primary（best-waste/legacy）的母卷数 = 最终选择 Priority-1 的基准线。
        // diverse 候选若多用 1 卷，会在 rolls 这关被直接淘汰，序号组根本轮不到比较，
        // 故 diverse 搜索必须把母卷数压到该基准，让对齐度只在同等卷数内重排花型。
        int primaryRolls = primarySolution.values().stream().mapToInt(Integer::intValue).sum();

        for (int k = 1; k < 5; k++) {
            long remaining = deadlineMs - System.currentTimeMillis();
            if (remaining < DIVERSE_MIN_REMAINING_MS) {
                log.info("Stopping diversity search: only {}ms remaining", remaining);
                break;
            }
            // 固定预算（仅依赖配置，不读 wall-clock），保证 diverse 花型集可复现。
            // 跑满 4 步需 timeoutMs 充足（≈ Legacy 预算 + 4×(stage4 + refine)）；
            // 用户当前 ~340s 档已满足。timeout 偏紧时 remaining 护栏会确定性地提前收尾。
            long diverseTime = params.getStage4TimeLimit();
            Map<PatternCandidate, Integer> diverse = solveMIPDiverseAlternative(
                    patterns, demands, allowOverSet, maxTotalOver, bestWaste, solutions, diverseTime,
                    alignmentScores, primaryRolls);

            if (diverse != null && !diverse.isEmpty()) {
                String name = "diverse-" + k;
                addSolutionCandidate(solutions, name, diverse);
                log.info("Diversity candidate {}: patterns={}, waste={}mm",
                        name, diverse.size(), calculateTotalWaste(diverse));
                // Refine with Stage4 to reduce single/double-car patterns.
                // This is a critical step — Stage4 refine typically reduces patterns by 10-15,
                // which directly improves sequence group count.
                long remainAfterDiverse = deadlineMs - System.currentTimeMillis();
                // 固定 refine 预算（去 wall-clock 化）；仅在确实逼近 deadline 时跳过。
                long refineBudget = REFINE_BUDGET_MS;
                if (remainAfterDiverse > DIVERSE_MIN_REMAINING_MS) {
                    try {
                        LegacyOrderPatternSelectionSolver refiner = new LegacyOrderPatternSelectionSolver(params);
                        List<PatternCandidate> diversePats = new ArrayList<>(diverse.keySet());
                        // Lock maxRolls to the diverse solution's actual rolls — must not increase roll count
                        int diverseRolls = diverse.values().stream().mapToInt(Integer::intValue).sum();
                        int diverseWaste = calculateTotalWaste(diverse);
                        log.info("Stage4 refine {}: budget={}ms, maxRolls={}, maxWaste={}",
                                name, refineBudget, diverseRolls, diverseWaste);
                        Map<PatternCandidate, Integer> refined = refiner.solveMIPStage4(
                                diversePats, demands, allowOverSet, maxTotalOver, diverseRolls, diverseWaste, refineBudget);
                        if (refined != null && !refined.isEmpty()) {
                            addSolutionCandidate(solutions, name + "-s4", refined);
                            int refinedRolls = refined.values().stream().mapToInt(Integer::intValue).sum();
                            log.info("Stage4 refined {}: patterns={}, rolls={}, waste={}mm",
                                    name, refined.size(), refinedRolls, calculateTotalWaste(refined));
                        } else {
                            log.info("Stage4 refine {} returned empty", name);
                        }
                    } catch (Exception ex) {
                        log.warn("Stage4 refinement of {} failed: {}", name, ex.getMessage());
                    }
                } else {
                    log.info("Skipping Stage4 refine for {}: only {}ms remaining before deadline", name, remainAfterDiverse);
                }
            } else {
                log.info("Could not find diversity candidate {}, stopping", k);
                break;
            }
        }

        return solutions;
    }

    private Map<PatternCandidate, Integer> solveMIPDiverseAlternative(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxWaste,
            List<NamedSolution> existingSolutions,
            long timeLimitMs,
            Map<PatternCandidate, Double> alignmentScores,
            int maxRolls) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) return null;

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int totalOverCap = params.getTotalOverCap();
            int maxTotalRolls = totalDemand + totalOverCap;

            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            for (int width : demands.keySet()) {
                double overUb = allowOverSet.contains(width) ? totalOverCap : 0;
                overVars.put(width, solver.makeNumVar(0, overUb, "over_" + width));
            }

            for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
                int width = demandEntry.getKey();
                int demand = demandEntry.getValue();
                MPConstraint constraint = solver.makeConstraint(demand, demand, "demand_" + width);
                for (int i = 0; i < patterns.size(); i++) {
                    int count = patterns.get(i).getPattern().getOrDefault(width, 0);
                    if (count > 0) constraint.setCoefficient(xVars.get(i), count);
                }
                constraint.setCoefficient(overVars.get(width), -1);
            }

            MPConstraint totalOverConstraint = solver.makeConstraint(0, maxTotalOver, "totalOverCap");
            for (MPVariable overVar : overVars.values()) totalOverConstraint.setCoefficient(overVar, 1);

            // 把母卷数压到 primary 同等水平（maxRolls）：对齐度不得以多开一卷为代价，
            // 否则候选会在最终选择的 Priority-1(rolls) 上被直接淘汰。
            int rollsUpperBound = Math.max(1, Math.min(maxTotalRolls, maxRolls));
            MPConstraint rollsCap = solver.makeConstraint(0, rollsUpperBound, "rollsCap");
            for (MPVariable xVar : xVars) rollsCap.setCoefficient(xVar, 1);

            // Waste bound with protection band — allows slightly worse waste for better pattern diversity
            int wasteSlack = Math.max(MIN_WASTE_PROTECTION_MM, (int) (maxWaste * WASTE_PROTECTION_RATIO));
            MPConstraint wasteCap = solver.makeConstraint(0, maxWaste + wasteSlack, "wasteCap");
            for (int i = 0; i < patterns.size(); i++) {
                wasteCap.setCoefficient(xVars.get(i), patterns.get(i).getRealWaste(params.getTotalWidth()));
            }

            // Diversity penalty: penalise patterns heavily used in existing solutions.
            // Weight (0.5mm) is tiny relative to typical waste differences between patterns,
            // so the waste bound is preserved while the solver explores different pattern mixes.
            Map<PatternCandidate, Double> totalUsage = new HashMap<>();
            for (NamedSolution sol : existingSolutions) {
                for (Map.Entry<PatternCandidate, Integer> e : sol.solution().entrySet()) {
                    totalUsage.merge(e.getKey(), (double) e.getValue(), Double::sum);
                }
            }
            double maxUsage = totalUsage.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            double DIVERSITY_WEIGHT = 0.5;
            // 对齐度主导强度。每卷废边 80~380mm，旧值 20 比废边小一个量级，对齐度被
            // 完全压制（这就是「PatternAlignmentScorer 没真正主导 diverse 选择」的根因）。
            // 提到 300 后，满分对齐(=1.0)≈300mm 等效收益/卷，足以在废边带内主导花型选择，
            // 偏向序号对齐度高的花型集（即能拼到 ~77 的那一类）。
            // 安全性：wasteCap 硬约束把总废边锁在 best+10% 带内，alpha 再大也吹不飞废边，
            // 只决定「在保护带内多激进地换取对齐度」。这是本次主要可调旋钮。
            double ALIGNMENT_ALPHA = 300.0;
            double KW_PENALTY_PER_EXTRA_SLOT = 15.0;

            MPObjective objective = solver.objective();
            for (int i = 0; i < patterns.size(); i++) {
                double waste = patterns.get(i).getRealWaste(params.getTotalWidth());
                double penalty = totalUsage.getOrDefault(patterns.get(i), 0.0) / maxUsage * DIVERSITY_WEIGHT;
                double alignBonus = alignmentScores.getOrDefault(patterns.get(i), 0.0) * ALIGNMENT_ALPHA;
                // Penalise patterns where any width appears more than once per roll.
                // Each extra slot (kw - 1) forces the postprocessor to handle intra-roll
                // multi-slot pairing, which tends to create extra sequence group transitions.
                double kwPenalty = 0.0;
                for (int kw : patterns.get(i).getPattern().values()) {
                    if (kw > 1) kwPenalty += (kw - 1) * KW_PENALTY_PER_EXTRA_SLOT;
                }
                objective.setCoefficient(xVars.get(i), waste + penalty - alignBonus + kwPenalty);
            }
            objective.setMinimization();

            solver.setTimeLimit(Math.max(1000, timeLimitMs));
            MPSolver.ResultStatus status = solver.solve();

            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.debug("Diverse solve returned {}", status);
                return null;
            }

            return extractSolution(patterns, xVars);
        } catch (Exception e) {
            log.error("Diverse solve failed", e);
            return null;
        }
    }

    private Map<PatternCandidate, Integer> refineStage3PatternCount(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxTotalWaste,
            Map<PatternCandidate, Integer> currentSolution) {
        if (currentSolution == null || currentSolution.isEmpty() || currentSolution.size() <= 1) {
            return null;
        }

        long refinementBudgetMs = Math.min(12_000L, Math.max(2_000L, params.getStage4TimeLimit() / 2));
        long deadlineMs = System.currentTimeMillis() + refinementBudgetMs;
        Map<PatternCandidate, Integer> bestSolution = currentSolution;
        Map<PatternCandidate, Integer> hintSolution = currentSolution;

        for (int targetPatternCount = currentSolution.size() - 1; targetPatternCount >= 1; targetPatternCount--) {
            long remainingMs = deadlineMs - System.currentTimeMillis();
            if (remainingMs < 1_500L) {
                break;
            }

            long timeLimitMs = Math.min(3_000L, remainingMs);
            Map<PatternCandidate, Integer> candidate = solveMIPStage3WithPatternCap(
                    patterns,
                    demands,
                    allowOverSet,
                    maxTotalOver,
                    maxTotalWaste,
                    targetPatternCount,
                    hintSolution,
                    timeLimitMs);
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (candidate.size() < bestSolution.size()) {
                bestSolution = candidate;
                hintSolution = candidate;
            }
        }

        return bestSolution.size() < currentSolution.size() ? bestSolution : null;
    }

    private Set<Integer> buildAllowOverSetForAttempt(int attempt,
            Map<Integer, Integer> demands,
            Set<Integer> baseAllowOverSet,
            int currentTopK) {
        if (attempt == 0) {
            return mergeForcedAllowOverSet(new HashSet<>(baseAllowOverSet));
        }
        if (attempt == 1) {
            return mergeForcedAllowOverSet(expandAllowOverSet(demands, currentTopK + 2));
        }
        log.warn("Waste-first pattern selection entering emergency relaxed-over fallback on all demand widths");
        return mergeForcedAllowOverSet(new HashSet<>(demands.keySet()));
    }

    private Set<Integer> mergeForcedAllowOverSet(Set<Integer> allowOverSet) {
        Set<Integer> merged = new HashSet<>(allowOverSet);
        merged.addAll(params.getForceAllowOverWidths());
        return merged;
    }

    private int repairPatternsForMIP(List<PatternCandidate> patterns,
            Set<String> seen,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            Map<Integer, Integer> underByWidth) {
        if (underByWidth == null || underByWidth.isEmpty()) {
            return 0;
        }

        int repaired = 0;
        List<Integer> allWidths = new ArrayList<>(demands.keySet());
        allWidths.sort((a, b) -> Integer.compare(demands.get(b), demands.get(a)));

        List<Integer> targetWidths = underByWidth.entrySet().stream()
                .sorted((a, b) -> {
                    int byUnder = Integer.compare(b.getValue(), a.getValue());
                    if (byUnder != 0) {
                        return byUnder;
                    }
                    return Integer.compare(demands.getOrDefault(b.getKey(), 0), demands.getOrDefault(a.getKey(), 0));
                })
                .map(Map.Entry::getKey)
                .toList();

        int minRw = params.getMinRollWidth();
        int maxRw = params.getMaxRollWidth();
        int step = params.getStepSize();
        int totalOverCap = params.getTotalOverCap();

        for (int width : targetWidths) {
            List<Integer> coeffs = new ArrayList<>();
            for (PatternCandidate pattern : patterns) {
                int count = pattern.getPattern().getOrDefault(width, 0);
                if (count > 0) {
                    coeffs.add(count);
                }
            }

            boolean needsRepair = false;
            if (coeffs.isEmpty()) {
                needsRepair = true;
            } else {
                int divisor = coeffs.get(0);
                for (int coeff : coeffs) {
                    divisor = gcd(divisor, coeff);
                }
                int capForWidth = allowOverSet.contains(width) ? totalOverCap : 0;
                boolean reachable = false;
                for (int extra = 0; extra <= capForWidth; extra++) {
                    if ((demands.get(width) + extra) % divisor == 0) {
                        reachable = true;
                        break;
                    }
                }
                needsRepair = !reachable;
            }

            if (!needsRepair) {
                continue;
            }

            for (int fillWidth : allWidths) {
                if (fillWidth == width) {
                    continue;
                }

                boolean added = false;
                for (int fillCount = 1; fillCount <= 5; fillCount++) {
                    int patternWidth = width + fillWidth * fillCount;
                    if (patternWidth < minRw) {
                        continue;
                    }
                    if (patternWidth > maxRw) {
                        break;
                    }

                    int rollWidth = ceilToStep(patternWidth, step);
                    rollWidth = Math.max(rollWidth, minRw);
                    rollWidth = Math.min(rollWidth, maxRw);
                    if (patternWidth > rollWidth) {
                        continue;
                    }

                    Map<Integer, Integer> candidatePattern = new LinkedHashMap<>();
                    candidatePattern.put(width, 1);
                    candidatePattern.put(fillWidth, fillCount);

                    PatternCandidate candidate = new PatternCandidate(candidatePattern, rollWidth);
                    if (seen.add(candidate.signature())) {
                        patterns.add(candidate);
                        repaired++;
                        added = true;
                        break;
                    }
                }

                if (added) {
                    break;
                }
            }
        }

        return repaired;
    }

    private Stage1SolveResult solveMIPStage1(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            long timeLimitMs) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) {
                return null;
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int totalOverCap = params.getTotalOverCap();

            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            Map<Integer, MPVariable> underVars = new LinkedHashMap<>();
            for (int width : demands.keySet()) {
                double overUb = allowOverSet.contains(width) ? totalOverCap : 0;
                overVars.put(width, solver.makeNumVar(0, overUb, "over_" + width));
                underVars.put(width, solver.makeNumVar(0, totalDemand, "under_" + width));
            }

            for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
                int width = demandEntry.getKey();
                int demand = demandEntry.getValue();
                MPConstraint constraint = solver.makeConstraint(demand, demand, "demand_" + width);

                for (int i = 0; i < patterns.size(); i++) {
                    int count = patterns.get(i).getPattern().getOrDefault(width, 0);
                    if (count > 0) {
                        constraint.setCoefficient(xVars.get(i), count);
                    }
                }
                constraint.setCoefficient(underVars.get(width), 1);
                constraint.setCoefficient(overVars.get(width), -1);
            }

            MPConstraint totalOverConstraint = solver.makeConstraint(0, totalOverCap, "totalOverCap");
            for (MPVariable overVar : overVars.values()) {
                totalOverConstraint.setCoefficient(overVar, 1);
            }

            MPObjective objective = solver.objective();
            for (MPVariable underVar : underVars.values()) {
                objective.setCoefficient(underVar, params.getUnderPenalty());
            }
            for (MPVariable overVar : overVars.values()) {
                objective.setCoefficient(overVar, 1);
            }
            objective.setMinimization();

            solver.setTimeLimit(Math.max(2000, timeLimitMs));
            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Stage1 returned {}", status);
                return null;
            }

            Map<Integer, Integer> underByWidth = new LinkedHashMap<>();
            int totalUnder = 0;
            int totalOver = 0;
            for (int width : demands.keySet()) {
                int under = (int) Math.round(underVars.get(width).solutionValue());
                int over = (int) Math.round(overVars.get(width).solutionValue());
                if (under > 0) {
                    underByWidth.put(width, under);
                    totalUnder += under;
                }
                totalOver += over;
            }

            return new Stage1SolveResult(extractSolution(patterns, xVars), totalOver, totalUnder, underByWidth);
        } catch (Exception e) {
            log.error("Stage1 failed", e);
            return null;
        }
    }

    private Map<PatternCandidate, Integer> solveMIPStage2BestWaste(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            Map<PatternCandidate, Integer> hintSolution,
            long timeLimitMs) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) {
                return null;
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int totalOverCap = params.getTotalOverCap();
            int maxTotalRolls = totalDemand + totalOverCap;

            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            for (int width : demands.keySet()) {
                double overUb = allowOverSet.contains(width) ? totalOverCap : 0;
                overVars.put(width, solver.makeNumVar(0, overUb, "over_" + width));
            }

            for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
                int width = demandEntry.getKey();
                int demand = demandEntry.getValue();
                MPConstraint constraint = solver.makeConstraint(demand, demand, "demand_" + width);

                for (int i = 0; i < patterns.size(); i++) {
                    int count = patterns.get(i).getPattern().getOrDefault(width, 0);
                    if (count > 0) {
                        constraint.setCoefficient(xVars.get(i), count);
                    }
                }
                constraint.setCoefficient(overVars.get(width), -1);
            }

            MPConstraint totalOverConstraint = solver.makeConstraint(0, maxTotalOver, "totalOverCap");
            for (MPVariable overVar : overVars.values()) {
                totalOverConstraint.setCoefficient(overVar, 1);
            }

            MPConstraint rollsCap = solver.makeConstraint(0, maxTotalRolls, "rollsCap");
            for (MPVariable xVar : xVars) {
                rollsCap.setCoefficient(xVar, 1);
            }

            MPObjective objective = solver.objective();
            for (int i = 0; i < patterns.size(); i++) {
                objective.setCoefficient(xVars.get(i), patterns.get(i).getRealWaste(params.getTotalWidth()));
            }
            objective.setMinimization();

            applyHint(solver, patterns, xVars, null, hintSolution);
            solver.setTimeLimit(Math.max(2000, timeLimitMs));
            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Stage2 returned {}", status);
                return null;
            }

            return extractSolution(patterns, xVars);
        } catch (Exception e) {
            log.error("Stage2 failed", e);
            return null;
        }
    }

    private Map<PatternCandidate, Integer> solveMIPStage3MinPatterns(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxTotalWaste,
            Map<PatternCandidate, Integer> hintSolution) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) {
                return null;
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int totalOverCap = params.getTotalOverCap();
            int maxTotalRolls = totalDemand + totalOverCap;

            List<MPVariable> xVars = new ArrayList<>();
            List<MPVariable> yVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
                yVars.add(solver.makeBoolVar("y_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            for (int width : demands.keySet()) {
                double overUb = allowOverSet.contains(width) ? totalOverCap : 0;
                overVars.put(width, solver.makeNumVar(0, overUb, "over_" + width));
            }

            for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
                int width = demandEntry.getKey();
                int demand = demandEntry.getValue();
                MPConstraint constraint = solver.makeConstraint(demand, demand, "demand_" + width);

                for (int i = 0; i < patterns.size(); i++) {
                    int count = patterns.get(i).getPattern().getOrDefault(width, 0);
                    if (count > 0) {
                        constraint.setCoefficient(xVars.get(i), count);
                    }
                }
                constraint.setCoefficient(overVars.get(width), -1);
            }

            MPConstraint totalOverConstraint = solver.makeConstraint(0, maxTotalOver, "totalOverCap");
            for (MPVariable overVar : overVars.values()) {
                totalOverConstraint.setCoefficient(overVar, 1);
            }

            MPConstraint rollsCap = solver.makeConstraint(0, maxTotalRolls, "rollsCap");
            for (MPVariable xVar : xVars) {
                rollsCap.setCoefficient(xVar, 1);
            }

            MPConstraint wasteCap = solver.makeConstraint(0, maxTotalWaste, "wasteCap");
            for (int i = 0; i < patterns.size(); i++) {
                wasteCap.setCoefficient(xVars.get(i), patterns.get(i).getRealWaste(params.getTotalWidth()));
            }

            for (int i = 0; i < patterns.size(); i++) {
                PatternCandidate pattern = patterns.get(i);
                int maxUsageForPattern = maxTotalRolls;
                for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                    int width = cut.getKey();
                    int count = cut.getValue();
                    if (count <= 0) {
                        continue;
                    }
                    int demand = demands.getOrDefault(width, 0);
                    int allowedOver = allowOverSet.contains(width) ? totalOverCap : 0;
                    maxUsageForPattern = Math.min(maxUsageForPattern, (demand + allowedOver) / count);
                }
                MPConstraint link = solver.makeConstraint(-MPSolver.infinity(), 0, "link_" + i);
                link.setCoefficient(xVars.get(i), 1);
                link.setCoefficient(yVars.get(i), -maxUsageForPattern);
            }

            MPObjective objective = solver.objective();
            for (MPVariable yVar : yVars) {
                objective.setCoefficient(yVar, 1);
            }
            objective.setMinimization();

            long timeLimit = Math.min(params.getStage4TimeLimit(), params.getTimeoutMs());
            applyHint(solver, patterns, xVars, yVars, hintSolution);
            solver.setTimeLimit(timeLimit);

            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Stage3 returned {} after {}ms", status, timeLimit);
                return null;
            }

            return extractSolution(patterns, xVars);
        } catch (Exception e) {
            log.error("Stage3 failed", e);
            return null;
        }
    }

    private Map<PatternCandidate, Integer> solveMIPStage3WithPatternCap(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxTotalWaste,
            int targetPatternCount,
            Map<PatternCandidate, Integer> hintSolution,
            long timeLimitMs) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) {
                return null;
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int totalOverCap = params.getTotalOverCap();
            int maxTotalRolls = totalDemand + totalOverCap;

            List<MPVariable> xVars = new ArrayList<>();
            List<MPVariable> yVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
                yVars.add(solver.makeBoolVar("y_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            for (int width : demands.keySet()) {
                double overUb = allowOverSet.contains(width) ? totalOverCap : 0;
                overVars.put(width, solver.makeNumVar(0, overUb, "over_" + width));
            }

            for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
                int width = demandEntry.getKey();
                int demand = demandEntry.getValue();
                MPConstraint constraint = solver.makeConstraint(demand, demand, "demand_" + width);

                for (int i = 0; i < patterns.size(); i++) {
                    int count = patterns.get(i).getPattern().getOrDefault(width, 0);
                    if (count > 0) {
                        constraint.setCoefficient(xVars.get(i), count);
                    }
                }
                constraint.setCoefficient(overVars.get(width), -1);
            }

            MPConstraint totalOverConstraint = solver.makeConstraint(0, maxTotalOver, "totalOverCap");
            for (MPVariable overVar : overVars.values()) {
                totalOverConstraint.setCoefficient(overVar, 1);
            }

            MPConstraint rollsCap = solver.makeConstraint(0, maxTotalRolls, "rollsCap");
            for (MPVariable xVar : xVars) {
                rollsCap.setCoefficient(xVar, 1);
            }

            MPConstraint wasteCap = solver.makeConstraint(0, maxTotalWaste, "wasteCap");
            for (int i = 0; i < patterns.size(); i++) {
                wasteCap.setCoefficient(xVars.get(i), patterns.get(i).getRealWaste(params.getTotalWidth()));
            }

            MPConstraint patternCap = solver.makeConstraint(0, targetPatternCount, "patternCap");
            for (int i = 0; i < patterns.size(); i++) {
                PatternCandidate pattern = patterns.get(i);
                int maxUsageForPattern = maxTotalRolls;
                for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
                    int width = cut.getKey();
                    int count = cut.getValue();
                    if (count <= 0) {
                        continue;
                    }
                    int demand = demands.getOrDefault(width, 0);
                    int allowedOver = allowOverSet.contains(width) ? totalOverCap : 0;
                    maxUsageForPattern = Math.min(maxUsageForPattern, (demand + allowedOver) / count);
                }
                MPConstraint link = solver.makeConstraint(-MPSolver.infinity(), 0, "link_" + i);
                link.setCoefficient(xVars.get(i), 1);
                link.setCoefficient(yVars.get(i), -maxUsageForPattern);
                patternCap.setCoefficient(yVars.get(i), 1);
            }

            MPObjective objective = solver.objective();
            for (int i = 0; i < patterns.size(); i++) {
                objective.setCoefficient(xVars.get(i), patterns.get(i).getRealWaste(params.getTotalWidth()));
            }
            objective.setMinimization();

            applyHint(solver, patterns, xVars, yVars, hintSolution);
            solver.setTimeLimit(timeLimitMs);

            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.debug("Stage3 refinement target={} returned {} after {}ms",
                        targetPatternCount, status, timeLimitMs);
                return null;
            }

            Map<PatternCandidate, Integer> solution = extractSolution(patterns, xVars);
            return solution.size() <= targetPatternCount ? solution : null;
        } catch (Exception e) {
            log.debug("Stage3 refinement target={} failed", targetPatternCount, e);
            return null;
        }
    }

    private void applyHint(MPSolver solver,
            List<PatternCandidate> patterns,
            List<MPVariable> xVars,
            List<MPVariable> yVars,
            Map<PatternCandidate, Integer> hintSolution) {
        if (hintSolution == null || hintSolution.isEmpty()) {
            solver.setHint(new MPVariable[] {}, new double[] {});
            return;
        }

        List<MPVariable> hintVars = new ArrayList<>();
        List<Double> hintValues = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            int usage = hintSolution.getOrDefault(patterns.get(i), 0);
            hintVars.add(xVars.get(i));
            hintValues.add((double) usage);
            if (yVars != null) {
                hintVars.add(yVars.get(i));
                hintValues.add(usage > 0 ? 1.0 : 0.0);
            }
        }

        solver.setHint(
                hintVars.toArray(new MPVariable[0]),
                hintValues.stream().mapToDouble(Double::doubleValue).toArray());
    }

    private void addSolutionCandidate(List<NamedSolution> candidates,
            String name,
            Map<PatternCandidate, Integer> solution) {
        if (solution == null || solution.isEmpty()) {
            return;
        }

        String signature = solutionSignature(solution);
        for (NamedSolution candidate : candidates) {
            if (candidate.signature().equals(signature)) {
                return;
            }
        }

        candidates.add(new NamedSolution(name, new LinkedHashMap<>(solution), signature));
    }

    private String solutionSignature(Map<PatternCandidate, Integer> solution) {
        return solution.entrySet().stream()
                .sorted((left, right) -> left.getKey().signature().compareTo(right.getKey().signature()))
                .map(entry -> entry.getKey().signature() + "=" + entry.getValue())
                .collect(Collectors.joining("|"));
    }

    private Set<Integer> expandAllowOverSet(Map<Integer, Integer> demands, int newTopK) {
        List<Map.Entry<Integer, Integer>> sorted = demands.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .toList();

        Set<Integer> expandedSet = new HashSet<>();
        for (int i = 0; i < Math.min(newTopK, sorted.size()); i++) {
            expandedSet.add(sorted.get(i).getKey());
        }
        log.debug("Expanded allowOverSet (Top-{}): {}", newTopK, expandedSet);
        return expandedSet;
    }

    private int calculateProtectedWasteCap(int bestWaste) {
        int slack = Math.max(MIN_WASTE_PROTECTION_MM, (int) Math.ceil(bestWaste * WASTE_PROTECTION_RATIO));
        return bestWaste + slack;
    }

    private int calculateTotalWaste(Map<PatternCandidate, Integer> solution) {
        return solution.entrySet().stream()
                .mapToInt(entry -> entry.getKey().getRealWaste(params.getTotalWidth()) * entry.getValue())
                .sum();
    }

    private int calculateTotalOver(Map<PatternCandidate, Integer> solution, Map<Integer, Integer> demands) {
        Map<Integer, Integer> production = calculateProduction(solution);
        int totalOver = 0;
        for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
            int produced = production.getOrDefault(demandEntry.getKey(), 0);
            totalOver += Math.max(0, produced - demandEntry.getValue());
        }
        return totalOver;
    }

    private Map<Integer, Integer> calculateProduction(Map<PatternCandidate, Integer> solution) {
        Map<Integer, Integer> produced = new HashMap<>();
        for (Map.Entry<PatternCandidate, Integer> entry : solution.entrySet()) {
            for (Map.Entry<Integer, Integer> cut : entry.getKey().getPattern().entrySet()) {
                produced.merge(cut.getKey(), cut.getValue() * entry.getValue(), Integer::sum);
            }
        }
        return produced;
    }

    private String formatWidthMap(Map<Integer, Integer> widthMap) {
        return widthMap.entrySet().stream()
                .sorted((a, b) -> Integer.compare(a.getKey(), b.getKey()))
                .map(entry -> entry.getKey() + "mm=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private void printSolutionSummary(Map<PatternCandidate, Integer> solution, Map<Integer, Integer> demands) {
        Map<Integer, Integer> produced = calculateProduction(solution);
        int totalRolls = solution.values().stream().mapToInt(Integer::intValue).sum();
        int totalWaste = calculateTotalWaste(solution);
        int totalOver = calculateTotalOver(solution, demands);

        log.info("Final solution summary:");
        log.info("  totalRolls={}", totalRolls);
        log.info("  patternCount={}", solution.size());
        log.info("  totalWaste={}mm", totalWaste);
        log.info("  totalOver={}", totalOver);

        boolean hasUnder = false;
        for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
            int width = demandEntry.getKey();
            int demand = demandEntry.getValue();
            int producedCount = produced.getOrDefault(width, 0);
            int diff = producedCount - demand;
            String status = diff > 0 ? "over+" + diff : (diff < 0 ? "under" + diff : "exact");
            log.info("  {}mm demand={} produced={} [{}]", width, demand, producedCount, status);
            if (diff < 0) {
                hasUnder = true;
            }
        }

        if (hasUnder) {
            log.warn("Final solution still has under-production");
        }
    }

    private int gcd(int a, int b) {
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    private int ceilToStep(int value, int step) {
        return ((value + step - 1) / step) * step;
    }

    private MPSolver createMIPSolver() {
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver != null) {
            // 仅 SCIP 接受该参数串；CBC 回退路径不应用。
            solver.setSolverSpecificParametersAsString(scipParams());
            // 单线程：多线程 MIP 是非确定性的经典来源（线程竞争与种子无关）。
            try { solver.setNumThreads(1); } catch (Exception ignored) {}
            return solver;
        }
        return MPSolver.createSolver("CBC");
    }

    private Map<PatternCandidate, Integer> extractSolution(List<PatternCandidate> patterns, List<MPVariable> xVars) {
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        for (int i = 0; i < patterns.size(); i++) {
            int usage = (int) Math.round(xVars.get(i).solutionValue());
            if (usage > 0) {
                solution.put(patterns.get(i), usage);
            }
        }
        return solution;
    }

    public record SolveCandidate(
            String name,
            SolverResult result) {
    }

    private record NamedSolution(
            String name,
            Map<PatternCandidate, Integer> solution,
            String signature) {
    }

    private record Stage1SolveResult(
            Map<PatternCandidate, Integer> solution,
            int totalOver,
            int totalUnder,
            Map<Integer, Integer> underByWidth) {
    }
}
