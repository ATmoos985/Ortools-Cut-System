package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class T42Djx250RegressionTest {

    @Test
    void exactQualityProfileEliminatesSingleCarGroup() throws Exception {
        verifyExactQualityProfile(4400);
    }

    @Test
    void wideningRollRangeDoesNotLoseTheKnownQualityFloor() throws Exception {
        verifyExactQualityProfile(4410);
    }

    private void verifyExactQualityProfile(int maxWidth) throws Exception {
        List<SolverOrderItem> items = loadItems();
        SolverConfig config = buildConfig(maxWidth);

        long startedAt = System.currentTimeMillis();
        List<CuttingInstruction> instructions = SolverRuntimeProperties.withOverrides(
                Map.of("cutting.quality", "true"),
                () -> new CuttingSolver().solve(items, config));
        long elapsedMs = System.currentTimeMillis() - startedAt;

        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);
        List<ColumnUse> uses = SolverExperimentSnapshot.fromInstructions(instructions);
        SolverExperimentSnapshot.Metrics metrics =
                SolverExperimentSnapshot.metrics(uses, config.getTotalWidth());

        assertEquals(SolverExperimentSnapshot.demandOf(items),
                SolverExperimentSnapshot.producedBy(uses));
        assertEquals(45, metrics.cars());
        assertEquals(metrics.cars() % 2, stats.oddCarGroups());
        assertEquals(0, stats.oneCarGroups());
        assertTrue(stats.groups() <= 10, "sequence groups=" + stats.groups());

        System.out.printf("T42DJX250 maxWidth=%d: groups=%d odd=%d one=%d small=%d cars=%d "
                        + "waste=%d elapsedMs=%d%n",
                maxWidth, stats.groups(), stats.oddCarGroups(), stats.oneCarGroups(),
                stats.smallCarGroups(), metrics.cars(), metrics.waste(), elapsedMs);
    }

    private List<SolverOrderItem> loadItems() throws Exception {
        InputStream input = getClass().getResourceAsStream("/t42djx250.csv");
        if (input == null) {
            throw new IllegalStateException("t42djx250.csv not found on test classpath");
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

    private SolverConfig buildConfig(int maxWidth) {
        SolverConfig config = new SolverConfig();
        config.setMode("variable");
        config.setMinWidth(4300);
        config.setMaxWidth(maxWidth);
        config.setStepSize(10);
        config.setTotalWidth(4600);
        config.setTotalOverCap(Integer.getInteger("cutting.test.t42OverCap", 30));
        config.setMaxIterations(300);
        config.setTimeoutMs(120000L);
        config.setUseNewSolver(true);
        config.setNewSolverTopK(3);
        config.setNewSolverMaxPatterns(800);
        config.setNewSolverMaxDistinctWidths(5);
        config.setNewSolverStage4TimeLimit(30000L);
        config.setNewSolverSeqGroupAlpha(1.0);
        config.setNewSolverSeqGroupBeta(0.0);
        config.setNewSolverUseOptimizedAssignment(true);
        config.setNewSolverUnderPenalty(1e6);
        return config;
    }
}
