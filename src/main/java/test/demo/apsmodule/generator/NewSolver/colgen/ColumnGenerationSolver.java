package test.demo.apsmodule.generator.NewSolver.colgen;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Column-generation LP stage aligned with the legacy variable-width solver.
 */
public class ColumnGenerationSolver {

    private static final Logger log = LoggerFactory.getLogger(ColumnGenerationSolver.class);

    private static final double EPSILON = 1e-6;
    private static final double OVER_PENALTY_BASE = 0.1;
    private static final int NO_IMPROVEMENT_LIMIT = 10;
    private static final int PRICING_CANDIDATE_LIMIT = 12;
    private static final int SOFT_LIMIT_BUFFER = 200;

    private final SolverParameters params;

    public ColumnGenerationSolver(SolverParameters params) {
        this.params = params;
    }

    public List<PatternCandidate> solve(List<PatternCandidate> initialPatterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        log.info("\n--- Stage A: 列生成LP ---");
        long startTime = System.currentTimeMillis();

        List<PatternCandidate> patterns = new ArrayList<>(initialPatterns);
        Set<String> seen = new HashSet<>();
        for (PatternCandidate pc : patterns) {
            seen.add(pc.signature());
        }
        log.info("初始模式数 {}", patterns.size());

        int iterationCount = 0;
        int noImprovementCount = 0;
        int maxIterations = params.getMaxIterations();
        int configuredMaxPatterns = params.getMaxPatterns();
        int initialPatternCount = patterns.size();
        int softLimit = determineSoftPatternLimit(initialPatternCount, configuredMaxPatterns);

        if (initialPatternCount > configuredMaxPatterns) {
            log.info("Initial pool {} exceeds configured maxPatterns={}, using soft limit {}",
                    initialPatternCount, configuredMaxPatterns, softLimit);
        }

        while (iterationCount < maxIterations && patterns.size() < softLimit) {
            iterationCount++;

            MasterLPResult masterResult = solveMasterProblemLP(patterns, demands, allowOverSet);
            if (!masterResult.feasible) {
                log.info("  主问题LP不可行");
                break;
            }

            List<PatternCandidate> newPatterns = solvePricingSubproblem(
                    masterResult.dualPrices, demands.keySet(), demands, allowOverSet);

            int added = 0;
            for (PatternCandidate np : newPatterns) {
                if (seen.add(np.signature())) {
                    patterns.add(np);
                    added++;
                    if (iterationCount <= 5 || iterationCount % 20 == 0) {
                        double rc = calculateReducedCost(np, masterResult.dualPrices);
                        log.info("  Iteration {}: add {} rc={}",
                                iterationCount, np, String.format("%.4f", rc));
                    }
                }
            }

            if (added > 0) {
                noImprovementCount = 0;
                if (patterns.size() >= softLimit) {
                    log.info("  Reached working pattern limit {}, stop", softLimit);
                    break;
                }
            } else {
                noImprovementCount++;
                if (noImprovementCount >= NO_IMPROVEMENT_LIMIT) {
                    log.info("  Iteration {}: no new columns for {} rounds, stop",
                            iterationCount, NO_IMPROVEMENT_LIMIT);
                    break;
                }
            }
        }

        log.info("列生成完成 {} 次迭代 {} 个模式", iterationCount, patterns.size());
        log.info("列生成耗时: {}ms", System.currentTimeMillis() - startTime);
        log.info("Column generation summary: iterations={}, addedColumns={}, softLimit={}",
                iterationCount, patterns.size() - initialPatternCount, softLimit);

        debugCoverageAndGcd(patterns, demands, allowOverSet);
        int repaired = repairPatterns(patterns, seen, demands, allowOverSet);
        if (repaired > 0) {
            log.info("修复了 {} 个模式，模式池总数: {}", repaired, patterns.size());
        }

        return patterns;
    }

    static int determineSoftPatternLimit(int initialPatternCount, int configuredMaxPatterns) {
        int buffer = Math.max(100, Math.min(SOFT_LIMIT_BUFFER, configuredMaxPatterns / 4));
        return Math.max(configuredMaxPatterns, initialPatternCount + buffer);
    }

