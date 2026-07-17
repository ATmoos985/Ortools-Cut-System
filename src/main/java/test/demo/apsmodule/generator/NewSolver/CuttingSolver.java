package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import test.demo.apsmodule.generator.NewSolver.colgen.ColumnGenerationSolver;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.MultiStageMIPSolver;
import test.demo.apsmodule.generator.NewSolver.mip.PatternAlignmentContext;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.model.SolverResult;
import test.demo.apsmodule.generator.NewSolver.output.InstructionConverter;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.generator.NewSolver.output.SetPartitionRefiner;
import test.demo.apsmodule.generator.NewSolver.output.SolverRunColumnArchive;
import test.demo.apsmodule.generator.NewSolver.pattern.PatternGenerator;
import test.demo.apsmodule.generator.NewSolver.report.SolveReportWriter;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.solver.CuttingSolverAlgorithm;
import test.demo.apsmodule.solver.kernel.DeterministicPlanSelector;
import test.demo.apsmodule.solver.kernel.PlanQuality;
import test.demo.apsmodule.solver.kernel.execution.BoundedSolverTaskExecutor;
import test.demo.apsmodule.solver.kernel.execution.DirectSolverTaskExecutor;
import test.demo.apsmodule.solver.kernel.execution.SolverTaskExecutor;

import java.util.*;
import java.util.stream.Collectors;

/**
 * NewSolver unified entry.
 */
