package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import test.demo.apsmodule.generator.NewSolver.colgen.ColumnGenerationSolver;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.mip.MultiStageMIPSolver;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.model.SolverResult;
import test.demo.apsmodule.generator.NewSolver.output.InstructionConverter;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.generator.NewSolver.pattern.PatternGenerator;
import test.demo.apsmodule.generator.NewSolver.report.SolveReportWriter;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.solver.CuttingSolverAlgorithm;

import java.util.*;
import java.util.stream.Collectors;

/**
 * NewSolver unified entry.
 */
@Component
public class CuttingSolver implements CuttingSolverAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(CuttingSolver.class);
    private static boolean orToolsLoaded = false;

    /**
     * A-layer SCIP seeds swept on the cheap primary (legacy) path of alternate
     * demand orders. Each seed steers the pattern-selection MIP onto a different
     * deterministic 花型集; pooling them and keeping the lowest-group plan is what
     * recovers the better sets the old non-deterministic solver only hit by luck
     * (single-seed default caps at 79; seed 7 reaches 73 on T9EST188). Distinct
     * 花型集 are deduped by signature, so extra seeds rarely add B-layer cost.
     */
    private static final int[] A_LAYER_SEEDS = {1, 7};

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
        MultiStageMIPSolver mipSolver = new MultiStageMIPSolver(params);
        InstructionConverter converter = new InstructionConverter(params);

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
                List<Map<Integer, Integer>> demandOrders = buildDemandOrders(demands);
                for (int orderIdx = 0; orderIdx < demandOrders.size(); orderIdx++) {
                    Map<Integer, Integer> orderedDemands = demandOrders.get(orderIdx);
                    // Order 0 (default): full search (primary + diverse) at the default
                    // A-layer seed. Alternate orders: cheap primary only — but swept over
                    // a small set of A-layer SCIP seeds. The seed deterministically steers
                    // the selection MIP onto a different 花型集 among tie-degenerate optima,
                    // which is the multi-start dimension that finds a much lower-group set
                    // (seed 7 reaches 73 where the single-seed default caps at 79). Distinct
                    // 花型集 are deduped by signature so the expensive B-layer assignment runs
                    // once per genuinely different set, not once per seed.
                    List<MultiStageMIPSolver.SolveCandidate> orderCandidates = new ArrayList<>();
                    if (orderIdx == 0) {
                        orderCandidates.addAll(mipSolver.solveCandidates(
                                new ArrayList<>(patterns), orderedDemands, allowOverSet, groupItems));
                    } else {
                        for (int seed : A_LAYER_SEEDS) {
                            MultiStageMIPSolver.SolveCandidate primary = mipSolver.solvePrimaryOnly(
                                    new ArrayList<>(patterns), orderedDemands, allowOverSet, seed);
                            if (primary != null) {
                                orderCandidates.add(new MultiStageMIPSolver.SolveCandidate(
                                        "s" + seed + "-" + primary.name(), primary.result()));
                            }
                        }
                    }
                    for (MultiStageMIPSolver.SolveCandidate candidate : orderCandidates) {
                        String sig = solutionSignature(candidate.result().getSolution());
                        if (seenCandidateSigs.add(sig)) {
                            solveCandidates.add(new MultiStageMIPSolver.SolveCandidate(
                                    "o" + orderIdx + "-" + candidate.name(), candidate.result()));
                        }
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
                for (int candidateIndex = 0; candidateIndex < solveCandidates.size(); candidateIndex++) {
                    MultiStageMIPSolver.SolveCandidate solveCandidate = solveCandidates.get(candidateIndex);
                    SolverResult result = solveCandidate.result();
                    printSolutionSummary(result, demands);

                    InstructionConverter.ConversionResult conversion = converter.convertWithDetails(
                            result.getSolution(), groupKey, groupItems, demands);
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

                    log.info("Pattern candidate {}: patterns={}, waste={}mm, over={}, groups={}, oddCars={}, smallCars={}, assignmentWinner={}",
                            solveCandidate.name(),
                            result.getPatternCount(),
                            result.getTotalWaste(),
                            result.getTotalOverProduction(),
                            sequenceGroups,
                            stats.oddCarGroups(),
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
                            stats.smallCarGroups(),
                            conversion.selectedName(),
                            candidateIndex);
                    if (bestPlan == null || isBetterPlan(plan, bestPlan)) {
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
        orders.add(toOrderedMap(entries, Comparator.comparingInt(Map.Entry::getKey)));
        orders.add(toOrderedMap(entries, Comparator
                .comparingInt((Map.Entry<Integer, Integer> e) -> e.getValue()).reversed()
                .thenComparingInt(Map.Entry::getKey)));
        orders.add(toOrderedMap(entries, Comparator
                .comparingInt((Map.Entry<Integer, Integer> e) -> e.getKey()).reversed()));
        orders.add(toOrderedMap(entries, Comparator
                .comparingInt((Map.Entry<Integer, Integer> e) -> e.getValue())
                .thenComparingInt(Map.Entry::getKey)));
        return orders;
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
        // Priority 1: rolls — fewer rolls = higher yield (less material consumed)
        if (candidate.result().getTotalRolls() != currentBest.result().getTotalRolls()) {
            return candidate.result().getTotalRolls() < currentBest.result().getTotalRolls();
        }
        // Priority 2: sequence groups — fewer is better for production efficiency
        if (candidate.sequenceGroupCount() != currentBest.sequenceGroupCount()) {
            return candidate.sequenceGroupCount() < currentBest.sequenceGroupCount();
        }
        // Priority 3: odd-car groups — even car counts per group are preferred
        if (candidate.oddCarGroups() != currentBest.oddCarGroups()) {
            return candidate.oddCarGroups() < currentBest.oddCarGroups();
        }
        // Priority 4: small-car groups (≤5 cars) — fewer tiny groups is better
        if (candidate.smallCarGroups() != currentBest.smallCarGroups()) {
            return candidate.smallCarGroups() < currentBest.smallCarGroups();
        }
        // Priority 5: pattern count — fewer distinct patterns simplifies production
        if (candidate.result().getPatternCount() != currentBest.result().getPatternCount()) {
            return candidate.result().getPatternCount() < currentBest.result().getPatternCount();
        }
        // Priority 4: waste — lower waste is better (tie-break within same roll count)
        if (candidate.result().getTotalWaste() != currentBest.result().getTotalWaste()) {
            return candidate.result().getTotalWaste() < currentBest.result().getTotalWaste();
        }
        // Priority 5: over-production, then candidate order
        if (candidate.result().getTotalOverProduction() != currentBest.result().getTotalOverProduction()) {
            return candidate.result().getTotalOverProduction() < currentBest.result().getTotalOverProduction();
        }
        return candidate.order() < currentBest.order();
    }

    private record GroupSolvePlan(
            String name,
            SolverResult result,
            List<CuttingInstruction> instructions,
            int sequenceGroupCount,
            int oddCarGroups,
            int smallCarGroups,
            String sequenceCandidateName,
            int order) {
    }

}
