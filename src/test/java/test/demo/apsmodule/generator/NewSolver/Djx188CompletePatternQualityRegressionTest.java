package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Djx188CompletePatternQualityRegressionTest {

    @Test
    void completePatternQualityPathPreservesThePrimaryBaseline() throws Exception {
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        SolverConfig config = buildConfig();

        long startedAt = System.currentTimeMillis();
        List<CuttingInstruction> instructions = SolverRuntimeProperties.withOverrides(
                Map.ofEntries(
                        Map.entry("cutting.quality", "true"),
                        Map.entry("cutting.completePatterns.enabled", "true"),
                        Map.entry("cutting.completePatterns.maxDistinctWidths", "5"),
                        Map.entry("cutting.aLayerParityPenalties", "0.1"),
                        Map.entry("cutting.aLayerStage4NodeLimit", "10"),
                        Map.entry("cutting.aLayerStage4SafetyTimeLimitMs", "20000"),
                        Map.entry("cutting.aLayerAlignmentLambdas", "0"),
                        Map.entry("cutting.nestedWidthCandidateSteps", "0"),
                        Map.entry("cutting.candidateOrders", "1"),
                        Map.entry("cutting.parallel.aLayer.enabled", "false"),
                        Map.entry("cutting.parallel.candidates.enabled", "false"),
                        Map.entry("cutting.lns.enabled", "true"),
                        Map.entry("cutting.lns.enrichPatterns", "false"),
                        Map.entry("cutting.phase2.enabled", "false"),
                        Map.entry("cutting.demandPeak.enabled", "false"),
                        Map.entry("cutting.spr.enabled", "false")),
                () -> new CuttingSolver().solve(items, config));
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertFalse(instructions.isEmpty());
        List<ColumnUse> uses = SolverExperimentSnapshot.fromInstructions(instructions);
        SolverExperimentSnapshot.Metrics metrics =
                SolverExperimentSnapshot.metrics(uses, config.getTotalWidth());
        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);
        assertEquals(
                SolverExperimentSnapshot.demandOf(items),
                SolverExperimentSnapshot.producedBy(uses));
        assertEquals(169, metrics.cars());
        assertEquals(36_870, metrics.waste());
        assertTrue(stats.groups() <= 53, stats::toString);
        assertTrue(stats.oddCarGroups() <= 33, stats::toString);
        assertTrue(stats.oneCarGroups() <= 23, stats::toString);
        System.out.printf("DJX188 complete-pattern quality: metrics=%s stats=%s "
                        + "manualTarget=26/1/0 elapsedMs=%d%n",
                metrics, stats, elapsedMs);
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
        config.setTimeoutMs(180000L);
        config.setUseNewSolver(true);
        config.setNewSolverTopK(3);
        config.setNewSolverMaxPatterns(800);
        config.setNewSolverMaxDistinctWidths(4);
        config.setNewSolverStage4TimeLimit(120000L);
        config.setNewSolverSeqGroupAlpha(1.0);
        config.setNewSolverSeqGroupBeta(0.0);
        config.setNewSolverUseOptimizedAssignment(true);
        config.setNewSolverUnderPenalty(1e6);
        return config;
    }
}
