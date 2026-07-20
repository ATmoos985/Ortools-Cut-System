package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Djx188FastQualityBaselineRegressionTest {

    @Test
    void fastPreviewProducesCompleteZeroOverBaselineWhenOverCapIsZero() throws Exception {
        List<SolverOrderItem> items = loadItems();
        SolverConfig config = buildConfig();

        long startedAt = System.currentTimeMillis();
        List<CuttingInstruction> instructions = SolverRuntimeProperties.withOverrides(
                fastOverrides(),
                () -> new CuttingSolver().solve(items, config));
        long elapsedMs = System.currentTimeMillis() - startedAt;

        List<ColumnUse> uses = SolverExperimentSnapshot.fromInstructions(instructions);
        Map<String, Integer> demand = SolverExperimentSnapshot.demandOf(items);
        Map<String, Integer> produced = SolverExperimentSnapshot.producedBy(uses);
        int over = produced.entrySet().stream()
                .mapToInt(entry -> Math.max(0,
                        entry.getValue() - demand.getOrDefault(entry.getKey(), 0)))
                .sum();

        assertFalse(instructions.isEmpty());
        assertEquals(demand, produced);
        assertEquals(0, over);
        System.out.printf("DJX188 FAST zero-over: instructions=%d over=%d elapsedMs=%d%n",
                instructions.size(), over, elapsedMs);
    }

    @Test
    void qualityContinuesFromFastBaselineWithoutRegressingPrimaryMetrics() throws Exception {
        List<SolverOrderItem> items = loadItems();
        SolverConfig config = buildConfig();

        long fastStartedAt = System.currentTimeMillis();
        List<CuttingInstruction> fast = SolverRuntimeProperties.withOverrides(
                fastOverrides(), () -> new CuttingSolver().solve(items, config));
        long fastElapsedMs = System.currentTimeMillis() - fastStartedAt;

        long qualityStartedAt = System.currentTimeMillis();
        List<CuttingInstruction> quality = SolverRuntimeProperties.withOverrides(
                qualityOverrides(), () -> new CuttingSolver().solve(items, config));
        long qualityElapsedMs = System.currentTimeMillis() - qualityStartedAt;

        Map<String, Integer> demand = SolverExperimentSnapshot.demandOf(items);
        List<ColumnUse> fastUses = SolverExperimentSnapshot.fromInstructions(fast);
        List<ColumnUse> qualityUses = SolverExperimentSnapshot.fromInstructions(quality);
        SolverExperimentSnapshot.Metrics fastMetrics =
                SolverExperimentSnapshot.metrics(fastUses, config.getTotalWidth());
        SolverExperimentSnapshot.Metrics qualityMetrics =
                SolverExperimentSnapshot.metrics(qualityUses, config.getTotalWidth());

        assertFalse(fast.isEmpty());
        assertFalse(quality.isEmpty());
        assertEquals(demand, SolverExperimentSnapshot.producedBy(fastUses));
        assertEquals(demand, SolverExperimentSnapshot.producedBy(qualityUses));
        assertTrue(qualityMetrics.cars() < fastMetrics.cars()
                        || qualityMetrics.cars() == fastMetrics.cars()
                        && qualityMetrics.groups() <= fastMetrics.groups(),
                () -> "quality=" + qualityMetrics + ", fast=" + fastMetrics);
        System.out.printf("DJX188 baseline continuation: FAST=%s/%dms QUALITY=%s/%dms%n",
                fastMetrics, fastElapsedMs, qualityMetrics, qualityElapsedMs);
    }

    private Map<String, String> fastOverrides() {
        return Map.ofEntries(
                Map.entry("cutting.fast.preview", "true"),
                Map.entry("cutting.fast.budgetMs", "10000"),
                Map.entry("cutting.quality", "false"),
                Map.entry("cutting.aLayerParityPenalties", "0"),
                Map.entry("cutting.aLayerAlignmentLambdas", "0"),
                Map.entry("cutting.nestedWidthCandidateSteps", "0"),
                Map.entry("cutting.candidateOrders", "1"),
                Map.entry("cutting.lns.enabled", "false"),
                Map.entry("cutting.phase2.enabled", "false"),
                Map.entry("cutting.demandPeak.enabled", "false"),
                Map.entry("cutting.spr.enabled", "false"));
    }

    private Map<String, String> qualityOverrides() {
        return Map.ofEntries(
                Map.entry("cutting.fast.preview", "false"),
                Map.entry("cutting.quality", "true"),
                Map.entry("cutting.aLayerParityPenalties", "0,0.1"),
                Map.entry("cutting.lns.enabled", "true"),
                Map.entry("cutting.lns.enrichPatterns", "false"),
                Map.entry("cutting.demandPeak.enabled", "false"),
                Map.entry("cutting.spr.enabled", "true"),
                Map.entry("cutting.spr.reverseTiePass", "true"),
                Map.entry("cutting.spr.reverseTieMaxIterations", "1"),
                Map.entry("cutting.spr.portfolioPass", "false"));
    }

    private List<SolverOrderItem> loadItems() throws Exception {
        InputStream input = getClass().getResourceAsStream("/djx188.csv");
        if (input == null) {
            throw new IllegalStateException("djx188.csv not found on test classpath");
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

    private SolverConfig buildConfig() {
        SolverConfig config = new SolverConfig();
        config.setMode("variable");
        config.setMinWidth(4300);
        config.setMaxWidth(4400);
        config.setStepSize(10);
        config.setTotalWidth(4600);
        config.setTotalOverCap(0);
        config.setMaxIterations(300);
        config.setTimeoutMs(120000L);
        config.setUseNewSolver(true);
        config.setNewSolverTopK(3);
        config.setNewSolverMaxPatterns(800);
        config.setNewSolverMaxDistinctWidths(4);
        config.setNewSolverStage4TimeLimit(30000L);
        config.setNewSolverSeqGroupAlpha(1.0);
        config.setNewSolverSeqGroupBeta(0.0);
        config.setNewSolverUseOptimizedAssignment(true);
        config.setNewSolverUnderPenalty(1e6);
        return config;
    }
}
