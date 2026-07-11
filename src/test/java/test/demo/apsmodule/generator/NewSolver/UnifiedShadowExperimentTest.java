package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver;
import test.demo.apsmodule.generator.NewSolver.output.SetPartitionRefiner;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Path lockPath = Path.of(System.getProperty(
                "cutting.test.experimentLock", "solver-experiments/solver.lock"));
        try (SolverExperimentGuard ignored = SolverExperimentGuard.acquire(lockPath)) {
        Path snapshotPath = Path.of(System.getProperty(
                "cutting.test.shadowSnapshot",
                "src/test/resources/t9est188_quality_snapshot.csv"));
        Assumptions.assumeTrue(Files.exists(snapshotPath),
                "qualified t9 snapshot has not been captured yet: " + snapshotPath);

        SolverExperimentSnapshot.Snapshot snapshot =
                SolverExperimentSnapshot.read(snapshotPath);
        String ordersResource = System.getProperty(
                "cutting.test.shadowOrders", "/t9est188.csv");
        List<SolverOrderItem> items = loadItems(ordersResource);
        Map<String, Integer> demand = SolverExperimentSnapshot.demandOf(items);
        assertEquals(demand,
                SolverExperimentSnapshot.producedBy(snapshot.uses()));

        SolverExperimentSnapshot.Metrics metrics = snapshot.metrics();
        Map<String, UnifiedSetPartitionSolver.Column> poolBySignature = new LinkedHashMap<>();
        List<UnifiedSetPartitionSolver.Column> requiredColumns = new ArrayList<>();
        snapshot.uses().stream().map(UnifiedSetPartitionSolver.ColumnUse::column)
                .forEach(requiredColumns::add);
        requiredColumns
                .forEach(column -> poolBySignature.putIfAbsent(column.signature(), column));
        String seedSnapshots = System.getProperty("cutting.test.shadowSeedSnapshots", "");
        for (String seedName : seedSnapshots.split(",")) {
            if (seedName.isBlank()) {
                continue;
            }
            Path seedPath = Path.of(seedName.trim());
            assertTrue(Files.exists(seedPath), "seed snapshot does not exist: " + seedPath);
            SolverExperimentSnapshot.Snapshot seed = SolverExperimentSnapshot.read(seedPath);
            assertEquals(demand, SolverExperimentSnapshot.producedBy(seed.uses()),
                    "seed snapshot demand mismatch: " + seedPath);
            seed.uses().stream().map(UnifiedSetPartitionSolver.ColumnUse::column)
                    .forEach(column -> {
                        requiredColumns.add(column);
                        poolBySignature.putIfAbsent(column.signature(), column);
                    });
        }
        String archiveProperty = System.getProperty("cutting.test.shadowArchives",
                "solver-experiments/t9est188-columns.csv");
        for (String archiveName : archiveProperty.split(",")) {
            Path archivePath = Path.of(archiveName.trim());
            if (!archiveName.isBlank() && Files.exists(archivePath)) {
                SolverExperimentSnapshot.readColumnArchive(archivePath).columns()
                        .forEach(column -> poolBySignature.putIfAbsent(column.signature(), column));
            }
        }
        List<UnifiedSetPartitionSolver.Column> fullPool = List.copyOf(poolBySignature.values());
        int maxColumns = Integer.getInteger("cutting.test.shadowMaxColumns", 0);
        List<UnifiedSetPartitionSolver.Column> pool;
        if (Boolean.getBoolean("cutting.test.shadowUseOneCarSelector")) {
            java.util.Set<String> oneCarDemandKeys = snapshot.uses().stream()
                    .filter(use -> use.count() == 1)
                    .flatMap(use -> use.column().demandUse().keySet().stream())
                    .collect(java.util.stream.Collectors.toSet());
            pool = SetPartitionRefiner.selectPolishColumns(
                    fullPool, requiredColumns, demand, oneCarDemandKeys, maxColumns,
                    Integer.getInteger("cutting.test.shadowColumnsPerDemandKey", 4),
                    Integer.getInteger("cutting.test.shadowTargetColumnsPerDemandKey", 64));
        } else {
            pool = SolverExperimentColumnSelector.select(
                    fullPool,
                    requiredColumns,
                    demand,
                    maxColumns,
                    Integer.getInteger("cutting.test.shadowColumnsPerDemandKey", 10));
        }
        System.out.printf("SHADOW pool selection: input=%d selected=%d required=%d max=%d%n",
                fullPool.size(), pool.size(), requiredColumns.size(), maxColumns);
        UnifiedSetPartitionSolver solver = new UnifiedSetPartitionSolver();
        UnifiedSetPartitionSolver.GroupCapResult rebuild = solver.checkGroupCap(
                pool,
                demand,
                metrics.cars(), metrics.waste(), TOTAL_WIDTH,
                metrics.groups(),
                Long.getLong("cutting.test.shadowTimeMs", 30_000L),
                snapshot.uses());

        assertTrue(rebuild.proven());
        assertTrue(rebuild.feasible());
        assertNotNull(rebuild.result());
        assertEquals(metrics.cars(), rebuild.result().cars());
        assertTrue(rebuild.result().waste() <= metrics.waste());

        int challengeCap = Integer.getInteger(
                "cutting.test.shadowChallengeCap", metrics.groups() - 1);
        UnifiedSetPartitionSolver.GroupCapResult challenge = null;
        if (challengeCap >= 0) {
            challenge = solver.checkGroupCap(
                    pool,
                    demand,
                    metrics.cars(), metrics.waste(), TOTAL_WIDTH,
                    challengeCap,
                    Long.getLong("cutting.test.shadowTimeMs", 30_000L),
                    snapshot.uses());
            assertNotNull(challenge.result());
            System.out.printf("SHADOW challenge: snapshot=%s cap=%d feasible=%s proven=%s "
                            + "status=%s nodes=%d elapsedMs=%d%n",
                    snapshotPath, challengeCap, challenge.feasible(), challenge.proven(),
                    challenge.result().status(), challenge.result().nodes(),
                    challenge.result().elapsedMs());
        }

        if (Boolean.parseBoolean(System.getProperty(
                "cutting.test.shadowLexicographic", "true"))) {
            List<UnifiedSetPartitionSolver.ColumnUse> warmStart =
                    challenge != null && challenge.feasible()
                            ? challenge.result().uses()
                            : snapshot.uses();
            UnifiedSetPartitionSolver.LexicographicResult lexicographic =
                    solver.solveLexicographic(
                            pool,
                            demand,
                            metrics.cars(), metrics.waste(), TOTAL_WIDTH,
                            Long.getLong("cutting.test.shadowPhaseTimeMs", 30_000L),
                            warmStart);
            assertNotNull(lexicographic.result());
            if (!lexicographic.result().uses().isEmpty()) {
                assertEquals(metrics.cars(), lexicographic.result().cars());
                assertTrue(lexicographic.result().waste() <= metrics.waste());
            }
            System.out.printf("SHADOW lexical: pool=%d result=%d/%d/%d status=%s "
                            + "provenOptimal=%s elapsedMs=%d%n",
                    pool.size(), lexicographic.result().groups(),
                    lexicographic.result().oddBlocks(), lexicographic.result().smallBlocks(),
                    lexicographic.result().status(), lexicographic.provenOptimal(),
                    lexicographic.phases().stream()
                            .mapToLong(UnifiedSetPartitionSolver.Result::elapsedMs).sum());
        }

        if (Boolean.parseBoolean(System.getProperty(
                "cutting.test.shadowSmallPolish", "false"))) {
            UnifiedSetPartitionSolver.Result smallPolish = solver.minimizeSmallAtCaps(
                    pool,
                    demand,
                    metrics.cars(), metrics.waste(), TOTAL_WIDTH,
                    Integer.getInteger("cutting.test.shadowSmallMaxGroups", metrics.groups()),
                    Integer.getInteger("cutting.test.shadowSmallMaxOdd", metrics.oddGroups()),
                    Long.getLong("cutting.test.shadowSmallTimeMs", 60_000L),
                    snapshot.uses());
            assertNotNull(smallPolish);
            if (!smallPolish.uses().isEmpty()) {
                assertEquals(demand,
                        SolverExperimentSnapshot.producedBy(smallPolish.uses()));
                assertEquals(metrics.cars(), smallPolish.cars());
                assertTrue(smallPolish.waste() <= metrics.waste());
                SolverExperimentSnapshot.Metrics polishedMetrics =
                        SolverExperimentSnapshot.metrics(smallPolish.uses(), TOTAL_WIDTH);
                Path output = Path.of(System.getProperty(
                        "cutting.test.shadowSmallOutput",
                        "solver-experiments/shadow-small-" + polishedMetrics.groups() + "-"
                                + polishedMetrics.oddGroups() + "-"
                                + polishedMetrics.smallGroups() + ".csv"));
                SolverExperimentSnapshot.write(output,
                        snapshot.dataset() + "-small-polish", smallPolish.uses(), TOTAL_WIDTH);
                System.out.printf("SHADOW small polish: pool=%d result=%d/%d/%d status=%s "
                                + "nodes=%d elapsedMs=%d output=%s%n",
                        pool.size(), polishedMetrics.groups(), polishedMetrics.oddGroups(),
                        polishedMetrics.smallGroups(), smallPolish.status(), smallPolish.nodes(),
                        smallPolish.elapsedMs(), output);
            } else {
                System.out.printf("SHADOW small polish: pool=%d status=%s nodes=%d elapsedMs=%d%n",
                        pool.size(), smallPolish.status(), smallPolish.nodes(),
                        smallPolish.elapsedMs());
            }
        }

        int feasibilityMaxSmall = Integer.getInteger(
                "cutting.test.shadowFeasibilityMaxSmall", -1);
        if (feasibilityMaxSmall >= 0) {
            int feasibilityMaxGroups = Integer.getInteger(
                    "cutting.test.shadowFeasibilityMaxGroups", metrics.groups());
            int feasibilityMaxOdd = Integer.getInteger(
                    "cutting.test.shadowFeasibilityMaxOdd", metrics.oddGroups());
            int feasibilityMaxOne = Integer.getInteger(
                    "cutting.test.shadowFeasibilityMaxOne",
                    (int) snapshot.uses().stream().filter(use -> use.count() == 1).count());
            UnifiedSetPartitionSolver.MetricCapResult feasibility = solver.checkMetricCaps(
                    pool, demand, metrics.cars(), metrics.waste(), TOTAL_WIDTH,
                    feasibilityMaxGroups, feasibilityMaxOdd, feasibilityMaxOne,
                    feasibilityMaxSmall,
                    Long.getLong("cutting.test.shadowFeasibilityTimeMs", 60_000L),
                    snapshot.uses());
            assertNotNull(feasibility.result());
            if (feasibility.feasible()) {
                assertEquals(demand,
                        SolverExperimentSnapshot.producedBy(feasibility.result().uses()));
                SolverExperimentSnapshot.Metrics feasibleMetrics =
                        SolverExperimentSnapshot.metrics(feasibility.result().uses(), TOTAL_WIDTH);
                Path output = Path.of(System.getProperty(
                        "cutting.test.shadowFeasibilityOutput",
                        "solver-experiments/shadow-feasible-" + feasibleMetrics.groups() + "-"
                                + feasibleMetrics.oddGroups() + "-"
                                + feasibleMetrics.smallGroups() + ".csv"));
                SolverExperimentSnapshot.write(output,
                        snapshot.dataset() + "-metric-caps",
                        feasibility.result().uses(), TOTAL_WIDTH);
            }
            System.out.printf("SHADOW metric caps: pool=%d caps=%d/%d/%d feasible=%s "
                            + "proven=%s status=%s nodes=%d elapsedMs=%d%n",
                    pool.size(), feasibilityMaxGroups, feasibilityMaxOdd,
                    feasibilityMaxSmall, feasibility.feasible(), feasibility.proven(),
                    feasibility.result().status(), feasibility.result().nodes(),
                    feasibility.result().elapsedMs());
        }

        if (Boolean.getBoolean("cutting.test.shadowOneCarPolish")) {
            UnifiedSetPartitionSolver.Result oneCarPolish = solver.minimizeOneCarAtCaps(
                    pool, demand, metrics.cars(), metrics.waste(), TOTAL_WIDTH,
                    Integer.getInteger("cutting.test.shadowOneCarMaxGroups", metrics.groups()),
                    Integer.getInteger("cutting.test.shadowOneCarMaxOdd", metrics.oddGroups()),
                    Long.getLong("cutting.test.shadowOneCarTimeMs", 60_000L),
                    snapshot.uses());
            assertNotNull(oneCarPolish);
            System.out.printf("SHADOW one-car polish: pool=%d result=%d/%d/one%d/%d "
                            + "status=%s nodes=%d elapsedMs=%d%n",
                    pool.size(), oneCarPolish.groups(), oneCarPolish.oddBlocks(),
                    oneCarPolish.oneCarBlocks(), oneCarPolish.smallBlocks(),
                    oneCarPolish.status(), oneCarPolish.nodes(), oneCarPolish.elapsedMs());
        }

        System.out.printf("SHADOW rebuild: snapshot=%s baseline=%d/%d/%d cars=%d waste=%d "
                        + "result=%d/%d/%d status=%s elapsedMs=%d%n",
                snapshotPath + " pool=" + pool.size(), metrics.groups(), metrics.oddGroups(), metrics.smallGroups(),
                metrics.cars(), metrics.waste(), rebuild.result().groups(),
                rebuild.result().oddBlocks(), rebuild.result().smallBlocks(),
                rebuild.result().status(), rebuild.result().elapsedMs());
        }
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
