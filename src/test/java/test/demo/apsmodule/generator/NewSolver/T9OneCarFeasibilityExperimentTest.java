package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.SolverExperimentLayeredPoolBuilder.Layer;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.MetricCapResult;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Result;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class T9OneCarFeasibilityExperimentTest {

    private static final int TOTAL_WIDTH = 4600;
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @BeforeAll
    static void loadOrTools() {
        com.google.ortools.Loader.loadNativeLibraries();
    }

    @Test
    void checksZeroOneCarAcrossLayeredPoolsWithDeterministicNodes() throws Exception {
        Path incumbentPath = Path.of(System.getProperty(
                "cutting.test.oneCarIncumbent",
                "solver-experiments/t9est188-candidate-66-8-27-22b48867.csv"));
        Path archivePath = Path.of(System.getProperty(
                "cutting.test.oneCarArchive",
                "solver-experiments/t9est188-columns.csv"));
        Assumptions.assumeTrue(Files.exists(incumbentPath),
                "incumbent snapshot missing: " + incumbentPath);
        Assumptions.assumeTrue(Files.exists(archivePath),
                "column archive missing: " + archivePath);

        Path lockPath = Path.of(System.getProperty(
                "cutting.test.experimentLock", "solver-experiments/solver.lock"));
        try (SolverExperimentGuard ignored = SolverExperimentGuard.acquire(lockPath)) {
            SolverExperimentSnapshot.Snapshot incumbent =
                    SolverExperimentSnapshot.read(incumbentPath);
            Map<String, Integer> demand = SolverExperimentSnapshot.demandOf(loadItems());
            assertEquals(demand, SolverExperimentSnapshot.producedBy(incumbent.uses()));

            List<Column> historical = loadHistoricalCandidateColumns(
                    incumbentPath.getParent(), incumbentPath, demand);
            List<Column> archive =
                    SolverExperimentSnapshot.readColumnArchive(archivePath).columns();
            List<Layer> layers = SolverExperimentLayeredPoolBuilder.build(
                    incumbent.uses(), historical, archive, demand,
                    parseInts("cutting.test.oneCarRelatedCaps", "1200,3000,6000"));

            boolean includeFull = Boolean.getBoolean("cutting.test.oneCarIncludeFull");
            java.util.Set<String> selectedLayers = parseStrings(
                    System.getProperty("cutting.test.oneCarLayers", ""));
            long[] nodeBudgets = parseLongs(
                    "cutting.test.oneCarNodeBudgets", "100,1000,5000");
            long safetyMs = Long.getLong("cutting.test.oneCarSafetyMs", 120_000L);
            int maxGroups = Integer.getInteger("cutting.test.oneCarMaxGroups", 66);
            int maxOdd = Integer.getInteger("cutting.test.oneCarMaxOdd", 8);
            int maxOne = Integer.getInteger("cutting.test.oneCarMaxOne", 0);
            Path report = reportPath();
            writeHeader(report);

            List<ColumnUse> partialHint = incumbent.uses().stream()
                    .filter(use -> use.count() > 1)
                    .toList();
            UnifiedSetPartitionSolver solver = new UnifiedSetPartitionSolver();
            for (Layer layer : layers) {
                if (!selectedLayers.isEmpty() && !selectedLayers.contains(layer.name())) {
                    continue;
                }
                if ("L3-full-archive".equals(layer.name()) && !includeFull) {
                    System.out.printf("ONE-CAR MATRIX skip %s columns=%d (enable full explicitly)%n",
                            layer.name(), layer.columns().size());
                    continue;
                }
                for (long nodeBudget : nodeBudgets) {
                    MetricCapResult outcome = solver.checkCoreMetricCapsByNodes(
                            layer.columns(), demand,
                            incumbent.metrics().cars(), incumbent.metrics().waste(), TOTAL_WIDTH,
                            maxGroups, maxOdd, maxOne, nodeBudget, safetyMs, partialHint);
                    assertNotNull(outcome.result());
                    append(report, layer, nodeBudget, safetyMs,
                            maxGroups, maxOdd, maxOne, outcome);
                    System.out.printf("ONE-CAR MATRIX layer=%s pool=%d hash=%s nodes=%d "
                                    + "status=%s decision=%s actualNodes=%d elapsedMs=%d%n",
                            layer.name(), layer.columns().size(), layer.hash(), nodeBudget,
                            outcome.result().status(), decision(outcome),
                            outcome.result().nodes(), outcome.result().elapsedMs());
                    if (outcome.feasible() || (outcome.proven() && !outcome.feasible())) {
                        if (outcome.feasible()) {
                            Path witness = report.resolveSibling(
                                    report.getFileName().toString().replace(".csv", "-witness.csv"));
                            SolverExperimentSnapshot.write(
                                    witness, "t9-zero-one-witness", outcome.result().uses(), TOTAL_WIDTH);
                        }
                        break;
                    }
                }
            }
            System.out.println("ONE-CAR MATRIX report=" + report.toAbsolutePath());
        }
    }

    private static List<Column> loadHistoricalCandidateColumns(Path directory,
            Path incumbentPath, Map<String, Integer> demand) throws IOException {
        Map<String, Column> columns = new java.util.TreeMap<>();
        if (directory == null || !Files.exists(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("t9est188"))
                    .filter(path -> path.getFileName().toString().endsWith(".csv"))
                    .filter(path -> !path.getFileName().toString().contains("columns"))
                    .filter(path -> !path.equals(incumbentPath))
                    .sorted()
                    .toList()) {
                try {
                    SolverExperimentSnapshot.Snapshot snapshot =
                            SolverExperimentSnapshot.read(path);
                    if (!demand.equals(SolverExperimentSnapshot.producedBy(snapshot.uses()))) {
                        continue;
                    }
                    snapshot.uses().stream().map(ColumnUse::column)
                            .forEach(column -> columns.putIfAbsent(column.signature(), column));
                } catch (IOException ignored) {
                    // Ignore non-snapshot CSV artifacts in the experiment directory.
                }
            }
        }
        return List.copyOf(columns.values());
    }

    private static Path reportPath() {
        String configured = System.getProperty("cutting.test.oneCarReport", "").trim();
        if (!configured.isEmpty()) {
            return Path.of(configured);
        }
        return Path.of("solver-experiments", "t9-one-car-feasibility-"
                + FILE_TIME.format(LocalDateTime.now()) + ".csv");
    }

    private static void writeHeader(Path report) throws IOException {
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report,
                "layer,pool_size,pool_hash,node_limit,safety_ms,max_groups,max_odd,max_one,"
                        + "status,decision,objective,best_bound,gap,actual_nodes,elapsed_ms,"
                        + "result_groups,result_odd,result_one,result_small,result_hash\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void append(Path report, Layer layer, long nodeBudget, long safetyMs,
            int maxGroups, int maxOdd, int maxOne, MetricCapResult outcome) throws IOException {
        Result result = outcome.result();
        String row = String.join(",",
                layer.name(), Integer.toString(layer.columns().size()), layer.hash(),
                Long.toString(nodeBudget), Long.toString(safetyMs),
                Integer.toString(maxGroups), Integer.toString(maxOdd), Integer.toString(maxOne),
                result.status(), decision(outcome), Double.toString(result.objectiveValue()),
                Double.toString(result.bestBound()), Double.toString(result.relativeGap()),
                Long.toString(result.nodes()), Long.toString(result.elapsedMs()),
                Integer.toString(result.groups()), Integer.toString(result.oddBlocks()),
                Integer.toString(result.oneCarBlocks()), Integer.toString(result.smallBlocks()),
                result.uses().isEmpty() ? "" : Integer.toUnsignedString(
                        result.signature().hashCode(), 16)) + "\n";
        Files.writeString(report, row, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String decision(MetricCapResult outcome) {
        if (outcome.feasible()) {
            return "WITNESS";
        }
        if (outcome.proven()) {
            return "PROVEN_INFEASIBLE";
        }
        return "INCONCLUSIVE";
    }

    private static int[] parseInts(String property, String defaults) {
        return java.util.Arrays.stream(System.getProperty(property, defaults).split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .mapToInt(Integer::parseInt).toArray();
    }

    private static long[] parseLongs(String property, String defaults) {
        return java.util.Arrays.stream(System.getProperty(property, defaults).split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .mapToLong(Long::parseLong).toArray();
    }

    private static java.util.Set<String> parseStrings(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(token -> !token.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static List<SolverOrderItem> loadItems() throws Exception {
        InputStream input = T9OneCarFeasibilityExperimentTest.class
                .getResourceAsStream("/t9est188.csv");
        if (input == null) {
            throw new IllegalStateException("fixture not found: /t9est188.csv");
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
        items.sort(Comparator.comparing(SolverOrderItem::getMessageText));
        return items;
    }
}
