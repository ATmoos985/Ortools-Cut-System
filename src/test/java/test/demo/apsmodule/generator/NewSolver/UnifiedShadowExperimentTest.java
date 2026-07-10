package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedShadowExperimentTest {

    private static final int TOTAL_WIDTH = 4600;

    @BeforeAll
    static void loadOrTools() {
        Loader.loadNativeLibraries();
    }

    @Test
    void rebuildsFrozenT9SnapshotAndOptionallyChallengesLowerGroupCap() throws Exception {
        Path snapshotPath = Path.of(System.getProperty(
                "cutting.test.shadowSnapshot",
                "src/test/resources/t9est188_quality_snapshot.csv"));
        Assumptions.assumeTrue(Files.exists(snapshotPath),
                "qualified t9 snapshot has not been captured yet: " + snapshotPath);

        SolverExperimentSnapshot.Snapshot snapshot =
                SolverExperimentSnapshot.read(snapshotPath);
        List<SolverOrderItem> items = loadItems("/t9est188.csv");
        assertEquals(SolverExperimentSnapshot.demandOf(items),
                SolverExperimentSnapshot.producedBy(snapshot.uses()));

        SolverExperimentSnapshot.Metrics metrics = snapshot.metrics();
        UnifiedSetPartitionSolver solver = new UnifiedSetPartitionSolver();
        UnifiedSetPartitionSolver.GroupCapResult rebuild = solver.checkGroupCap(
                snapshot.uses().stream().map(UnifiedSetPartitionSolver.ColumnUse::column).toList(),
                SolverExperimentSnapshot.demandOf(items),
                metrics.cars(), metrics.waste(), TOTAL_WIDTH,
                metrics.groups(),
                Long.getLong("cutting.test.shadowTimeMs", 30_000L));

        assertTrue(rebuild.proven());
        assertTrue(rebuild.feasible());
        assertNotNull(rebuild.result());
        assertEquals(metrics.cars(), rebuild.result().cars());
        assertTrue(rebuild.result().waste() <= metrics.waste());

        Integer challengeCap = Integer.getInteger("cutting.test.shadowChallengeCap");
        if (challengeCap != null) {
            UnifiedSetPartitionSolver.GroupCapResult challenge = solver.checkGroupCap(
                    snapshot.uses().stream()
                            .map(UnifiedSetPartitionSolver.ColumnUse::column).toList(),
                    SolverExperimentSnapshot.demandOf(items),
                    metrics.cars(), metrics.waste(), TOTAL_WIDTH,
                    challengeCap,
                    Long.getLong("cutting.test.shadowTimeMs", 30_000L));
            assertNotNull(challenge.result());
            System.out.printf("SHADOW challenge: snapshot=%s cap=%d feasible=%s proven=%s "
                            + "status=%s nodes=%d elapsedMs=%d%n",
                    snapshotPath, challengeCap, challenge.feasible(), challenge.proven(),
                    challenge.result().status(), challenge.result().nodes(),
                    challenge.result().elapsedMs());
        }

        System.out.printf("SHADOW rebuild: snapshot=%s baseline=%d/%d/%d cars=%d waste=%d "
                        + "result=%d/%d/%d status=%s elapsedMs=%d%n",
                snapshotPath, metrics.groups(), metrics.oddGroups(), metrics.smallGroups(),
                metrics.cars(), metrics.waste(), rebuild.result().groups(),
                rebuild.result().oddBlocks(), rebuild.result().smallBlocks(),
                rebuild.result().status(), rebuild.result().elapsedMs());
    }

    private List<SolverOrderItem> loadItems(String resource) throws Exception {
        InputStream input = getClass().getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("fixture not found: " + resource);
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
}
