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
import test.demo.apsmodule.generator.NewSolver.util.SolveDiagnostics;
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
    private static final double WASTE_GROUP_TOLERANCE_RATIO = 0.005;
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
        try (SolveDiagnostics.RunHandle ignored = SolveDiagnostics.beginRun("NewSolver")) {
            return solveInternal(items, config);
        }
    }

    private List<CuttingInstruction> solveInternal(List<SolverOrderItem> items, SolverConfig config) {
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
                .collect(Collectors.groupingBy(
                        SolverOrderItem::getGroupKey,
                        TreeMap::new,
                        Collectors.toList()));

        log.info("Group count: {}", groups.size());

        List<CuttingInstruction> allInstructions = new ArrayList<>();

        for (Map.Entry<String, List<SolverOrderItem>> group : groups.entrySet()) {
            String groupKey = group.getKey();
            List<SolverOrderItem> groupItems = group.getValue();

            log.info("--- Processing group: {} ({} items) ---", groupKey, groupItems.size());

            Map<Integer, Integer> demands = groupItems.stream()
                    .collect(Collectors.groupingBy(
                            SolverOrderItem::getWidth,
                            TreeMap::new,
                            Collectors.summingInt(SolverOrderItem::getDemand)));

            Set<Integer> allowOverSet = buildAllowOverSet(demands, params);
            log.debug("Allow-over widths: {}", allowOverSet);

            List<PatternCandidate> patterns = patternGenerator.generate(demands);
            patterns = colGenSolver.solve(patterns, demands, allowOverSet);

            List<MultiStageMIPSolver.SolveCandidate> solveCandidates = mipSolver.solveCandidates(patterns, demands, allowOverSet);
            if (solveCandidates.isEmpty()) {
                log.warn("Solve failed for group: {}", groupKey);
                continue;
            }

            GroupSolvePlan bestPlan = null;
            Map<String, GroupSolvePlan> plansByName = new LinkedHashMap<>();
            for (int candidateIndex = 0; candidateIndex < solveCandidates.size(); candidateIndex++) {
                MultiStageMIPSolver.SolveCandidate solveCandidate = solveCandidates.get(candidateIndex);
                SolverResult result = solveCandidate.result();
                printSolutionSummary(result, demands);

                List<CuttingInstruction> instructions = converter.convert(
                        result.getSolution(), groupKey, groupItems, demands);
                int sequenceGroups = SequenceGroupPostProcessor.countTotalGroups(instructions);

                log.info("Pattern candidate {}: patterns={}, waste={}mm, over={}, groups={}",
                        solveCandidate.name(),
                        result.getPatternCount(),
                        result.getTotalWaste(),
                        result.getTotalOverProduction(),
                        sequenceGroups);
                SolveDiagnostics.recordCandidate(
                        groupKey,
                        "pattern-candidate",
                        solveCandidate.name(),
                        sequenceGroups,
                        result.getTotalWaste(),
                        false,
                        "patterns=" + result.getPatternCount()
                                + ";over=" + result.getTotalOverProduction()
                                + ";rolls=" + result.getTotalRolls());

                GroupSolvePlan plan = new GroupSolvePlan(
                        solveCandidate.name(),
                        result,
                        instructions,
                        sequenceGroups,
                        candidateIndex);
                plansByName.putIfAbsent(plan.name(), plan);
                if (bestPlan == null || isBetterPlan(plan, bestPlan, params)) {
                    bestPlan = plan;
                }
            }

            if (bestPlan == null) {
                log.warn("No usable instruction plan produced for group: {}", groupKey);
                continue;
            }

            logCandidateDiagnostics(groupKey, plansByName, groupItems, bestPlan, params);
            SolveDiagnostics.recordCandidate(
                    groupKey,
                    "pattern-candidate",
                    bestPlan.name(),
                    bestPlan.sequenceGroupCount(),
                    bestPlan.result().getTotalWaste(),
                    true,
                    "selected");
            log.info("Selected pattern candidate for group {}: {} (groups={}, patterns={}, waste={}mm)",
                    groupKey,
                    bestPlan.name(),
                    bestPlan.sequenceGroupCount(),
                    bestPlan.result().getPatternCount(),
                    bestPlan.result().getTotalWaste());

            allInstructions.addAll(bestPlan.instructions());
            log.debug("Generated instructions: {}", bestPlan.instructions().size());
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("\n========== NewSolver DONE ==========");
        log.info("Elapsed: {}ms", totalTime);
        log.info("Total instructions: {}", allInstructions.size());

        return allInstructions;
    }

    private Set<Integer> buildAllowOverSet(Map<Integer, Integer> demands, SolverParameters params) {
        List<Map.Entry<Integer, Integer>> sorted = demands.entrySet().stream()
                .sorted((a, b) -> {
                    int byDemand = Integer.compare(b.getValue(), a.getValue());
                    if (byDemand != 0) {
                        return byDemand;
                    }
                    return Integer.compare(a.getKey(), b.getKey());
                })
                .collect(Collectors.toList());

        Set<Integer> allowOverSet = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(params.getTopK(), sorted.size()); i++) {
            allowOverSet.add(sorted.get(i).getKey());
        }
        params.getForceAllowOverWidths().stream()
                .sorted()
                .forEach(allowOverSet::add);
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

    private boolean isBetterPlan(GroupSolvePlan candidate, GroupSolvePlan currentBest, SolverParameters params) {
        int wasteDifference = Math.abs(candidate.result().getTotalWaste() - currentBest.result().getTotalWaste());
        int wasteTolerance = calculateWasteGroupTolerance(candidate.result(), currentBest.result(), params);
        if (wasteDifference <= wasteTolerance
                && candidate.sequenceGroupCount() != currentBest.sequenceGroupCount()) {
            return candidate.sequenceGroupCount() < currentBest.sequenceGroupCount();
        }
        if (candidate.result().getTotalWaste() != currentBest.result().getTotalWaste()) {
            return candidate.result().getTotalWaste() < currentBest.result().getTotalWaste();
        }
        if (candidate.sequenceGroupCount() != currentBest.sequenceGroupCount()) {
            return candidate.sequenceGroupCount() < currentBest.sequenceGroupCount();
        }
        if (candidate.result().getTotalOverProduction() != currentBest.result().getTotalOverProduction()) {
            return candidate.result().getTotalOverProduction() < currentBest.result().getTotalOverProduction();
        }
        if (candidate.result().getPatternCount() != currentBest.result().getPatternCount()) {
            return candidate.result().getPatternCount() < currentBest.result().getPatternCount();
        }
        if (candidate.result().getTotalRolls() != currentBest.result().getTotalRolls()) {
            return candidate.result().getTotalRolls() < currentBest.result().getTotalRolls();
        }
        return candidate.order() < currentBest.order();
    }

    private int calculateWasteGroupTolerance(SolverResult left, SolverResult right, SolverParameters params) {
        int referenceRolls = Math.max(left.getTotalRolls(), right.getTotalRolls());
        return (int) Math.ceil(WASTE_GROUP_TOLERANCE_RATIO * params.getTotalWidth() * referenceRolls);
    }

    private void logCandidateDiagnostics(String groupKey,
            Map<String, GroupSolvePlan> plansByName,
            List<SolverOrderItem> groupItems,
            GroupSolvePlan bestPlan,
            SolverParameters params) {
        GroupSolvePlan bestWaste = plansByName.get("legacy-best-waste");
        GroupSolvePlan minPattern = plansByName.get("legacy-min-pattern");
        if (bestWaste != null && minPattern != null) {
            int tolerance = calculateWasteGroupTolerance(bestWaste.result(), minPattern.result(), params);
            log.info("Legacy candidate diagnostic group={}: bestWaste groups={}, waste={}mm; minPattern groups={}, waste={}mm; deltaGroups={}, deltaWaste={}mm; tolerance={}mm",
                    groupKey,
                    bestWaste.sequenceGroupCount(),
                    bestWaste.result().getTotalWaste(),
                    minPattern.sequenceGroupCount(),
                    minPattern.result().getTotalWaste(),
                    minPattern.sequenceGroupCount() - bestWaste.sequenceGroupCount(),
                    minPattern.result().getTotalWaste() - bestWaste.result().getTotalWaste(),
                    tolerance);
        }

        int lowerBound = estimateSequenceGroupLowerBound(groupItems, bestPlan.result());
        if (lowerBound > 0) {
            double ratio = (double) bestPlan.sequenceGroupCount() / lowerBound;
            log.info("Selected candidate diagnostic group={}: selected={}, groups={}, simpleLowerBound={}, groupRatio={}",
                    groupKey,
                    bestPlan.name(),
                    bestPlan.sequenceGroupCount(),
                    lowerBound,
                    String.format("%.2f", ratio));
        }
    }

    private int estimateSequenceGroupLowerBound(List<SolverOrderItem> groupItems, SolverResult result) {
        Map<Integer, Set<String>> messagesByWidth = new TreeMap<>();
        for (SolverOrderItem item : groupItems) {
            messagesByWidth
                    .computeIfAbsent(item.getWidth(), ignored -> new TreeSet<>())
                    .add(item.getMessageText());
        }

        Map<Integer, Integer> maxSlotsByWidth = new TreeMap<>();
        for (PatternCandidate pattern : result.getSolution().keySet()) {
            for (Map.Entry<Integer, Integer> entry : pattern.getPattern().entrySet()) {
                maxSlotsByWidth.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }

        int lowerBound = 1;
        for (Map.Entry<Integer, Set<String>> entry : messagesByWidth.entrySet()) {
            int maxSlots = Math.max(1, maxSlotsByWidth.getOrDefault(entry.getKey(), 1));
            int widthLowerBound = (entry.getValue().size() + maxSlots - 1) / maxSlots;
            lowerBound = Math.max(lowerBound, widthLowerBound);
        }
        return lowerBound;
    }

    private record GroupSolvePlan(
            String name,
            SolverResult result,
            List<CuttingInstruction> instructions,
            int sequenceGroupCount,
            int order) {
    }
}
