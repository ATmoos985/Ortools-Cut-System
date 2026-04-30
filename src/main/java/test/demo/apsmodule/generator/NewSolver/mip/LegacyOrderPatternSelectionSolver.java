package test.demo.apsmodule.generator.NewSolver.mip;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

class LegacyOrderPatternSelectionSolver {

    private static final Logger log = LoggerFactory.getLogger(LegacyOrderPatternSelectionSolver.class);
    private static final int MAX_REPAIR_ATTEMPTS = 3;
    private static final double LEGACY_SEQ_GROUP_ALPHA = 1.0;
    private static final double LEGACY_SEQ_GROUP_BETA = 0.0;

    private final SolverParameters params;

    LegacyOrderPatternSelectionSolver(SolverParameters params) {
        this.params = params;
    }

    List<Result> solveCandidates(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        Set<String> seen = patterns.stream()
                .map(PatternCandidate::signature)
                .collect(Collectors.toCollection(HashSet::new));
        Set<Integer> baseAllowOverSet = mergeForcedAllowOverWidths(allowOverSet);
        Set<Integer> expandedAllowOverSet = mergeForcedAllowOverWidths(expandAllowOverSet(demands, params.getTopK() + 2));
        Set<Integer> relaxedAllowOverSet = mergeForcedAllowOverWidths(new HashSet<>(demands.keySet()));
        Set<Integer> workingAllowOverSet = mergeForcedAllowOverWidths(allowOverSet);

        for (int attempt = 0; attempt <= MAX_REPAIR_ATTEMPTS; attempt++) {
            log.info("--- Legacy-order pattern selection attempt {}/{} ---",
                    attempt + 1, MAX_REPAIR_ATTEMPTS + 1);

            Map<PatternCandidate, Integer> solution = solveFinalMIP(
                    patterns,
                    demands,
                    workingAllowOverSet);

            if (isFeasible(solution, demands)) {
                return List.of(new Result("legacy-order", new LinkedHashMap<>(solution)));
            }

            if (attempt >= MAX_REPAIR_ATTEMPTS) {
                break;
            }

            int repaired = repairPatterns(patterns, seen, demands, workingAllowOverSet);
            log.info("Legacy-order repair added {} patterns", repaired);
            boolean movedToNextAllowOverTier = false;
            if (workingAllowOverSet.equals(baseAllowOverSet) && !expandedAllowOverSet.equals(baseAllowOverSet)) {
                workingAllowOverSet = expandedAllowOverSet;
                movedToNextAllowOverTier = true;
                log.info("Legacy-order expanded allowOverSet to {}", workingAllowOverSet.size());
            } else if (workingAllowOverSet.equals(expandedAllowOverSet) && !relaxedAllowOverSet.equals(expandedAllowOverSet)) {
                workingAllowOverSet = relaxedAllowOverSet;
                movedToNextAllowOverTier = true;
                log.warn("Legacy-order entering emergency relaxed-over fallback on all demand widths");
            }
            if (repaired == 0 && !movedToNextAllowOverTier) {
                break;
            }
        }

        return Collections.emptyList();
    }

    private Map<PatternCandidate, Integer> solveFinalMIP(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        int[] stage1Result = solveMIPStage1(patterns, demands, allowOverSet);
        if (stage1Result == null) {
            return Collections.emptyMap();
        }

        int optimalOver = Arrays.stream(stage1Result).sum();
        Map<PatternCandidate, Integer> stage2Solution = solveMIPStage2(
                patterns, demands, allowOverSet, optimalOver);
        if (stage2Solution == null || stage2Solution.isEmpty()) {
            return Collections.emptyMap();
        }

        int totalRolls = stage2Solution.values().stream().mapToInt(Integer::intValue).sum();
        Map<PatternCandidate, Integer> stage3Solution = solveMIPStage3(
                patterns, demands, allowOverSet, optimalOver, totalRolls);
        if (stage3Solution == null || stage3Solution.isEmpty()) {
            stage3Solution = stage2Solution;
        }

        int totalWaste = calculateTotalWaste(stage3Solution);
        Map<PatternCandidate, Integer> stage4Solution = solveMIPStage4(
                patterns, demands, allowOverSet, optimalOver, totalRolls, totalWaste);
        if (stage4Solution == null || stage4Solution.isEmpty()) {
            return stage3Solution;
        }
        return stage4Solution;
    }

    private Set<Integer> expandAllowOverSet(Map<Integer, Integer> demands, int newTopK) {
        List<Map.Entry<Integer, Integer>> sorted = demands.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .toList();

        Set<Integer> expandedSet = new HashSet<>();
        for (int i = 0; i < Math.min(newTopK, sorted.size()); i++) {
            expandedSet.add(sorted.get(i).getKey());
        }
        return expandedSet;
    }

