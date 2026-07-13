package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakColumnPoolBuilder;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakInitialSolutionSolver;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in timing comparison: current full production route versus the archived
 * demand-peak constructive route. The latter includes archive read, pool build
 * and one MIP, but not live column generation because that adapter is not yet
 * connected to production.
 */
class DemandPeakEndToEndBenchmarkTest {

    private static final int TOTAL_WIDTH = 4600;

    @Test
    void comparesSixianProductionAndDemandPeakReplay() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("cutting.test.demandPeakBenchmark"));
        compare("sixian", "/sixian.csv",
                "solver-experiments/sixian-candidate-42-2-12-81a1bb45.csv",
                "solver-experiments/sixian-columns.csv");
    }

    @Test
    void comparesT9ProductionAndDemandPeakReplay() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("cutting.test.demandPeakBenchmark"));
        compare("t9", "/t9est188.csv",
                "solver-experiments/t9est188-candidate-66-8-26-1114da49.csv",
                "solver-experiments/t9est188-columns.csv");
    }

    private void compare(String dataset, String resource, String snapshotName, String archiveName)
            throws Exception {
        List<SolverOrderItem> items = loadItems(resource);
        SolverConfig config = buildConfig();
        ProductionRun beforeRepair = runProduction(items, config, false);
        ProductionRun afterRepair = runProduction(items, config, true);
        PeakRun peak = runPeakReplay(snapshotName, archiveName);
        assertConserved(items, beforeRepair);
        assertConserved(items, afterRepair);
        assertPeakValid(peak);
        print(dataset, beforeRepair, afterRepair, peak);
    }

    private ProductionRun runProduction(List<SolverOrderItem> items, SolverConfig config,
                                        boolean lnsEnabled) {
        Map<String, String> properties = Map.of(
                "cutting.lns.enabled", Boolean.toString(lnsEnabled),
                "cutting.quality", "false");
        long startedAt = System.nanoTime();
        List<CuttingInstruction> instructions = withProperties(properties,
                () -> new CuttingSolver().solve(items, config));
        long elapsedMs = elapsedMs(startedAt);
        List<UnifiedSetPartitionSolver.ColumnUse> uses =
                SolverExperimentSnapshot.fromInstructions(instructions);
        SolverExperimentSnapshot.Metrics metrics =
                SolverExperimentSnapshot.metrics(uses, config.getTotalWidth());
        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);
        return new ProductionRun(lnsEnabled, elapsedMs, metrics, uses, stats);
    }

    private PeakRun runPeakReplay(String snapshotName, String archiveName) throws Exception {
        long startedAt = System.nanoTime();
        SolverExperimentSnapshot.Snapshot snapshot =
                SolverExperimentSnapshot.read(Path.of(snapshotName));
        Map<String, Integer> demand = SolverExperimentSnapshot.producedBy(snapshot.uses());
        List<UnifiedSetPartitionSolver.Column> protectedColumns = snapshot.uses().stream()
                .map(UnifiedSetPartitionSolver.ColumnUse::column).toList();
        Map<String, UnifiedSetPartitionSolver.Column> columns = new LinkedHashMap<>();
        SolverExperimentSnapshot.readColumnArchive(Path.of(archiveName)).columns()
                .forEach(column -> columns.putIfAbsent(column.signature(), column));
        protectedColumns.forEach(column -> columns.putIfAbsent(column.signature(), column));
        DemandPeakColumnPoolBuilder.Config poolConfig = new DemandPeakColumnPoolBuilder.Config(
                3, 12, 96,
                Integer.getInteger("cutting.test.demandPeakBandLimit", 48), 2, 3, 10);
        DemandPeakInitialSolutionSolver.InitialSolution solution =
                new DemandPeakInitialSolutionSolver().solve(columns.values(), demand, protectedColumns,
                        poolConfig, snapshot.metrics().cars(), snapshot.metrics().waste(), TOTAL_WIDTH,
                        Long.getLong("cutting.test.demandPeakTimeMs", 60_000L));
        return new PeakRun(elapsedMs(startedAt), demand, snapshot.metrics(), solution);
    }

    private void assertConserved(List<SolverOrderItem> items, ProductionRun run) {
        assertEquals(SolverExperimentSnapshot.demandOf(items),
                SolverExperimentSnapshot.producedBy(run.uses()));
    }

    private void assertPeakValid(PeakRun run) {
        assertTrue(run.solution().feasible());
        assertEquals(run.demand(), SolverExperimentSnapshot.producedBy(run.solution().solution().uses()));
        assertEquals(run.baseline().cars(), run.solution().solution().cars());
        assertTrue(run.solution().solution().waste() <= run.baseline().waste());
    }

    private void print(String dataset, ProductionRun beforeRepair, ProductionRun afterRepair,
                       PeakRun peak) {
        System.out.printf("DEMAND-PEAK end-to-end: dataset=%s production-pre=%dms %d/%d/one%d/%d, "
                        + "production-lns=%dms %d/%d/one%d/%d, lnsIncrement=%dms, "
                        + "peak-replay=%dms %d/%d/one%d/%d pool=%d peaks=%s%n",
                dataset, beforeRepair.elapsedMs(), beforeRepair.metrics().groups(), beforeRepair.metrics().oddGroups(),
                beforeRepair.uses().stream().filter(use -> use.count() == 1).count(),
                beforeRepair.metrics().smallGroups(), afterRepair.elapsedMs(),
                afterRepair.metrics().groups(), afterRepair.metrics().oddGroups(),
                afterRepair.uses().stream().filter(use -> use.count() == 1).count(),
                afterRepair.metrics().smallGroups(), afterRepair.elapsedMs() - beforeRepair.elapsedMs(),
                peak.elapsedMs(), peak.solution().solution().groups(),
                peak.solution().solution().oddBlocks(), peak.solution().solution().oneCarBlocks(),
                peak.solution().solution().smallBlocks(), peak.solution().pool().columns().size(),
                peak.solution().pool().peaks());
    }

    private static <T> T withProperties(Map<String, String> values, Supplier<T> action) {
        Map<String, String> previous = new LinkedHashMap<>();
        values.keySet().forEach(key -> previous.put(key, System.getProperty(key)));
        values.forEach(System::setProperty);
        try {
            return action.get();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static List<SolverOrderItem> loadItems(String resource) throws Exception {
        InputStream input = DemandPeakEndToEndBenchmarkTest.class.getResourceAsStream(resource);
        if (input == null) throw new IllegalStateException("fixture not found: " + resource);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.readLine();
            java.util.ArrayList<SolverOrderItem> items = new java.util.ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
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
            return items;
        }
    }

    private static SolverConfig buildConfig() {
        SolverConfig config = new SolverConfig();
        config.setMode("variable");
        config.setMinWidth(4300);
        config.setMaxWidth(4400);
        config.setStepSize(10);
        config.setTotalWidth(TOTAL_WIDTH);
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

    private record ProductionRun(boolean lnsEnabled, long elapsedMs,
                                 SolverExperimentSnapshot.Metrics metrics,
                                 List<UnifiedSetPartitionSolver.ColumnUse> uses,
                                 SequenceGroupPostProcessor.GroupStats stats) {
    }

    private record PeakRun(long elapsedMs, Map<String, Integer> demand,
                           SolverExperimentSnapshot.Metrics baseline,
                           DemandPeakInitialSolutionSolver.InitialSolution solution) {
    }
}
