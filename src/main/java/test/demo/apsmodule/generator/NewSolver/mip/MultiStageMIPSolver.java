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
import test.demo.apsmodule.generator.NewSolver.util.SolveDiagnostics;
import test.demo.apsmodule.generator.NewSolver.util.SolverDeterminism;

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
    private static final double WASTE_PROTECTION_RATIO = 0.02;
    private static final int MIN_WASTE_PROTECTION_MM = 200;
    private static final long MIN_FALLBACK_STAGE_TIME_LIMIT_MS = 1_000L;

    private final SolverParameters params;

    public MultiStageMIPSolver(SolverParameters params) {
        this.params = params;
    }

    public SolverResult solve(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        long startTime = System.currentTimeMillis();

        List<SolveCandidate> candidates = solveCandidates(patterns, demands, allowOverSet);
        if (candidates.isEmpty()) {
            return SolverResult.failure(System.currentTimeMillis() - startTime);
        }

        return candidates.get(0).result();
    }

    public List<SolveCandidate> solveCandidates(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        long startTime = System.currentTimeMillis();

        List<NamedSolution> finalSolutions = solvePrimaryPatternSelection(
                patterns, demands, allowOverSet);
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

    private List<NamedSolution> solvePrimaryPatternSelection(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        LegacyOrderPatternSelectionSolver legacySolver = new LegacyOrderPatternSelectionSolver(params);
        List<LegacyOrderPatternSelectionSolver.Result> legacySolutions = legacySolver.solveCandidates(
                patterns, demands, allowOverSet);
        if (!legacySolutions.isEmpty()) {
            return legacySolutions.stream()
                    .map(solution -> new NamedSolution(
                            solution.name(),
                            solution.solution(),
                            solutionSignature(solution.solution())))
                    .toList();
        }

        log.warn("Legacy-order pattern selection failed, falling back to waste-first pattern selection");
        return solveFinalMIPWithRepair(patterns, demands, allowOverSet, params.getTopK());
    }

    private List<NamedSolution> solveFinalMIPWithRepair(
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int currentTopK) {

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

            Stage1SolveResult stage1Result = solveMIPStage1(patterns, demands, workingAllowOverSet);
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

            Map<PatternCandidate, Integer> stage2Solution = solveMIPStage2BestWaste(
                    patterns, demands, workingAllowOverSet, stage1Result.totalOver(), stage1Result.solution());
            if (stage2Solution == null || stage2Solution.isEmpty()) {
                log.warn("Stage2 best-waste solve failed on attempt {}/{}", attempt + 1, MAX_SOLVE_ATTEMPTS);
                continue;
            }

            int bestWaste = calculateTotalWaste(stage2Solution);
            int protectedWasteCap = calculateProtectedWasteCap(bestWaste);
            log.info("Stage2 result: bestWaste={}mm, protectedWasteCap={}mm", bestWaste, protectedWasteCap);

            Map<PatternCandidate, Integer> stage3Solution = solveMIPStage3MinPatterns(
                    patterns, demands, workingAllowOverSet, stage1Result.totalOver(), protectedWasteCap, stage2Solution);
            if (stage3Solution == null || stage3Solution.isEmpty()) {
                log.warn("Stage3 pattern minimization failed, falling back to Stage2 best-waste solution");
                stage3Solution = stage2Solution;
            } else {
                log.info("Stage3 result: patternCount={}, totalWaste={}mm",
                        stage3Solution.size(), calculateTotalWaste(stage3Solution));
            }

            List<NamedSolution> candidates = new ArrayList<>();
            Map<PatternCandidate, Integer> refinedStage3Solution = refineStage3PatternCount(
                    patterns,
                    demands,
                    workingAllowOverSet,
                    stage1Result.totalOver(),
                    protectedWasteCap,
                    stage3Solution);
            if (refinedStage3Solution != null && !refinedStage3Solution.isEmpty()) {
                log.info("Stage3 refinement result: patternCount={}, totalWaste={}mm",
                        refinedStage3Solution.size(), calculateTotalWaste(refinedStage3Solution));
                addSolutionCandidate(candidates, "min-pattern-refined", refinedStage3Solution);
            }
            addSolutionCandidate(candidates, "min-pattern", stage3Solution);
            addSolutionCandidate(candidates, "best-waste", stage2Solution);

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

    private Map<PatternCandidate, Integer> refineStage3PatternCount(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxTotalWaste,
            Map<PatternCandidate, Integer> currentSolution) {
        if (currentSolution == null || currentSolution.isEmpty() || currentSolution.size() <= 1) {
            return null;
        }

        long refinementBudgetMs = Math.max(2_000L, params.getStage4TimeLimit() / 2);
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
        allWidths.sort((a, b) -> {
            int byDemand = Integer.compare(demands.get(b), demands.get(a));
            if (byDemand != 0) {
                return byDemand;
            }
            return Integer.compare(a, b);
        });

        List<Integer> targetWidths = underByWidth.entrySet().stream()
                .sorted((a, b) -> {
                    int byUnder = Integer.compare(b.getValue(), a.getValue());
                    if (byUnder != 0) {
                        return byUnder;
                    }
                    int byDemand = Integer.compare(
                            demands.getOrDefault(b.getKey(), 0),
                            demands.getOrDefault(a.getKey(), 0));
                    if (byDemand != 0) {
                        return byDemand;
                    }
                    return Integer.compare(a.getKey(), b.getKey());
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
            Set<Integer> allowOverSet) {
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
                overVars.put(width, solver.makeIntVar(0, overUb, "over_" + width));
                underVars.put(width, solver.makeIntVar(0, totalDemand, "under_" + width));
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

            solver.setTimeLimit(fallbackStageTimeLimitMs());
            long startTime = System.currentTimeMillis();
            MPSolver.ResultStatus status = solver.solve();
            long elapsed = System.currentTimeMillis() - startTime;
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                SolveDiagnostics.recordMip("PATTERN_SELECTION", "mip", "FallbackStage1", status, elapsed,
                        objective.value(), objective.bestBound(),
                        "patterns=" + patterns.size() + ";demands=" + demands.size()
                                + ";stageTimeLimit=" + fallbackStageTimeLimitMs());
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
            SolveDiagnostics.recordMip("PATTERN_SELECTION", "mip", "FallbackStage1", status, elapsed,
                    objective.value(), objective.bestBound(),
                    "patterns=" + patterns.size() + ";demands=" + demands.size()
                            + ";stageTimeLimit=" + fallbackStageTimeLimitMs()
                            + ";totalOver=" + totalOver + ";totalUnder=" + totalUnder);

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
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            for (int width : demands.keySet()) {
                double overUb = allowOverSet.contains(width) ? totalOverCap : 0;
                overVars.put(width, solver.makeIntVar(0, overUb, "over_" + width));
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
            solver.setTimeLimit(fallbackStageTimeLimitMs());
            long startTime = System.currentTimeMillis();
            MPSolver.ResultStatus status = solver.solve();
            long elapsed = System.currentTimeMillis() - startTime;
            SolveDiagnostics.recordMip("PATTERN_SELECTION", "mip", "FallbackStage2BestWaste", status, elapsed,
                    objective.value(), objective.bestBound(),
                    "patterns=" + patterns.size() + ";maxTotalOver=" + maxTotalOver
                            + ";stageTimeLimit=" + fallbackStageTimeLimitMs());
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
                overVars.put(width, solver.makeIntVar(0, overUb, "over_" + width));
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
            for (int i = 0; i < yVars.size(); i++) {
                objective.setCoefficient(yVars.get(i), 1.0);
            }
            objective.setMinimization();

            long timeLimit = Math.min(params.getStage4TimeLimit(), params.getTimeoutMs());
            applyHint(solver, patterns, xVars, yVars, hintSolution);
            solver.setTimeLimit(timeLimit);

            long startTime = System.currentTimeMillis();
            MPSolver.ResultStatus status = solver.solve();
            long elapsed = System.currentTimeMillis() - startTime;
            SolveDiagnostics.recordMip("PATTERN_SELECTION", "mip", "FallbackStage3MinPatterns", status, elapsed,
                    objective.value(), objective.bestBound(),
                    "patterns=" + patterns.size() + ";maxTotalWaste=" + maxTotalWaste + ";timeLimit=" + timeLimit);
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
                overVars.put(width, solver.makeIntVar(0, overUb, "over_" + width));
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

            long startTime = System.currentTimeMillis();
            MPSolver.ResultStatus status = solver.solve();
            long elapsed = System.currentTimeMillis() - startTime;
            SolveDiagnostics.recordMip("PATTERN_SELECTION", "mip", "FallbackStage3Refine", status, elapsed,
                    objective.value(), objective.bestBound(),
                    "patterns=" + patterns.size() + ";targetPatternCount=" + targetPatternCount + ";timeLimit=" + timeLimitMs);
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
                .sorted((a, b) -> {
                    int byDemand = Integer.compare(b.getValue(), a.getValue());
                    if (byDemand != 0) {
                        return byDemand;
                    }
                    return Integer.compare(a.getKey(), b.getKey());
                })
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

    private long fallbackStageTimeLimitMs() {
        return Math.max(MIN_FALLBACK_STAGE_TIME_LIMIT_MS,
                Math.min(params.getStage4TimeLimit(), params.getTimeoutMs()));
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
        if (solver == null) {
            solver = MPSolver.createSolver("CBC");
        }
        SolverDeterminism.configure(solver);
        return solver;
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