    private MasterLPResult solveMasterProblemLP(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        try {
            MPSolver solver = MPSolver.createSolver("GLOP");
            if (solver == null) {
                return new MasterLPResult(0, new HashMap<>(), false);
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int maxDemand = demands.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            int totalOverCap = params.getTotalOverCap();

            List<MPVariable> xVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeNumVar(0, totalDemand + totalOverCap, "x_" + i));
            }

            Map<Integer, MPVariable> underVars = new HashMap<>();
            for (int width : demands.keySet()) {
                underVars.put(width, solver.makeNumVar(0, totalDemand, "under_" + width));
            }

            Map<Integer, MPVariable> overVars = new HashMap<>();
            for (int width : demands.keySet()) {
                double overUpperBound = allowOverSet.contains(width) ? totalOverCap : 0;
                overVars.put(width, solver.makeNumVar(0, overUpperBound, "over_" + width));
            }

            Map<Integer, MPConstraint> demandConstraints = new HashMap<>();
            for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
                int width = demandEntry.getKey();
                int demand = demandEntry.getValue();
                MPConstraint constraint = solver.makeConstraint(demand, demand, "demand_" + width);
                constraint.setCoefficient(underVars.get(width), 1);
                constraint.setCoefficient(overVars.get(width), -1);
                demandConstraints.put(width, constraint);
            }

            for (int i = 0; i < patterns.size(); i++) {
                PatternCandidate candidate = patterns.get(i);
                for (Map.Entry<Integer, Integer> cut : candidate.getPattern().entrySet()) {
                    MPConstraint constraint = demandConstraints.get(cut.getKey());
                    if (constraint != null) {
                        constraint.setCoefficient(xVars.get(i), cut.getValue());
                    }
                }
            }

            MPConstraint overCapConstraint = solver.makeConstraint(0, totalOverCap, "overCap");
            for (MPVariable overVar : overVars.values()) {
                overCapConstraint.setCoefficient(overVar, 1);
            }

            MPObjective objective = solver.objective();
            for (int i = 0; i < patterns.size(); i++) {
                objective.setCoefficient(xVars.get(i), patterns.get(i).getCost(params.getTotalWidth()));
            }
            for (MPVariable underVar : underVars.values()) {
                objective.setCoefficient(underVar, params.getUnderPenalty());
            }
            for (Map.Entry<Integer, Integer> demandEntry : demands.entrySet()) {
                int width = demandEntry.getKey();
                int demand = demandEntry.getValue();
                double ratio = (double) maxDemand / Math.max(demand, 1);
                double penalty = OVER_PENALTY_BASE * ratio * ratio;
                if (!allowOverSet.contains(width)) {
                    penalty *= 100;
                }
                objective.setCoefficient(overVars.get(width), penalty);
            }
            objective.setMinimization();

