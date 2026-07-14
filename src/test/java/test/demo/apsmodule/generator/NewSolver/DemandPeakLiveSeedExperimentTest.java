package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakColumnPoolBuilder;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakInitialSolutionSolver;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.output.SolverRunColumnArchive;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live, no-history experiment for the demand-peak construction policy.
 *
 * <p>The production Stage-5 output is captured only as a feasible seed. The
 * candidate pool is then rebuilt from those live pattern shapes and current
 * demand; no frozen column archive or historical candidate is read. This
 * measures whether peak-directed message assignment can consolidate sequence
 * groups before any LNS/SPR repair is introduced.</p>
 */
class DemandPeakLiveSeedExperimentTest {

    @BeforeAll
    static void loadOrTools() {
        Loader.loadNativeLibraries();
    }

    @Test
    void rebuildsSixianFromLiveStage5Seed() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("cutting.test.demandPeakLiveSeed"));

        List<SolverOrderItem> items = loadItems();
        SolverConfig config = buildConfig();
        long baselineStarted = System.currentTimeMillis();
        SolverRunColumnArchive.Captured<List<CuttingInstruction>> captured =
                SolverRuntimeProperties.withOverrides(
                        Map.of("cutting.lns.enabled", "false", "cutting.quality", "false"),
                        () -> SolverRunColumnArchive.capture(
                                () -> new CuttingSolver().solve(items, config)));
        long baselineElapsedMs = System.currentTimeMillis() - baselineStarted;

        List<ColumnUse> seedUses = SolverExperimentSnapshot.fromInstructions(captured.value());
        Map<String, Integer> demand = SolverExperimentSnapshot.demandOf(items);
        SolverExperimentSnapshot.Metrics baseline = SolverExperimentSnapshot.metrics(
                seedUses, config.getTotalWidth());
        assertEquals(demand, SolverExperimentSnapshot.producedBy(seedUses));

        List<Column> protectedColumns = seedUses.stream().map(ColumnUse::column).toList();
        List<Map<Integer, Integer>> liveShapes = captured.value().stream()
                .<Map<Integer, Integer>>map(instruction -> new LinkedHashMap<>(instruction.getSubRolls()))
                .toList();
        Map<Integer, Map<String, Integer>> demandByWidth = demandByWidth(items);
        DemandPeakColumnPoolBuilder.Config poolConfig = new DemandPeakColumnPoolBuilder.Config(
                3, 12, 96,
                Integer.getInteger("cutting.test.demandPeakBandLimit", 96),
                Integer.getInteger("cutting.test.demandPeakCount", 2),
                Integer.getInteger("cutting.test.demandPeakSeparationSteps", 3),
                config.getStepSize());
        DemandPeakColumnPoolBuilder.Result livePool = new DemandPeakColumnPoolBuilder().build(
                liveShapes, demandByWidth, protectedColumns, poolConfig);

        long peakStarted = System.currentTimeMillis();
        DemandPeakInitialSolutionSolver.InitialSolution result =
                new DemandPeakInitialSolutionSolver().solve(
                        livePool.columns(), demand, protectedColumns, poolConfig,
                        baseline.cars(), baseline.waste(), config.getTotalWidth(),
                        Long.getLong("cutting.test.demandPeakTimeMs", 60_000L));
        long peakElapsedMs = System.currentTimeMillis() - peakStarted;

        assertTrue(result.feasible(), "live-seed demand-peak MIP is infeasible");
        assertEquals(demand, SolverExperimentSnapshot.producedBy(result.solution().uses()));
        assertEquals(baseline.cars(), result.solution().cars());
        assertTrue(result.solution().waste() <= baseline.waste());
        assertTrue(result.solution().groups() <= baseline.groups());
        System.out.printf("DEMAND-PEAK live-seed: baseline=%d/odd%d/small%d cars=%d waste=%d "
                        + "baselineMs=%d captured=%d pool=%d peaks=%s result=%d/odd%d/one%d/small%d "
                        + "mipStatus=%s mipMs=%d totalMs=%d%n",
                baseline.groups(), baseline.oddGroups(), baseline.smallGroups(),
                baseline.cars(), baseline.waste(), baselineElapsedMs, captured.columns().size(),
                result.pool().columns().size(), result.pool().peaks(), result.solution().groups(),
                result.solution().oddBlocks(), result.solution().oneCarBlocks(), result.solution().smallBlocks(),
                result.solution().status(), result.solution().elapsedMs(), baselineElapsedMs + peakElapsedMs);
    }

    private Map<Integer, Map<String, Integer>> demandByWidth(List<SolverOrderItem> items) {
        Map<Integer, Map<String, Integer>> result = new LinkedHashMap<>();
        for (SolverOrderItem item : items) {
            result.computeIfAbsent(item.getWidth(), ignored -> new LinkedHashMap<>())
                    .merge(item.getMessageText() == null ? "" : item.getMessageText(),
                            item.getDemand(), Integer::sum);
        }
        return result;
    }

    private List<SolverOrderItem> loadItems() throws Exception {
        InputStream input = getClass().getResourceAsStream("/sixian.csv");
        if (input == null) {
            throw new IllegalStateException("sixian.csv not found on test classpath");
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
                item.setMessageText(fields[0].trim());
                item.setWidth(Integer.parseInt(fields[1].trim()));
                item.setDemand(Integer.parseInt(fields[2].trim()));
                item.setLength(Integer.parseInt(fields[3].trim()));
                item.setSurfaceTreatment(fields[4].trim());
                item.setGroupKey(fields[3].trim() + "m+" + fields[4].trim());
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
        config.setTotalOverCap(30);
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
