package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-dataset guard: no DJX188 width or pattern is supplied to the analyzer. */
class ParityTrapCrossDatasetTest {

    @Test
    void t42Djx250UsesTheSameParityKernelAndDeterministicPeelingRules()
            throws Exception {
        Loader.loadNativeLibraries();
        List<SolverOrderItem> items = loadItems("/t42djx250.csv");
        SolverConfig config = buildConfig();
        List<CuttingInstruction> instructions = SolverRuntimeProperties.withOverrides(
                Map.of("cutting.quality", "true"),
                () -> new CuttingSolver().solve(items, config));

        Map<Integer, Integer> demands = widthDemands(items);
        Map<PatternCandidate, Integer> solution = patternSolution(instructions);
        PatternParityTrapAnalyzer.Analysis analysis =
                PatternParityTrapAnalyzer.analyze(solution, demands);

        List<Map.Entry<PatternCandidate, Integer>> reversedEntries =
                new ArrayList<>(solution.entrySet());
        Collections.reverse(reversedEntries);
        Map<PatternCandidate, Integer> reversed = new LinkedHashMap<>();
        reversedEntries.forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        assertEquals(analysis.steps(),
                PatternParityTrapAnalyzer.analyze(reversed, demands).steps());

        int cars = solution.values().stream().mapToInt(Integer::intValue).sum();
        List<PatternCandidate> oddPatterns = solution.entrySet().stream()
                .filter(entry -> entry.getValue() % 2 != 0)
                .map(Map.Entry::getKey)
                .toList();
        assertEquals(45, cars);
        assertEquals(1, oddPatterns.size());
        assertEquals(1, analysis.actualOddUsages());
        assertEquals(0, analysis.actualOneUsages());
        assertTrue(PatternParityFeasibilityOracle
                .hasDemandParityFingerprint(oddPatterns.get(0), demands));

        SolverParameters params = parameters();
        List<PatternCandidate> universe = new CompletePatternEnumerator(params, 5)
                .generate(demands);
        long compatibleOddCores = universe.stream()
                .filter(pattern -> PatternParityFeasibilityOracle
                        .hasDemandParityFingerprint(pattern, demands))
                .count();
        assertTrue(compatibleOddCores > 0);

        ParityTrapComplementSelector.Selection selection =
                ParityTrapComplementSelector.select(
                        analysis,
                        universe,
                        solution.keySet(),
                        demands,
                        params.getTotalWidth(),
                        5,
                        80);
        if (!analysis.forcedOddSteps().isEmpty()) {
            assertFalse(selection.targets().isEmpty());
        }

        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);
        System.out.printf("T42 PARITY-CROSS patterns=%d cars=%d groups=%d odd=%d one=%d "
                        + "peeled=%d core=%d forced=%s unexplained=%s "
                        + "compatibleOddCores=%d complements=%d%n",
                solution.size(), cars, stats.groups(), analysis.actualOddUsages(),
                analysis.actualOneUsages(), analysis.steps().size(),
                analysis.corePatternSignatures().size(), analysis.forcedOddWidths(),
                analysis.unexplainedOddUsages(), compatibleOddCores,
                selection.candidates().size());
    }

    private static Map<PatternCandidate, Integer> patternSolution(
            List<CuttingInstruction> instructions) {
        Map<String, PatternCandidate> bySignature = new LinkedHashMap<>();
        Map<String, Integer> usageBySignature = new LinkedHashMap<>();
        for (CuttingInstruction instruction : instructions) {
            PatternCandidate pattern = new PatternCandidate(
                    new TreeMap<>(instruction.getSubRolls()), instruction.getRollWidth());
            bySignature.putIfAbsent(pattern.signature(), pattern);
            usageBySignature.merge(
                    pattern.signature(), instruction.getUsageCount(), Integer::sum);
        }
        Map<PatternCandidate, Integer> result = new LinkedHashMap<>();
        usageBySignature.forEach((signature, usage) ->
                result.put(bySignature.get(signature), usage));
        return result;
    }

    private static Map<Integer, Integer> widthDemands(List<SolverOrderItem> items) {
        Map<Integer, Integer> demands = new TreeMap<>();
        items.forEach(item -> demands.merge(
                item.getWidth(), item.getDemand(), Integer::sum));
        return demands;
    }

    private static List<SolverOrderItem> loadItems(String resource) throws Exception {
        InputStream input = ParityTrapCrossDatasetTest.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException(resource + " not found on test classpath");
        }
        List<SolverOrderItem> items = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                SolverOrderItem item = new SolverOrderItem();
                item.setMessageText(fields[0]);
                item.setWidth(Integer.parseInt(fields[1]));
                item.setDemand(Integer.parseInt(fields[2]));
                item.setLength(Integer.parseInt(fields[3]));
                item.setSurfaceTreatment(fields[4]);
                item.setGroupKey(fields[3] + "m+" + fields[4]);
                items.add(item);
            }
        }
        return items;
    }

    private static SolverConfig buildConfig() {
        SolverConfig config = new SolverConfig();
        config.setMode("variable");
        config.setMinWidth(4300);
        config.setMaxWidth(4400);
        config.setStepSize(10);
        config.setTotalWidth(4600);
        config.setTotalOverCap(30);
        config.setMaxIterations(300);
        config.setTimeoutMs(120_000L);
        config.setUseNewSolver(true);
        config.setNewSolverTopK(3);
        config.setNewSolverMaxPatterns(800);
        config.setNewSolverMaxDistinctWidths(5);
        config.setNewSolverStage4TimeLimit(30_000L);
        config.setNewSolverSeqGroupAlpha(1.0);
        config.setNewSolverSeqGroupBeta(0.0);
        config.setNewSolverUseOptimizedAssignment(true);
        config.setNewSolverUnderPenalty(1e6);
        return config;
    }

    private static SolverParameters parameters() {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(4300);
        params.setMaxRollWidth(4400);
        params.setStepSize(10);
        params.setTotalWidth(4600);
        params.setTotalOverCap(30);
        params.setMaxPatterns(800);
        params.setMaxDistinctWidths(5);
        params.sanitize();
        return params;
    }
}