@Component
public class CuttingSolver implements CuttingSolverAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(CuttingSolver.class);
    private static final DeterministicPlanSelector PLAN_SELECTOR = new DeterministicPlanSelector();
    private static boolean orToolsLoaded = false;

    /**
     * A-layer SCIP seed(s) for the pattern-selection MIP, swept across demand orders.
     * Consolidated to seed 1 only: it lands the selection on low-pattern (42-44) 花型集
     * that the B-layer assembles to 74-76, while the default seed 0 caps at 79. Seed 7
     * was dropped — it produces 46-pattern sets that Stage5 frequently can't solve in
     * time (NOT_SOLVED → greedy fallback), i.e. wasted runtime for no reliable gain.
     * Fewer candidates = roughly half the solve time at the same result level.
     */
    private static final int[] A_LAYER_SEEDS = {1};

    // Baseline defaults only; each solve call uses a per-request copy.
    private final SolverParameters baseParams;

    public CuttingSolver() {
        this(SolverParameters.createDefault());
    }

    public CuttingSolver(SolverParameters params) {
        this.baseParams = (params == null ? SolverParameters.createDefault() : params.copy());
        this.baseParams.sanitize();
        loadOrTools();
    }

    private synchronized void loadOrTools() {
        if (!orToolsLoaded) {
            try {
                Loader.loadNativeLibraries();
                orToolsLoaded = true;
                log.info("OR-Tools loaded.");
            } catch (Exception e) {
                log.error("OR-Tools load failed", e);
            }
        }
    }

    @Override
    public boolean supports(SolverConfig config) {
        return config.isUseNewSolver();
    }

    @Override
    public List<CuttingInstruction> solve(List<SolverOrderItem> items, SolverConfig config) {
        log.info("\n========== NewSolver START ==========");
        long startTime = System.currentTimeMillis();

        // Per-request parameter snapshot to avoid cross-request contamination.
        SolverParameters params = baseParams.copy();
        params.mergeFrom(config);
        log.debug("Params: {}", params);

        // Per-request solver components to keep state isolated.
        PatternGenerator patternGenerator = new PatternGenerator(params);
        ColumnGenerationSolver colGenSolver = new ColumnGenerationSolver(params);
        Map<String, List<SolverOrderItem>> groups = items.stream()
                .collect(Collectors.groupingBy(SolverOrderItem::getGroupKey));

        log.info("Group count: {}", groups.size());

        List<CuttingInstruction> allInstructions = new ArrayList<>();

        try (SolveReportWriter report = SolveReportWriter.create()) {
            log.info("Solve report: {}", report.getFilePath());

            int totalGroups = groups.size();
            for (Map.Entry<String, List<SolverOrderItem>> group : groups.entrySet()) {
                String groupKey = group.getKey();
                List<SolverOrderItem> groupItems = group.getValue();
                long groupStart = System.currentTimeMillis();

                log.info("--- Processing group: {} ({} items) ---", groupKey, groupItems.size());

                // Width-ascending TreeMap (NOT HashMap): a deterministic demand order
                // is what makes the whole solve reproducible. HashMap bucket order
                // created degenerate ties that SCIP's RNG broke differently each solve,
                // which was the root cause of the run-to-run sequence-group swing.
                Map<Integer, Integer> demands = new java.util.TreeMap<>();
                for (SolverOrderItem item : groupItems) {
                    demands.merge(item.getWidth(), item.getDemand(), Integer::sum);
                }

                Set<Integer> allowOverSet = buildAllowOverSet(demands, params);
                PatternAlignmentContext alignmentContext = PatternAlignmentContext.from(groupItems);
                report.beginGroup(groupKey, groupItems, demands, allowOverSet, totalGroups);
                log.debug("Allow-over widths: {}", allowOverSet);

                List<PatternCandidate> patterns = patternGenerator.generate(demands);
                patterns = colGenSolver.solve(patterns, demands, allowOverSet);

                // Multi-start over deterministic demand orders. Each order makes the
                // selection MIP build variables in a different order -> a different
                // deterministic pattern set. Pooling candidates across orders widens the
                // search so the best assignment is more reliably low (a single order
                // caps at 81; the demand-descending order reaches a set that hits 79).
                List<MultiStageMIPSolver.SolveCandidate> solveCandidates = new ArrayList<>();
                java.util.Set<String> seenCandidateSigs = new java.util.HashSet<>();
                List<ALayerTask> aLayerTasks = new ArrayList<>();
                List<Map<Integer, Integer>> demandOrders = buildDemandOrders(demands);
                double[] alignmentLambdas = aLayerAlignmentLambdas();
                int nestedWidthCap = nestedWidthCandidateCap(params);
                for (int orderIdx = 0; orderIdx < demandOrders.size(); orderIdx++) {
                    Map<Integer, Integer> orderedDemands = demandOrders.get(orderIdx);
                    // Every order: cheap primary (legacy) only, swept over a small set of
                    // A-layer SCIP seeds. The seed deterministically steers the selection
                    // MIP onto a different 花型集 among tie-degenerate optima — the multi-start
                    // dimension that finds lower-group sets. The expensive diverse generation
                    // was removed: across every measured run its candidates never won (always
                    // 85-103, beaten by a legacy primary), so it was ~half the runtime for no
                    // gain. Distinct 花型集 are deduped by signature so the B-layer assignment
                    // runs once per genuinely different set, not once per (order, seed).
                    List<ALayerTask> orderTasks = new ArrayList<>();
                    double[] parityPenalties = aLayerParityPenalties();
                    for (int seed : A_LAYER_SEEDS) {
                        for (double alignmentLambda : alignmentLambdas) {
                            for (double parityPenalty : parityPenalties) {
                                // parity 经系统属性注入（LegacyOrderPatternSelectionSolver
                                // 的 parityPenalty() 读取），调用后立即还原
                                // Scoped runtime override; do not leak A-layer experiment flags
                                // through JVM-wide system properties.
                                orderTasks.add(new ALayerTask(
                                        "o" + orderIdx + "-s" + seed + "-a"
                                                + formatLambda(alignmentLambda)
                                                + "-p" + formatLambda(parityPenalty) + "-",
                                        patterns,
                                        orderedDemands,
                                        allowOverSet,
                                        seed,
                                        alignmentLambda,
                                        parityPenalty,
                                        alignmentContext));
                            }
                        }
                    }
                    aLayerTasks.addAll(orderTasks);
                }
                if (nestedWidthCap >= params.getMinRollWidth()) {
                    List<PatternCandidate> nestedPatterns = patterns.stream()
                            .filter(pattern -> pattern.getRollWidth() <= nestedWidthCap)
                            .toList();
                    if (!nestedPatterns.isEmpty()) {
                        double[] parityPenalties = aLayerParityPenalties();
                        double nestedParity = parityPenalties[parityPenalties.length - 1];
                        aLayerTasks.add(new ALayerTask(
                                "nested-w" + nestedWidthCap + "-p"
                                        + formatLambda(nestedParity) + "-",
                                nestedPatterns,
                                demandOrders.get(0),
                                allowOverSet,
                                A_LAYER_SEEDS[0],
                                0.0,
                                nestedParity,
                                alignmentContext));
                    }
                }
                List<MultiStageMIPSolver.SolveCandidate> rawCandidates;
                try (SolverTaskExecutor executor = createTaskExecutor(
                        "a-layer", "cutting.parallel.aLayer.enabled",
                        "cutting.parallel.aLayer.threads", aLayerTasks.size())) {
                    logParallelStage("A-layer", executor, aLayerTasks.size());
                    rawCandidates = executor.mapOrdered(aLayerTasks,
                                    task -> solveALayerTask(task, params)).stream()
                            .filter(Objects::nonNull)
                            .toList();
                }
                for (MultiStageMIPSolver.SolveCandidate candidate : rawCandidates) {
                    String sig = solutionSignature(candidate.result().getSolution());
                    if (seenCandidateSigs.add(sig)) {
                        solveCandidates.add(candidate);
                    }
                }
                if (solveCandidates.isEmpty()) {
                    log.warn("Solve failed for group: {}", groupKey);
                    report.writeGroupFailure(
                            groupKey,
                            "pattern selection returned no candidates",
                            System.currentTimeMillis() - groupStart);
                    continue;
                }

                // Full assignment (Stage5 + Phase2) on every pattern candidate. Cheap
                // greedy screening was tried but its group ordering does not track the
                // Stage5 result, so it dropped the genuinely best candidates.
                GroupSolvePlan bestPlan = null;
                List<SolveReportWriter.CandidateRow> reportRows = new ArrayList<>();
                List<SolveReportWriter.SequenceCandidateRow> sequenceReportRows = new ArrayList<>();
                List<CandidateEvaluation> candidateEvaluations = evaluateCandidates(
                        solveCandidates, groupKey, groupItems, demands, params);
                for (CandidateEvaluation evaluation : candidateEvaluations) {
                    int candidateIndex = evaluation.order();
                    MultiStageMIPSolver.SolveCandidate solveCandidate = evaluation.candidate();
                    SolverResult result = solveCandidate.result();
                    printSolutionSummary(result, demands);

                    InstructionConverter.ConversionResult conversion = evaluation.conversion();
                    List<CuttingInstruction> instructions = conversion.instructions();
                    SequenceGroupPostProcessor.GroupStats stats =
                            SequenceGroupPostProcessor.computeGroupStats(instructions);
                    int sequenceGroups = stats.groups();

                    for (InstructionConverter.SequenceCandidateRow row : conversion.candidateRows()) {
                        sequenceReportRows.add(new SolveReportWriter.SequenceCandidateRow(
                                solveCandidate.name(),
                                row.name(),
                                row.sequenceGroupCount(),
                                row.instructions(),
                                row.selected()));
                    }

                    log.info("Pattern candidate {}: patterns={}, waste={}mm, over={}, groups={}, "
                                    + "oddCars={}, oneCars={}, smallCars={}, assignmentWinner={}",
                            solveCandidate.name(),
                            result.getPatternCount(),
                            result.getTotalWaste(),
                            result.getTotalOverProduction(),
                            sequenceGroups,
                            stats.oddCarGroups(),
                            stats.oneCarGroups(),
                            stats.smallCarGroups(),
                            conversion.selectedName());

                    reportRows.add(new SolveReportWriter.CandidateRow(
                            solveCandidate.name(),
                            result,
                            sequenceGroups,
                            params.getTotalWidth()));

                    GroupSolvePlan plan = new GroupSolvePlan(
                            solveCandidate.name(),
                            result,
                            instructions,
                            sequenceGroups,
                            stats.oddCarGroups(),
                            stats.oneCarGroups(),
                            stats.smallCarGroups(),
                            conversion.selectedName(),
                            candidateIndex);
                    if (bestPlan == null || PLAN_SELECTOR.isBetter(
                            toPlanQuality(plan), toPlanQuality(bestPlan))) {
                        bestPlan = plan;
                    }
                }

                if (bestPlan == null) {
                    log.warn("No usable instruction plan produced for group: {}", groupKey);
                    report.writeGroupFailure(
                            groupKey,
                            "instruction conversion produced no usable plan",
                            System.currentTimeMillis() - groupStart);
                    continue;
                }

                log.info("Selected pattern candidate for group {}: {} / {} (groups={}, patterns={}, waste={}mm)",
                        groupKey,
                        bestPlan.name(),
                        bestPlan.sequenceCandidateName(),
                        bestPlan.sequenceGroupCount(),
                        bestPlan.result().getPatternCount(),
                        bestPlan.result().getTotalWaste());

                report.writeGroupResult(
                        reportRows,
                        sequenceReportRows,
                        bestPlan.name(),
                        bestPlan.result().getSolution(),
                        bestPlan.sequenceGroupCount(),
                        params.getTotalWidth(),
                        System.currentTimeMillis() - groupStart,
                        demands);

                allInstructions.addAll(bestPlan.instructions());
                log.debug("Generated instructions: {}", bestPlan.instructions().size());
            }

            report.writeSummary(allInstructions.size());
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("\n========== NewSolver DONE ==========");
        log.info("Elapsed: {}ms", totalTime);
        log.info("Total instructions: {}", allInstructions.size());

        return allInstructions;
    }

    private MultiStageMIPSolver.SolveCandidate solveALayerTask(
            ALayerTask task, SolverParameters params) {
        try {
            MultiStageMIPSolver.SolveCandidate primary = SolverRuntimeProperties.withOverrides(
                    Map.of("cutting.aLayerParityPenalty",
                            Double.toString(task.parityPenalty())),
                    () -> new MultiStageMIPSolver(params).solvePrimaryOnly(
                            new ArrayList<>(task.patterns()),
                            task.demands(),
                            task.allowOverWidths(),
                            task.seed(),
                            task.alignmentLambda(),
                            task.alignmentContext()));
            return primary == null
                    ? null
                    : new MultiStageMIPSolver.SolveCandidate(
                            task.namePrefix() + primary.name(), primary.result());
        } catch (RuntimeException e) {
            log.warn("A-layer task {} failed; remaining candidates continue",
                    task.namePrefix(), e);
            return null;
        }
    }

    private List<CandidateEvaluation> evaluateCandidates(
            List<MultiStageMIPSolver.SolveCandidate> solveCandidates,
            String groupKey,
            List<SolverOrderItem> groupItems,
            Map<Integer, Integer> demands,
            SolverParameters params) {
        List<CandidateTask> tasks = new ArrayList<>(solveCandidates.size());
        for (int i = 0; i < solveCandidates.size(); i++) {
            tasks.add(new CandidateTask(i, solveCandidates.get(i)));
        }

        List<CandidateEvaluation> baseEvaluations;
        try (SolverTaskExecutor executor = createTaskExecutor(
                "candidate", "cutting.parallel.candidates.enabled",
                "cutting.parallel.candidates.threads", tasks.size())) {
            boolean outerParallel = executor.parallelism(tasks.size()) > 1;
            logParallelStage("Stage5+LNS candidates", executor, tasks.size());
            baseEvaluations = executor.mapOrdered(tasks,
                            task -> evaluateBaseCandidate(
                                    task, groupKey, groupItems, demands, params, outerParallel))
                    .stream()
                    .filter(Objects::nonNull)
                    .toList();
        }

        if (!qualityMode() || !SetPartitionRefiner.isEnabled() || baseEvaluations.isEmpty()) {
            return baseEvaluations;
        }
        int bestBaseGroups = baseEvaluations.stream()
                .mapToInt(evaluation -> evaluation.conversion().selectedSequenceGroups())
                .min()
                .orElse(Integer.MAX_VALUE);
        int gap = SetPartitionRefiner.skipGapThreshold();
        List<CandidateEvaluation> shortlistedEvaluations = baseEvaluations.stream()
                .filter(evaluation -> isSprShortlisted(
                        evaluation.conversion().selectedSequenceGroups(), bestBaseGroups, gap))
                .toList();
        try (SolverTaskExecutor executor = createTaskExecutor(
                "spr-candidate", "cutting.parallel.sprCandidates.enabled",
                "cutting.parallel.sprCandidates.threads", shortlistedEvaluations.size())) {
            boolean outerParallel = executor.parallelism(shortlistedEvaluations.size()) > 1;
            log.info("SPR shortlist: {}/{} candidates (bestGroups={}, gap={})",
                    shortlistedEvaluations.size(), baseEvaluations.size(), bestBaseGroups, gap);
            logParallelStage("SPR candidates", executor, shortlistedEvaluations.size());
            List<CandidateEvaluation> refined = executor.mapOrdered(
                    shortlistedEvaluations,
                    evaluation -> refineCandidate(evaluation, params, outerParallel));
            Map<Integer, CandidateEvaluation> refinedByOrder = refined.stream()
                    .collect(Collectors.toMap(CandidateEvaluation::order, evaluation -> evaluation));
            return baseEvaluations.stream()
                    .map(evaluation -> refinedByOrder.getOrDefault(
                            evaluation.order(), evaluation))
                    .toList();
        }
    }

    private CandidateEvaluation evaluateBaseCandidate(
            CandidateTask task,
            String groupKey,
            List<SolverOrderItem> groupItems,
            Map<Integer, Integer> demands,
            SolverParameters params,
            boolean outerParallel) {
        try {
            InstructionConverter converter = new InstructionConverter(params);
            InstructionConverter.ConversionResult conversion;
            if (outerParallel) {
                conversion = SolverRuntimeProperties.withOverrides(
                        Map.of(
                                "cutting.lns.parallel", "false",
                                "cutting.spr.parallel", "false"),
                        () -> converter.convertWithoutSetPartition(
                                task.candidate().result().getSolution(),
                                groupKey,
                                groupItems,
                                demands));
            } else {
                conversion = converter.convertWithoutSetPartition(
                        task.candidate().result().getSolution(),
                        groupKey,
                        groupItems,
                        demands);
            }
            return new CandidateEvaluation(task.order(), task.candidate(), conversion);
        } catch (RuntimeException e) {
            log.warn("Candidate {} conversion failed; remaining candidates continue",
                    task.candidate().name(), e);
            return null;
        }
    }

    private CandidateEvaluation refineCandidate(
            CandidateEvaluation evaluation,
            SolverParameters params,
            boolean outerParallel) {
        try {
            InstructionConverter converter = new InstructionConverter(params);
            InstructionConverter.ConversionResult refined = outerParallel
                    ? SolverRuntimeProperties.withOverrides(
                            Map.of("cutting.spr.parallel", "false"),
                            () -> converter.refineWithSetPartition(evaluation.conversion()))
                    : converter.refineWithSetPartition(evaluation.conversion());
            return new CandidateEvaluation(
                    evaluation.order(), evaluation.candidate(), refined);
        } catch (RuntimeException e) {
            log.warn("SPR candidate {} failed; base result remains active",
                    evaluation.candidate().name(), e);
            return evaluation;
        }
    }

    static boolean isSprShortlisted(int groups, int bestGroups, int gap) {
        return groups - bestGroups <= Math.max(0, gap);
    }

    private SolverTaskExecutor createTaskExecutor(
            String scope,
            String enabledProperty,
            String threadsProperty,
            int taskCount) {
        boolean enabled = SolverRuntimeProperties.getBoolean(enabledProperty, true);
        if (!enabled || taskCount <= 1 || SolverRunColumnArchive.isCaptureActive()) {
            return new DirectSolverTaskExecutor();
        }
        int requestedThreads = SolverRuntimeProperties.getInt(
                threadsProperty, BoundedSolverTaskExecutor.globalParallelism());
        return BoundedSolverTaskExecutor.create(scope, requestedThreads);
    }

    private void logParallelStage(String stage, SolverTaskExecutor executor, int taskCount) {
        log.info("{} execution: tasks={}, parallelism={}",
                stage, taskCount, executor.parallelism(taskCount));
    }

    private Set<Integer> buildAllowOverSet(Map<Integer, Integer> demands, SolverParameters params) {
        List<Map.Entry<Integer, Integer>> sorted = demands.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());

        Set<Integer> allowOverSet = new HashSet<>();
        for (int i = 0; i < Math.min(params.getTopK(), sorted.size()); i++) {
            allowOverSet.add(sorted.get(i).getKey());
        }
        allowOverSet.addAll(params.getForceAllowOverWidths());
        return allowOverSet;
    }

    /**
     * Deterministic demand orderings for multi-start. Each ordering only changes the
     * MIP variable-creation order, yielding a different (but reproducible) pattern set.
     */
    private List<Map<Integer, Integer>> buildDemandOrders(Map<Integer, Integer> demands) {
        List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(demands.entrySet());
        List<Map<Integer, Integer>> orders = new ArrayList<>();
        // order 0: width-ascending. On the real four-line case this single order is
        // the winner (o0 reached 47, beating o1/o2/o3 at 50/52/52). Production runs
        // this ONE path: the other three cost ~13 min of B-layer LNS for a strictly
        // worse result, which is unusable in production.
        orders.add(toOrderedMap(entries, Comparator.comparingInt(Map.Entry::getKey)));
        if (candidateOrderCount() <= 1) {
            return orders;
        }
        // The remaining orders are an offline multi-start dimension only; restore the
        // full 4-candidate sweep with -Dcutting.candidateOrders=4.
        orders.add(toOrderedMap(entries, Comparator
                .comparingInt((Map.Entry<Integer, Integer> e) -> e.getValue()).reversed()
                .thenComparingInt(Map.Entry::getKey)));
        orders.add(toOrderedMap(entries, Comparator
                .comparingInt((Map.Entry<Integer, Integer> e) -> e.getKey()).reversed()));
        orders.add(toOrderedMap(entries, Comparator
                .comparingInt((Map.Entry<Integer, Integer> e) -> e.getValue())
                .thenComparingInt(Map.Entry::getKey)));
        int wanted = Math.min(candidateOrderCount(), orders.size());
        return new ArrayList<>(orders.subList(0, wanted));
    }

    /** Number of demand-order candidates to run. Default 1 (single fast path). */
    private int candidateOrderCount() {
        String raw = SolverRuntimeProperties.get("cutting.candidateOrders");
        if (raw != null && !raw.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(raw.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 1;
    }

    /**
     * 质量模式总开关（-Dcutting.quality=true 或 API lnsQuality）。展开为：
     * A层 parity 扫描 {0, 0.1} + B层双 LNS 变体（默认邻域 vs 扩大邻域）评优。
     * 依据（笔记13终局）：parity0.1 在 sixian 上给出 46大组 但在 t9est188 上 72→94
     * ——数据集脆弱，绝不能单独当默认；唯一稳健编排是多候选全评估后由
     * isBetterPlan（车数→组→odd→1车→small）拣优，结构上永不劣于单路径。
     */
    public static boolean qualityMode() {
        return SolverRuntimeProperties.getBoolean("cutting.quality", false);
    }

    static int nestedWidthCandidateCap(SolverParameters params) {
        String configuredSteps = SolverRuntimeProperties.get("cutting.nestedWidthCandidateSteps");
        int stepSize = Math.max(1, params.getStepSize());
        int defaultSteps = qualityMode()
                && Math.floorMod(params.getMaxRollWidth(), 100) == stepSize
                ? 1
                : 0;
        int steps = configuredSteps == null || configuredSteps.isBlank()
                ? defaultSteps
                : Math.max(0, SolverRuntimeProperties.getInt(
                        "cutting.nestedWidthCandidateSteps", defaultSteps));
        return steps == 0
                ? -1
                : params.getMaxRollWidth() - steps * stepSize;
    }

    /**
     * A-layer parity 惩罚扫描列表。默认 {0}（不生效）；质量模式默认 {0, 0.1}；
     * -Dcutting.aLayerParityPenalties=0,0.1,0.2 显式覆盖。
     */
    static double[] aLayerParityPenalties() {
        String raw = SolverRuntimeProperties.get("cutting.aLayerParityPenalties");
        if (raw == null || raw.isBlank()) {
            return qualityMode() ? new double[] {0.0, 0.1} : new double[] {0.0};
        }
        LinkedHashSet<Double> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                double value = Double.parseDouble(trimmed);
                if (!Double.isNaN(value) && value >= 0.0) {
                    values.add(value);
                }
            } catch (NumberFormatException ignored) {
                // Skip bad tokens and keep the rest of the sweep usable.
            }
        }
        if (values.isEmpty()) {
            values.add(0.0);
        }
        double[] result = new double[values.size()];
        int i = 0;
        for (double value : values) {
            result[i++] = value;
        }
        return result;
    }

    /** A-layer alignment lambda sweep. Include 0.0 as the no-alignment baseline. */
    private double[] aLayerAlignmentLambdas() {
        String raw = SolverRuntimeProperties.get("cutting.aLayerAlignmentLambdas", "0");
        LinkedHashSet<Double> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                double value = Double.parseDouble(trimmed);
                if (!Double.isNaN(value) && value >= 0.0) {
                    values.add(value);
                }
            } catch (NumberFormatException ignored) {
                // Skip bad tokens and keep the rest of the sweep usable.
            }
        }
        if (values.isEmpty()) {
            values.add(0.0);
        }
        double[] result = new double[values.size()];
        int i = 0;
        for (double value : values) {
            result[i++] = value;
        }
        return result;
    }

    private String formatLambda(double value) {
        if (Math.rint(value) == value) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value).replace('.', 'p');
    }

    private Map<Integer, Integer> toOrderedMap(List<Map.Entry<Integer, Integer>> entries,
            Comparator<Map.Entry<Integer, Integer>> comparator) {
        Map<Integer, Integer> ordered = new LinkedHashMap<>();
        entries.stream().sorted(comparator).forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }

    private String solutionSignature(Map<PatternCandidate, Integer> solution) {
        return solution.entrySet().stream()
                .map(e -> e.getKey().signature() + "x" + e.getValue())
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private void printSolutionSummary(SolverResult result, Map<Integer, Integer> demands) {
        log.info("Solution summary:");
        log.info("  Total rolls: {}", result.getTotalRolls());
        log.info("  Pattern count: {}", result.getPatternCount());
        log.info("  Total waste: {}mm", result.getTotalWaste());
        log.info("  Total over: {}", result.getTotalOverProduction());

        Map<Integer, Integer> production = new HashMap<>();
        for (Map.Entry<PatternCandidate, Integer> e : result.getSolution().entrySet()) {
            for (Map.Entry<Integer, Integer> pe : e.getKey().getPattern().entrySet()) {
                production.merge(pe.getKey(), pe.getValue() * e.getValue(), Integer::sum);
            }
        }

        for (int w : demands.keySet().stream().sorted().collect(Collectors.toList())) {
            int demand = demands.get(w);
            int prod = production.getOrDefault(w, 0);
            String status = prod == demand ? "[exact]"
                    : (prod > demand ? "[over+" + (prod - demand) + "]" : "[under" + (prod - demand) + "]");
            log.info("    {}mm: demand={} produced={} {}", w, demand, prod, status);
        }
    }

    public SolverParameters getParameters() {
        return baseParams.copy();
    }

    private boolean isBetterPlan(GroupSolvePlan candidate, GroupSolvePlan currentBest) {
        return PLAN_SELECTOR.isBetter(
                toPlanQuality(candidate), toPlanQuality(currentBest));
    }

    private PlanQuality toPlanQuality(GroupSolvePlan plan) {
        return new PlanQuality(
                plan.result().getTotalOverProduction(),
                plan.result().getTotalRolls(),
                plan.sequenceGroupCount(),
                plan.oddCarGroups(),
                plan.oneCarGroups(),
                plan.smallCarGroups(),
                plan.result().getPatternCount(),
                plan.result().getTotalWaste(),
                plan.order());
    }

    private record ALayerTask(
            String namePrefix,
            List<PatternCandidate> patterns,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverWidths,
            int seed,
            double alignmentLambda,
            double parityPenalty,
            PatternAlignmentContext alignmentContext) {

        private ALayerTask {
            patterns = List.copyOf(patterns);
            demands = Collections.unmodifiableMap(new LinkedHashMap<>(demands));
            allowOverWidths = Set.copyOf(allowOverWidths);
        }
    }

    private record CandidateTask(
            int order,
            MultiStageMIPSolver.SolveCandidate candidate) {
    }

    private record CandidateEvaluation(
            int order,
            MultiStageMIPSolver.SolveCandidate candidate,
            InstructionConverter.ConversionResult conversion) {
    }

    private record GroupSolvePlan(
            String name,
            SolverResult result,
            List<CuttingInstruction> instructions,
            int sequenceGroupCount,
            int oddCarGroups,
            int oneCarGroups,
            int smallCarGroups,
            String sequenceCandidateName,
            int order) {
    }

}
