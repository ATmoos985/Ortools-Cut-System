package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standalone end-to-end harness for the T9EST188 委托1-27 dataset (116 items, single group).
 *
 * Reproduces the production /api/cutting/v2/optimize run without Spring/DB:
 * reads the order items from a CSV fixture, builds the exact SolverConfig used
 * (flexible 4300-4400, totalWidth=4600 → matches the logged waste 119670), runs
 * the real CuttingSolver, and reports the true sequence-group count.
 *
 * Not an assertion test — it prints the result so B-layer experiments can be
 * measured directly. Run with: mvn test -Dtest=B5HarnessTest
 */
class B5HarnessTest {

    @Test
    void runT9est188() throws Exception {
        List<SolverOrderItem> items = loadItems();
        SolverConfig config = buildConfig();

        long t0 = System.currentTimeMillis();
        CuttingSolver solver = new CuttingSolver();
        List<CuttingInstruction> instructions = solver.solve(items, config);
        long elapsed = System.currentTimeMillis() - t0;

        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);
        List<test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse> uses =
                SolverExperimentSnapshot.fromInstructions(instructions);
        SolverExperimentSnapshot.Metrics metrics =
                SolverExperimentSnapshot.metrics(uses, config.getTotalWidth());
        assertEquals(stats.groups(), metrics.groups());
        assertEquals(stats.oddCarGroups(), metrics.oddGroups());
        assertEquals(stats.smallCarGroups(), metrics.smallGroups());
        assertEquals(SolverExperimentSnapshot.demandOf(items),
                SolverExperimentSnapshot.producedBy(uses));

        System.out.println("\n##### B5 HARNESS RESULT #####");
        System.out.println("items=" + items.size() + " instructions=" + instructions.size());
        System.out.println("groups=" + stats.groups()
                + " oddCarGroups=" + stats.oddCarGroups()
                + " smallCarGroups=" + stats.smallCarGroups());
        System.out.println("cars=" + metrics.cars() + " waste=" + metrics.waste());
        System.out.println("elapsedMs=" + elapsed);
        System.out.println("#############################\n");

        writeSnapshotWhenQualified(uses, metrics, config.getTotalWidth());
    }

    private void writeSnapshotWhenQualified(
            List<test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse> uses,
            SolverExperimentSnapshot.Metrics metrics,
            int totalWidth) throws IOException {
        String output = System.getProperty("cutting.test.snapshotOutput", "").trim();
        if (output.isEmpty()) {
            return;
        }
        Path candidatePath = Path.of("target", "solver-experiments",
                "t9est188-candidate-" + metrics.groups() + "-"
                        + metrics.oddGroups() + "-" + metrics.smallGroups() + ".csv");
        SolverExperimentSnapshot.write(candidatePath, "t9est188-candidate", uses, totalWidth);
        System.out.println("CANDIDATE SNAPSHOT WRITTEN: " + candidatePath.toAbsolutePath());
        int maxGroups = Integer.getInteger("cutting.test.snapshotMaxGroups", 66);
        int maxOdd = Integer.getInteger("cutting.test.snapshotMaxOdd", 8);
        int maxSmall = Integer.getInteger("cutting.test.snapshotMaxSmall", 26);
        assertTrue(metrics.groups() <= maxGroups,
                "snapshot rejected: groups=" + metrics.groups() + " > " + maxGroups);
        assertTrue(metrics.oddGroups() <= maxOdd,
                "snapshot rejected: odd=" + metrics.oddGroups() + " > " + maxOdd);
        assertTrue(metrics.smallGroups() <= maxSmall,
                "snapshot rejected: small=" + metrics.smallGroups() + " > " + maxSmall);
        Path path = Path.of(output);
        SolverExperimentSnapshot.write(path, "t9est188", uses, totalWidth);
        SolverExperimentSnapshot.Snapshot reloaded = SolverExperimentSnapshot.read(path);
        assertEquals(metrics, reloaded.metrics());
        System.out.println("QUALIFIED SNAPSHOT WRITTEN: " + path.toAbsolutePath());
    }

    private List<SolverOrderItem> loadItems() throws IOException {
        List<SolverOrderItem> items = new ArrayList<>();
        InputStream in = getClass().getResourceAsStream("/t9est188.csv");
        if (in == null) {
            throw new IllegalStateException("t9est188.csv not found on test classpath");
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            br.readLine(); // header: message,width,demand,length,surface
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split(",", -1);
                String message = p[0].trim();
                int width = Integer.parseInt(p[1].trim());
                int demand = Integer.parseInt(p[2].trim());
                int length = Integer.parseInt(p[3].trim());
                String surface = p[4].trim();

                SolverOrderItem item = new SolverOrderItem();
                item.setMessageText(message);
                item.setWidth(width);
                item.setDemand(demand);
                item.setLength(length);
                item.setSurfaceTreatment(surface);
                item.setGroupKey(length + "m+" + surface);
                items.add(item);
            }
        }
        return items;
    }

    /** Mirrors SolverConfigFactory flexible defaults + the logged run config. */
    private SolverConfig buildConfig() {
        SolverConfig c = new SolverConfig();
        c.setMode("variable");
        c.setMinWidth(4300);
        c.setMaxWidth(4400);
        c.setStepSize(10);
        c.setTotalWidth(4600);
        c.setTotalOverCap(30);
        c.setMaxIterations(300);
        c.setTimeoutMs(120000L);
        c.setUseNewSolver(true);
        c.setNewSolverTopK(3);
        c.setNewSolverMaxPatterns(800);
        c.setNewSolverMaxDistinctWidths(4);
        c.setNewSolverStage4TimeLimit(30000L);
        c.setNewSolverSeqGroupAlpha(1.0);
        c.setNewSolverSeqGroupBeta(0.0);
        c.setNewSolverUseOptimizedAssignment(true);
        c.setNewSolverUnderPenalty(1e6);
        return c;
    }
}