            MPSolver.ResultStatus status = solver.solve();
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                return new MasterLPResult(0, new HashMap<>(), false);
            }

            Map<Integer, Double> dualPrices = new HashMap<>();
            for (Map.Entry<Integer, MPConstraint> entry : demandConstraints.entrySet()) {
                dualPrices.put(entry.getKey(), entry.getValue().dualValue());
            }

            return new MasterLPResult(objective.value(), dualPrices, true);
        } catch (Exception e) {
            log.error("Column-generation LP solve failed", e);
            return new MasterLPResult(0, new HashMap<>(), false);
        }
    }

    private List<PatternCandidate> solvePricingSubproblem(Map<Integer, Double> dualPrices,
            Set<Integer> allWidths,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        List<PatternCandidate> newPatterns = new ArrayList<>();
        List<Integer> widths = new ArrayList<>(allWidths);
        Collections.sort(widths);

        int minRollWidth = params.getMinRollWidth();
        int maxRollWidth = params.getMaxRollWidth();
        int stepSize = params.getStepSize();

        for (int rollWidth = minRollWidth; rollWidth <= maxRollWidth; rollWidth += stepSize) {
            PatternCandidate best = solveDPForRollWidth(rollWidth, widths, dualPrices, demands, allowOverSet);
            if (best != null) {
                double reducedCost = calculateReducedCost(best, dualPrices);
                if (reducedCost < -EPSILON) {
                    newPatterns.add(best);
                }
            }
        }

        newPatterns.sort((a, b) -> Double.compare(
                calculateReducedCost(a, dualPrices),
                calculateReducedCost(b, dualPrices)));

        if (newPatterns.size() > PRICING_CANDIDATE_LIMIT) {
            return new ArrayList<>(newPatterns.subList(0, PRICING_CANDIDATE_LIMIT));
        }

        return newPatterns;
    }

    private PatternCandidate solveDPForRollWidth(int rollWidth,
            List<Integer> widths,
            Map<Integer, Double> dualPrices,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        int capacity = rollWidth;
        int widthCount = widths.size();
        int minRollWidth = params.getMinRollWidth();
        int maxRollWidth = params.getMaxRollWidth();
        int maxDistinctWidths = Math.max(1, Math.min(params.getMaxDistinctWidths(), widthCount));

        double[][] previous = newDpLayer(capacity, maxDistinctWidths);
        previous[0][0] = 0.0;
        int[][][] chosenCounts = new int[widthCount + 1][capacity + 1][maxDistinctWidths + 1];

        double[] itemValues = new double[widthCount];
        int[] maxCopiesByWidth = new int[widthCount];
        for (int i = 0; i < widthCount; i++) {
            int width = widths.get(i);
            itemValues[i] = Math.max(0, dualPrices.getOrDefault(width, 0.0));
            int allowedProduction = demands.getOrDefault(width, 0)
                    + (allowOverSet.contains(width) ? params.getTotalOverCap() : 0);
            maxCopiesByWidth[i] = Math.min(capacity / width, Math.max(0, allowedProduction));
        }

        for (int widthIndex = 1; widthIndex <= widthCount; widthIndex++) {
            int width = widths.get(widthIndex - 1);
            int maxCopies = maxCopiesByWidth[widthIndex - 1];
            double itemValue = itemValues[widthIndex - 1];
            double[][] next = newDpLayer(capacity, maxDistinctWidths);

            for (int usedWidth = 0; usedWidth <= capacity; usedWidth++) {
                for (int distinct = 0; distinct <= maxDistinctWidths; distinct++) {
                    double baseValue = previous[usedWidth][distinct];
                    if (baseValue == Double.NEGATIVE_INFINITY) {
                        continue;
                    }

                    if (baseValue > next[usedWidth][distinct] + EPSILON) {
                        next[usedWidth][distinct] = baseValue;
                    }

                    if (distinct >= maxDistinctWidths || maxCopies <= 0) {
                        continue;
                    }

                    int copyLimit = Math.min(maxCopies, (capacity - usedWidth) / width);
                    for (int count = 1; count <= copyLimit; count++) {
                        int nextWidth = usedWidth + width * count;
                        int nextDistinct = distinct + 1;
                        double newValue = baseValue + itemValue * count;
                        if (newValue > next[nextWidth][nextDistinct] + EPSILON) {
                            next[nextWidth][nextDistinct] = newValue;
                            chosenCounts[widthIndex][nextWidth][nextDistinct] = count;
                        }
                    }
                }
            }
            previous = next;
        }

        int bestTotalWidth = -1;
        int bestDistinct = -1;
        double bestValue = -1;
        for (int t = minRollWidth; t <= rollWidth; t++) {
            for (int distinct = 1; distinct <= maxDistinctWidths; distinct++) {
                double value = previous[t][distinct];
                if (value > bestValue + EPSILON
                        || (Math.abs(value - bestValue) <= EPSILON && t > bestTotalWidth)) {
                    bestValue = value;
                    bestTotalWidth = t;
                    bestDistinct = distinct;
                }
            }
        }

        if (bestTotalWidth < 0 || bestDistinct < 0 || bestValue <= EPSILON) {
            return null;
        }

        Map<Integer, Integer> pattern = new HashMap<>();
        int remainingWidth = bestTotalWidth;
        int remainingDistinct = bestDistinct;
        for (int widthIndex = widthCount; widthIndex >= 1; widthIndex--) {
            int count = chosenCounts[widthIndex][remainingWidth][remainingDistinct];
            if (count <= 0) {
                continue;
            }
            int width = widths.get(widthIndex - 1);
            pattern.put(width, count);
            remainingWidth -= width * count;
            remainingDistinct--;
        }

        if (pattern.isEmpty()) {
            return null;
        }

        int patternWidth = pattern.entrySet().stream()
                .mapToInt(entry -> entry.getKey() * entry.getValue())
                .sum();

        int bestRollWidth = ceilToStep(patternWidth, params.getStepSize());
        bestRollWidth = Math.max(bestRollWidth, minRollWidth);
        bestRollWidth = Math.min(bestRollWidth, maxRollWidth);

        if (patternWidth > bestRollWidth || patternWidth < minRollWidth) {
            return null;
        }

        return new PatternCandidate(pattern, bestRollWidth);
    }

    private double[][] newDpLayer(int capacity, int maxDistinctWidths) {
        double[][] layer = new double[capacity + 1][maxDistinctWidths + 1];
        for (double[] row : layer) {
            Arrays.fill(row, Double.NEGATIVE_INFINITY);
        }
        return layer;
    }

    private double calculateReducedCost(PatternCandidate candidate, Map<Integer, Double> dualPrices) {
        double value = 0;
        for (Map.Entry<Integer, Integer> entry : candidate.getPattern().entrySet()) {
            double dual = Math.max(0, dualPrices.getOrDefault(entry.getKey(), 0.0));
            value += dual * entry.getValue();
        }
        return candidate.getCost(params.getTotalWidth()) - value;
    }

    private int ceilToStep(int value, int step) {
        return ((value + step - 1) / step) * step;
    }

    private void debugCoverageAndGcd(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        log.info("\n--- GCD 诊断 ---");
        int totalOverCap = params.getTotalOverCap();

        for (int width : demands.keySet()) {
            List<Integer> coefficients = new ArrayList<>();
            for (PatternCandidate candidate : patterns) {
                int coefficient = candidate.getPattern().getOrDefault(width, 0);
                if (coefficient > 0) {
                    coefficients.add(coefficient);
                }
            }

            if (coefficients.isEmpty()) {
                log.info("  Width {}mm is not covered by any pattern", width);
                continue;
            }

            int divisor = coefficients.get(0);
            for (int coefficient : coefficients) {
                divisor = gcd(divisor, coefficient);
            }

            int demand = demands.get(width);
            int capForWidth = allowOverSet.contains(width) ? totalOverCap : 0;

            boolean reachable = false;
            for (int extra = 0; extra <= capForWidth; extra++) {
                if ((demand + extra) % divisor == 0) {
                    reachable = true;
                    break;
                }
            }

            if (!reachable) {
                log.info("  Width {}mm has GCD block: gcd={}, demand={}, cap={}",
                        width, divisor, demand, capForWidth);
            }
        }
    }

    private int repairPatterns(List<PatternCandidate> patterns,
            Set<String> seen,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        int repaired = 0;
        List<Integer> widths = new ArrayList<>(demands.keySet());
        widths.sort((a, b) -> Integer.compare(demands.get(b), demands.get(a)));

        int minRollWidth = params.getMinRollWidth();
        int maxRollWidth = params.getMaxRollWidth();
        int stepSize = params.getStepSize();
        int totalOverCap = params.getTotalOverCap();

        for (int width : demands.keySet()) {
            List<Integer> coefficients = new ArrayList<>();
            for (PatternCandidate candidate : patterns) {
                int coefficient = candidate.getPattern().getOrDefault(width, 0);
                if (coefficient > 0) {
                    coefficients.add(coefficient);
                }
            }

            boolean needsRepair = false;
            if (coefficients.isEmpty()) {
                log.info("  Repair uncovered width {}mm", width);
                needsRepair = true;
            } else {
                int divisor = coefficients.get(0);
                for (int coefficient : coefficients) {
                    divisor = gcd(divisor, coefficient);
                }

                int capForWidth = allowOverSet.contains(width) ? totalOverCap : 0;
                boolean reachable = false;
                for (int extra = 0; extra <= capForWidth; extra++) {
                    if ((demands.get(width) + extra) % divisor == 0) {
                        reachable = true;
                        break;
                    }
                }

                if (!reachable) {
                    log.info("  Repair GCD-blocked width {}mm (gcd={}, demand={})",
                            width, divisor, demands.get(width));
                    needsRepair = true;
                }
            }

            if (!needsRepair) {
                continue;
            }

            boolean found = false;
            for (int fillWidth : widths) {
                if (found || fillWidth == width) {
                    continue;
                }

                for (int fillCount = 1; fillCount <= 5; fillCount++) {
                    int totalWidth = width + fillWidth * fillCount;
                    if (totalWidth < minRollWidth) {
                        continue;
                    }
                    if (totalWidth > maxRollWidth) {
                        break;
                    }

                    int rollWidth = ceilToStep(totalWidth, stepSize);
                    rollWidth = Math.max(rollWidth, minRollWidth);
                    rollWidth = Math.min(rollWidth, maxRollWidth);

                    if (totalWidth > rollWidth) {
                        continue;
                    }

                    Map<Integer, Integer> pattern = new HashMap<>();
                    pattern.put(width, 1);
                    pattern.put(fillWidth, fillCount);

                    PatternCandidate candidate = new PatternCandidate(pattern, rollWidth);
                    if (seen.add(candidate.signature())) {
                        patterns.add(candidate);
                        repaired++;
                        found = true;
                        log.info("    Generated repair pattern: {} @{}mm", pattern, rollWidth);
                        break;
                    }
                }
            }
        }

        return repaired;
    }

    private int gcd(int a, int b) {
        while (b != 0) {
            int next = b;
            b = a % b;
            a = next;
        }
        return a;
    }

    private static class MasterLPResult {
        final double objectiveValue;
        final Map<Integer, Double> dualPrices;
        final boolean feasible;

        MasterLPResult(double objectiveValue, Map<Integer, Double> dualPrices, boolean feasible) {
            this.objectiveValue = objectiveValue;
            this.dualPrices = dualPrices;
            this.feasible = feasible;
        }
    }
}
