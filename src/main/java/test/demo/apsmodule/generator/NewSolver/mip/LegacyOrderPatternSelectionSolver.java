package test.demo.apsmodule.generator.NewSolver.mip;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
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
    private static final long DEFAULT_QUALITY_PRIMARY_STAGE4_NODE_LIMIT = 3000L;
    private static final long DEFAULT_QUALITY_STAGE4_NODE_LIMIT = 10L;
    private static final long DEFAULT_STAGE4_WALL_CAP_MS = 20_000L;
    private static final long DEFAULT_STAGE4_SAFETY_TIME_LIMIT_MS = 120_000L;

    /**
     * 固定 SCIP 随机化种子，保证相同输入 → 相同解。Legacy 既产出 primary 花型集，
     * 又承担 diverse 候选的 Stage4 refine（最终胜出的 {@code -s4} 候选），两者都需复现。
     */
    private static final String SCIP_DETERMINISTIC_PARAMS =
            "randomization/randomseedshift = 0\n"
          + "randomization/permutationseed = 0\n"
          + "randomization/lpseed = 0\n";

    /**
     * SCIP randomization params for the configured A-layer seed. The seed steers
     * which 花型集 the selection MIP lands on among tie-degenerate optima; the
     * multi-start in CuttingSolver sweeps it to find a lower sequence-group set.
     * Seed 0 reproduces the legacy {@link #SCIP_DETERMINISTIC_PARAMS}.
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

    private static final double LEGACY_SEQ_GROUP_ALPHA = 1.0;
    private static final double LEGACY_SEQ_GROUP_BETA = 0.0;
    private static final int    MIN_USAGE_THRESHOLD = 4;
    private static final double SMALL_USAGE_PENALTY = 0.02;

    private final SolverParameters params;
    private final PatternAlignmentScorer alignmentScorer;

    LegacyOrderPatternSelectionSolver(SolverParameters params) {
        this(params, PatternAlignmentContext.empty());
    }

    LegacyOrderPatternSelectionSolver(SolverParameters params, PatternAlignmentContext alignmentContext) {
        this.params = params;
        this.alignmentScorer = new PatternAlignmentScorer(alignmentContext);
    }

    /**
     * Stage4 奇偶 tie-break 权重。默认 0（关闭）。实验开关：-Dcutting.aLayerParityPenalty=0.02。
     * 权重须远小于 1（花型数单位代价），保证只在花型数平局的最优解之间挑奇偶更好的。
     */
    private static double parityPenalty() {
        return SolverRuntimeProperties.getDouble("cutting.aLayerParityPenalty", 0.0);
    }

    private static long longProperty(String key, long defaultValue) {
        return SolverRuntimeProperties.getLong(key, defaultValue);
    }

    static long stage4NodeLimit() {
        long defaultValue = SolverRuntimeProperties.getBoolean("cutting.quality", false)
                ? DEFAULT_QUALITY_STAGE4_NODE_LIMIT
                : 0L;
        return longProperty("cutting.aLayerStage4NodeLimit", defaultValue);
    }

    static long stage4WallLimitMs(long remainingTimeMs) {
        return stage4WallLimitMs(remainingTimeMs, stage4NodeLimit());
    }

    private static long stage4WallLimitMs(long remainingTimeMs, long effectiveNodeLimit) {
        long configuredCap = effectiveNodeLimit > 0
                ? longProperty("cutting.aLayerStage4SafetyTimeLimitMs",
                        DEFAULT_STAGE4_SAFETY_TIME_LIMIT_MS)
                : longProperty("cutting.aLayerStage4CapMs", DEFAULT_STAGE4_WALL_CAP_MS);
        return Math.min(remainingTimeMs, configuredCap);
    }

    static long effectiveStage4NodeLimit(double parityPenalty, double alignmentLambda) {
        if (parityPenalty > 0.0 || alignmentLambda > 0.0) {
            return stage4NodeLimit();
        }
        long defaultValue = SolverRuntimeProperties.getBoolean("cutting.quality", false)
                ? DEFAULT_QUALITY_PRIMARY_STAGE4_NODE_LIMIT
                : 0L;
        return longProperty("cutting.aLayerPrimaryStage4NodeLimit", defaultValue);
    }

    List<Result> solveCandidates(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet) {
        return solveCandidates(patterns, demands, allowOverSet,
                System.currentTimeMillis() + params.getTimeoutMs());
    }

    List<Result> solveCandidates(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            long deadlineMs) {
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
                    workingAllowOverSet,
                    deadlineMs);

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
            Set<Integer> allowOverSet,
            long deadlineMs) {
        // Distribute remaining budget across stages. Stage1/2 are critical;
        // Stage3/4 are refinements and get whatever is left.
        long now = System.currentTimeMillis();
        long remaining = Math.max(5000, deadlineMs - now);
        long stage1Time = Math.max(5000, remaining / 6);
        int[] stage1Result = solveMIPStage1(patterns, demands, allowOverSet, stage1Time);
        if (stage1Result == null) {
            return Collections.emptyMap();
        }

        int optimalOver = Arrays.stream(stage1Result).sum();
        log.info("Legacy Stage1 completed: optimalOver={}", optimalOver);
        remaining = Math.max(5000, deadlineMs - System.currentTimeMillis());
        long stage2Time = Math.max(5000, remaining * 55 / 100);
        Map<PatternCandidate, Integer> stage2Solution = solveMIPStage2(
                patterns, demands, allowOverSet, optimalOver, stage2Time);
        if (stage2Solution == null || stage2Solution.isEmpty()) {
            return Collections.emptyMap();
        }

        int totalRolls = stage2Solution.values().stream().mapToInt(Integer::intValue).sum();
        int stage2Patterns = stage2Solution.size();
        int stage2Waste = calculateTotalWaste(stage2Solution);
        log.info("Legacy Stage2 completed: rolls={}, patterns={}, waste={}mm", totalRolls, stage2Patterns, stage2Waste);

        remaining = Math.max(3000, deadlineMs - System.currentTimeMillis());
        long stage3Time = Math.max(3000, remaining * 60 / 100);
        Map<PatternCandidate, Integer> stage3Solution = solveMIPStage3(
                patterns, demands, allowOverSet, optimalOver, totalRolls, stage3Time);
        if (stage3Solution == null || stage3Solution.isEmpty()) {
            log.info("Legacy Stage3 failed, using Stage2 solution");
            stage3Solution = stage2Solution;
        }

        int totalWaste = calculateTotalWaste(stage3Solution);
        int stage3Rolls = stage3Solution.values().stream().mapToInt(Integer::intValue).sum();
        int stage3Patterns = stage3Solution.size();
        log.info("Legacy Stage3 completed: rolls={}, patterns={}, waste={}mm", stage3Rolls, stage3Patterns, totalWaste);

        int wasteSlack = Math.max(200, (int) (totalWaste * 0.05));
        log.info("Legacy Stage4 constraints: maxRolls={}, maxWaste={}, wasteSlack={}, wasteCap={}",
                stage3Rolls, totalWaste, wasteSlack, totalWaste + wasteSlack);

        remaining = Math.max(2000, deadlineMs - System.currentTimeMillis());
        // The inner Stage4 policy owns the wall cap. Passing the remaining request budget here
        // prevents an outer cap from firing before the deterministic node budget on slower hosts.
        long stage4Time = remaining;
        // 对齐 λ 或奇偶 tie-break 生效时给 Stage4 全池：奇偶翻转需要备选花型做需求等式的
        // 补偿交换，stage3 精选池往往没有腾挪空间。
        boolean stage4NeedsFullPool = params.getALayerAlignmentLambda() > 0.0 || parityPenalty() > 0.0;
        List<PatternCandidate> stage4PatternPool = stage4NeedsFullPool
                ? patterns
                : new ArrayList<>(stage3Solution.keySet());
        if (stage4NeedsFullPool) {
            log.info("Legacy Stage4 uses full pattern pool: {} patterns", stage4PatternPool.size());
        }
        Map<PatternCandidate, Integer> stage4Solution = solveMIPStage4(
                stage4PatternPool, demands, allowOverSet, optimalOver, stage3Rolls, totalWaste, stage4Time);
        if (stage4Solution == null || stage4Solution.isEmpty()) {
            log.info("Legacy Stage4 failed, using Stage3 solution");
            return stage3Solution;
        }
        int stage4Rolls = stage4Solution.values().stream().mapToInt(Integer::intValue).sum();
        int stage4Patterns = stage4Solution.size();
        int stage4Waste = calculateTotalWaste(stage4Solution);
        log.info("Legacy Stage4 completed: rolls={}, patterns={}, waste={}mm", stage4Rolls, stage4Patterns, stage4Waste);
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
            Set<Integer> allowOverSet,
            long timeLimitMs) {
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

            solver.setTimeLimit(Math.max(1000, timeLimitMs));
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
            int maxTotalOver,
            long timeLimitMs) {
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

            solver.setTimeLimit(Math.max(1000, timeLimitMs));
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
            int maxTotalRolls,
            long timeLimitMs) {
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

            solver.setTimeLimit(Math.max(1000, timeLimitMs));
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

    Map<PatternCandidate, Integer> solveMIPStage4(List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int maxTotalOver,
            int maxTotalRolls,
            int maxTotalWaste,
            long timeLimitMs) {
        try {
            MPSolver solver = createMIPSolver();
            if (solver == null) {
                return null;
            }

            int totalDemand = demands.values().stream().mapToInt(Integer::intValue).sum();
            int bigM = totalDemand + params.getTotalOverCap();

            List<MPVariable> xVars = new ArrayList<>();
            List<MPVariable> yVars = new ArrayList<>();
            List<MPVariable> sVars = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                xVars.add(solver.makeIntVar(0, totalDemand + params.getTotalOverCap(), "x_" + i));
                yVars.add(solver.makeBoolVar("y_" + i));
                sVars.add(solver.makeNumVar(0, MIN_USAGE_THRESHOLD, "s_" + i));
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

            int wasteSlack = Math.max(200, (int) (maxTotalWaste * 0.05));
            MPConstraint wasteCap = solver.makeConstraint(0, maxTotalWaste + wasteSlack, "wasteCap");
            for (int i = 0; i < patterns.size(); i++) {
                wasteCap.setCoefficient(xVars.get(i), patterns.get(i).getRealWaste(params.getTotalWidth()));
            }

            for (int i = 0; i < patterns.size(); i++) {
                MPConstraint link = solver.makeConstraint(-MPSolver.infinity(), 0, "link_" + i);
                link.setCoefficient(xVars.get(i), 1);
                link.setCoefficient(yVars.get(i), -bigM);
            }

            for (int i = 0; i < patterns.size(); i++) {
                MPConstraint minUse = solver.makeConstraint(0, MPSolver.infinity(), "minuse_" + i);
                minUse.setCoefficient(sVars.get(i), 1.0);
                minUse.setCoefficient(xVars.get(i), 1.0);
                minUse.setCoefficient(yVars.get(i), -MIN_USAGE_THRESHOLD);
            }

            // 奇偶 tie-break：odd 序号组的理论下界 = usage 为奇数的花型族个数（每族块大小
            // 之和 = 族车数，奇数至少留一个奇块，B 层不可突破——人工方案 odd=2 恰好打到
            // 自己花型集的下界）。x_i = 2·h_i + o_i 提取 usage 奇偶性，o_i 进目标做小权重
            // 惩罚，在车数/废边/花型数平局的最优解中偏好偶 usage 的花型集，把下界往
            // 理论极限（总车数奇偶性决定，本数据集=每分组1）压。
            double parityPenalty = parityPenalty();
            List<MPVariable> oVars = new ArrayList<>();
            if (parityPenalty > 0.0) {
                for (int i = 0; i < patterns.size(); i++) {
                    MPVariable hVar = solver.makeIntVar(0, totalDemand + params.getTotalOverCap(), "h_" + i);
                    MPVariable oVar = solver.makeBoolVar("o_" + i);
                    MPConstraint parity = solver.makeConstraint(0, 0, "parity_" + i);
                    parity.setCoefficient(xVars.get(i), 1);
                    parity.setCoefficient(hVar, -2);
                    parity.setCoefficient(oVar, -1);
                    oVars.add(oVar);
                }
            }

            int maxWidthCount = patterns.stream()
                    .mapToInt(PatternCandidate::getWidthCount)
                    .max()
                    .orElse(1);

            MPObjective objective = solver.objective();
            double alignmentLambda = params.getALayerAlignmentLambda();
            double alignmentCostSum = 0.0;
            double alignmentCostMax = 0.0;
            for (int i = 0; i < patterns.size(); i++) {
                double alignmentCost = alignmentLambda > 0.0
                        ? alignmentScorer.cost(patterns.get(i))
                        : 0.0;
                alignmentCostSum += alignmentCost;
                alignmentCostMax = Math.max(alignmentCostMax, alignmentCost);
                objective.setCoefficient(yVars.get(i),
                        LEGACY_SEQ_GROUP_ALPHA + alignmentLambda * alignmentCost);
                double groupCost = (double) patterns.get(i).getWidthCount() / maxWidthCount;
                objective.setCoefficient(xVars.get(i), LEGACY_SEQ_GROUP_BETA * groupCost);
                objective.setCoefficient(sVars.get(i), SMALL_USAGE_PENALTY);
            }
            for (MPVariable oVar : oVars) {
                objective.setCoefficient(oVar, parityPenalty);
            }
            objective.setMinimization();
            if (alignmentLambda > 0.0 && !patterns.isEmpty()) {
                log.info("Legacy Stage4 alignment: lambda={}, avgCost={}, maxCost={}",
                        alignmentLambda,
                        alignmentCostSum / patterns.size(),
                        alignmentCostMax);
            }

            // 20s 是快路径顶帽；全池+奇偶变量的模型 20s 常 NOT_SOLVED/FEASIBLE 截断
            // （截断时奇偶项来不及优化）。实验可用 -Dcutting.aLayerStage4CapMs 放宽。
            long nodeLimit = effectiveStage4NodeLimit(parityPenalty, alignmentLambda);
            if (nodeLimit > 0 && solver.solverVersion().toUpperCase().contains("SCIP")) {
                solver.setSolverSpecificParametersAsString(
                        scipParams() + "limits/nodes = " + nodeLimit + "\n");
            }
            long stage4TimeLimit = stage4WallLimitMs(timeLimitMs, nodeLimit);
            solver.setHint(new MPVariable[] {}, new double[] {});
            solver.setTimeLimit(stage4TimeLimit);

            long stage4StartedAt = System.currentTimeMillis();
            MPSolver.ResultStatus status = solver.solve();
            long stage4ElapsedMs = System.currentTimeMillis() - stage4StartedAt;
            long stage4Nodes = -1L;
            try {
                stage4Nodes = solver.nodes();
            } catch (Throwable ignored) {
            }
            log.info("Legacy Stage4 solve: status={}, elapsedMs={}, nodes={}/{}, wallLimitMs={}",
                    status, stage4ElapsedMs, stage4Nodes, nodeLimit, stage4TimeLimit);
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Legacy-order Stage4 returned {}", status);
                return null;
            }

            Map<PatternCandidate, Integer> solution = extractSolution(patterns, xVars);
            if (parityPenalty > 0.0) {
                long oddUsageFamilies = solution.values().stream().filter(u -> u % 2 != 0).count();
                log.info("Legacy Stage4 parity: penalty={}, oddUsageFamilies={} (odd 序号组下界), status={}",
                        parityPenalty, oddUsageFamilies, status);
            }
            return solution;
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
        if (solver != null) {
            // 仅 SCIP 接受该参数串；CBC 回退路径不应用。
            solver.setSolverSpecificParametersAsString(scipParams());
            // 单线程：多线程 MIP 是非确定性的经典来源（线程竞争与种子无关）。
            try { solver.setNumThreads(1); } catch (Exception ignored) {}
            return solver;
        }
        return MPSolver.createSolver("CBC");
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