    private int[] solveMIPStage1(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) {
                return null;
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            List<Integer> widthList = new ArrayList<>(demands.keySet());

            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + params.getTotalOverCap(), "x_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            Map<Integer, MPVariable> underVars = new LinkedHashMap<>();
            for (int width : widthList) {
                double overUpperBound = allowOverSet.contains(width) ? params.getTotalOverCap() : 0;
                overVars.put(width, solver.makeIntVar(0, overUpperBound, "over_" + width));
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

            MPConstraint overCap = solver.makeConstraint(0, params.getTotalOverCap(), "overCap");
            for (MPVariable overVar : overVars.values()) {
                overCap.setCoefficient(overVar, 1);
            }

            MPObjective objective = solver.objective();
            for (MPVariable underVar : underVars.values()) {
                objective.setCoefficient(underVar, params.getUnderPenalty());
            }
            for (MPVariable overVar : overVars.values()) {
                objective.setCoefficient(overVar, 1);
            }
            objective.setMinimization();

            solver.setTimeLimit(params.getTimeoutMs());
            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Legacy-order Stage1 returned {}", status);
                return null;
            }

            int[] overValues = new int[widthList.size()];
            for (int i = 0; i < widthList.size(); i++) {
                overValues[i] = (int) Math.round(overVars.get(widthList.get(i)).solutionValue());
            }
            return overValues;
        } catch (Exception e) {
            log.error("Legacy-order Stage1 failed", e);
            return null;
        }
    }

    private Map<PatternCandidate, Integer> solveMIPStage2(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) {
                return null;
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();

            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + params.getTotalOverCap(), "x_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            Map<Integer, MPVariable> underVars = new LinkedHashMap<>();
            for (int width : demands.keySet()) {
                double overUpperBound = allowOverSet.contains(width) ? params.getTotalOverCap() : 0;
                overVars.put(width, solver.makeIntVar(0, overUpperBound, "over_" + width));
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

            MPConstraint overCap = solver.makeConstraint(0, maxTotalOver, "overCap");
            for (MPVariable overVar : overVars.values()) {
                overCap.setCoefficient(overVar, 1);
            }

            MPObjective objective = solver.objective();
            for (MPVariable underVar : underVars.values()) {
                objective.setCoefficient(underVar, params.getUnderPenalty());
            }
            for (MPVariable xVar : xVars) {
                objective.setCoefficient(xVar, 1);
            }
            objective.setMinimization();

            solver.setTimeLimit(params.getTimeoutMs());
            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Legacy-order Stage2 returned {}", status);
                return null;
            }

            return extractSolution(patterns, xVars);
        } catch (Exception e) {
            log.error("Legacy-order Stage2 failed", e);
            return null;
        }
    }

    private Map<PatternCandidate, Integer> solveMIPStage3(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxTotalRolls) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) {
                return null;
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();

            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + params.getTotalOverCap(), "x_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            for (int width : demands.keySet()) {
                double overUpperBound = allowOverSet.contains(width) ? params.getTotalOverCap() : 0;
                overVars.put(width, solver.makeIntVar(0, overUpperBound, "over_" + width));
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

            MPConstraint overCap = solver.makeConstraint(0, maxTotalOver, "overCap");
            for (MPVariable overVar : overVars.values()) {
                overCap.setCoefficient(overVar, 1);
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

            solver.setTimeLimit(params.getTimeoutMs());
            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Legacy-order Stage3 returned {}", status);
                return null;
            }

            return extractSolution(patterns, xVars);
        } catch (Exception e) {
            log.error("Legacy-order Stage3 failed", e);
            return null;
        }
    }

    private Map<PatternCandidate, Integer> solveMIPStage4(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxTotalRolls,
            int maxTotalWaste) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) {
                return null;
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int bigM = totalDemand + params.getTotalOverCap();

            List<MPVariable> xVars = new ArrayList<>();
            List<MPVariable> yVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + params.getTotalOverCap(), "x_" + i));
                yVars.add(solver.makeBoolVar("y_" + i));
            }

            Map<Integer, MPVariable> overVars = new LinkedHashMap<>();
            for (int width : demands.keySet()) {
                double overUpperBound = allowOverSet.contains(width) ? params.getTotalOverCap() : 0;
                overVars.put(width, solver.makeIntVar(0, overUpperBound, "over_" + width));
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

            MPConstraint overCap = solver.makeConstraint(0, maxTotalOver, "overCap");
            for (MPVariable overVar : overVars.values()) {
                overCap.setCoefficient(overVar, 1);
            }

            MPConstraint rollsCap = solver.makeConstraint(0, maxTotalRolls, "rollsCap");
            for (MPVariable xVar : xVars) {
                rollsCap.setCoefficient(xVar, 1);
            }

            int wasteSlack = Math.max(200, (int) (maxTotalWaste * 0.02));
            MPConstraint wasteCap = solver.makeConstraint(0, maxTotalWaste + wasteSlack, "wasteCap");
            for (int i = 0; i < patterns.size(); i++) {
                wasteCap.setCoefficient(xVars.get(i), patterns.get(i).getRealWaste(params.getTotalWidth()));
            }

            for (int i = 0; i < patterns.size(); i++) {
                MPConstraint link = solver.makeConstraint(-MPSolver.infinity(), 0, "link_" + i);
                link.setCoefficient(xVars.get(i), 1);
                link.setCoefficient(yVars.get(i), -bigM);
            }

            int maxWidthCount = patterns.stream()
                    .mapToInt(PatternCandidate::getWidthCount)
                    .max()
                    .orElse(1);

            MPObjective objective = solver.objective();
            for (int i = 0; i < patterns.size(); i++) {
                objective.setCoefficient(yVars.get(i), LEGACY_SEQ_GROUP_ALPHA);
                double groupCost = (double) patterns.get(i).getWidthCount() / maxWidthCount;
                objective.setCoefficient(xVars.get(i), LEGACY_SEQ_GROUP_BETA * groupCost);
            }
            objective.setMinimization();

            long stage4TimeLimit = Math.min(30_000L, params.getTimeoutMs());
            solver.setHint(new MPVariable[] {}, new double[] {});
            solver.setTimeLimit(stage4TimeLimit);

            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Legacy-order Stage4 returned {}", status);
                return null;
            }

            return extractSolution(patterns, xVars);
        } catch (Exception e) {
            log.error("Legacy-order Stage4 failed", e);
            return null;
        }
    }

    private boolean isFeasible(Map<PatternCandidate, Integer> solution, Map<Integer, Integer> demands) {
        if (solution == null || solution.isEmpty()) {
            return false;
        }
        Map<Integer, Integer> production = calculateProduction(solution);
        for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
            if (production.getOrDefault(demandEntry.getKey(), 0) < demandEntry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private Set<Integer> mergeForcedAllowOverWidths(Set<Integer> allowOverSet) {
        Set<Integer> merged = new HashSet<>(allowOverSet);
        merged.addAll(params.getForceAllowOverWidths());
        return merged;
    }

    private int repairPatterns(List<PatternCandidate> patterns,
            Set<String> seen,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        int repaired = 0;
        List<Integer> widths = new ArrayList<>(demands.keySet());
        widths.sort((a, b) -> Integer.compare(demands.get(b), demands.get(a)));

        for (int width : demands.keySet()) {
            List<Integer> coefficients = new ArrayList<>();
            for (PatternCandidate pattern : patterns) {
                int count = pattern.getPattern().getOrDefault(width, 0);
                if (count > 0) {
                    coefficients.add(count);
                }
            }

            boolean needsRepair = coefficients.isEmpty();
            if (!needsRepair) {
                int divisor = coefficients.get(0);
                for (int coefficient : coefficients) {
                    divisor = gcd(divisor, coefficient);
                }
                int capForWidth = allowOverSet.contains(width) ? params.getTotalOverCap() : 0;
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

            boolean found = false;
            for (int fillWidth : widths) {
                if (fillWidth == width || found) {
                    continue;
                }
                for (int fillCount = 1; fillCount <= 5; fillCount++) {
                    int totalWidth = width + fillWidth * fillCount;
                    if (totalWidth < params.getMinRollWidth()) {
                        continue;
                    }
                    if (totalWidth > params.getMaxRollWidth()) {
                        break;
                    }

                    int rollWidth = ceilToStep(totalWidth, params.getStepSize());
                    rollWidth = Math.max(rollWidth, params.getMinRollWidth());
                    rollWidth = Math.min(rollWidth, params.getMaxRollWidth());
                    if (totalWidth > rollWidth) {
                        continue;
                    }

                    Map<Integer, Integer> candidatePattern = new LinkedHashMap<>();
                    candidatePattern.put(width, 1);
                    candidatePattern.put(fillWidth, fillCount);
                    PatternCandidate candidate = new PatternCandidate(candidatePattern, rollWidth);
                    if (seen.add(candidate.signature())) {
                        patterns.add(candidate);
                        repaired++;
                        found = true;
                        break;
                    }
                }
            }
        }

        return repaired;
    }

    private int calculateTotalWaste(Map<PatternCandidate, Integer> solution) {
        return solution.entrySet().stream()
                .mapToInt(entry -> entry.getKey().getRealWaste(params.getTotalWidth()) * entry.getValue())
                .sum();
    }

    private Map<Integer, Integer> calculateProduction(Map<PatternCandidate, Integer> solution) {
        Map<Integer, Integer> produced = new LinkedHashMap<>();
        for (Map.Entry<PatternCandidate, Integer> entry : solution.entrySet()) {
            for (Map.Entry<Integer, Integer> cut : entry.getKey().getPattern().entrySet()) {
                produced.merge(cut.getKey(), cut.getValue() * entry.getValue(), Integer::sum);
            }
        }
        return produced;
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

    private MPSolver createMIPSolver() {
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            solver = MPSolver.createSolver("CBC");
        }
        return solver;
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

    record Result(
            String name,
            Map<PatternCandidate, Integer> solution) {
    }
}
