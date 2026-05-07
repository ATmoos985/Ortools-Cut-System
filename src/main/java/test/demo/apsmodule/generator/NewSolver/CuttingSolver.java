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

                Map<Integer, Integer> demands = groupItems.stream()
                        .collect(Collectors.groupingBy(
                                SolverOrderItem::getWidth,
                                Collectors.summingInt(SolverOrderItem::getDemand)));

                Set<Integer> allowOverSet = buildAllowOverSet(demands, params);
                report.beginGroup(groupKey, groupItems, demands, allowOverSet, totalGroups);
                log.debug("Allow-over widths: {}", allowOverSet);

                List<PatternCandidate> patterns = patternGenerator.generate(demands);
                patterns = colGenSolver.solve(patterns, demands, allowOverSet);

                List<MultiStageMIPSolver.SolveCandidate> solveCandidates =
                        mipSolver.solveCandidates(patterns, demands, allowOverSet, groupItems);
                if (solveCandidates.isEmpty()) {
                    log.warn("Solve failed for group: {}", groupKey);
                    report.writeGroupFailure(
                            groupKey,
                            "pattern selection returned no candidates",
                            System.currentTimeMillis() - groupStart);
                    continue;
                }

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
                    int sequenceGroups = SequenceGroupPostProcessor.countTotalGroups(instructions);

                    for (InstructionConverter.SequenceCandidateRow row : conversion.candidateRows()) {
                        sequenceReportRows.add(new SolveReportWriter.SequenceCandidateRow(
                                solveCandidate.name(),
                                row.name(),
                                row.sequenceGroupCount(),
                                row.instructions(),
                                row.selected()));
                    }

                    log.info("Pattern candidate {}: patterns={}, waste={}mm, over={}, groups={}, assignmentWinner={}",
                            solveCandidate.name(),
                            result.getPatternCount(),
                            result.getTotalWaste(),
                            result.getTotalOverProduction(),
                            sequenceGroups,
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
                        System.currentTimeMillis() - groupStart);

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
        // Priority 1: utilization — lower waste is strictly better
        if (candidate.result().getTotalWaste() != currentBest.result().getTotalWaste()) {
            return candidate.result().getTotalWaste() < currentBest.result().getTotalWaste();
        }
        // Priority 2: sequence groups — fewer is better
        if (candidate.sequenceGroupCount() != currentBest.sequenceGroupCount()) {
            return candidate.sequenceGroupCount() < currentBest.sequenceGroupCount();
        }
        // Priority 3: pattern count — fewer distinct patterns simplifies production
        if (candidate.result().getPatternCount() != currentBest.result().getPatternCount()) {
            return candidate.result().getPatternCount() < currentBest.result().getPatternCount();
        }
        // Tiebreak: over-production, then candidate order
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
            String sequenceCandidateName,
            int order) {
    }
}
