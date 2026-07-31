package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Versioned, autonomous DJX188 25-group research baseline.
 *
 * <p>This fixture loads only the production-like DJX188 input, the complete
 * pattern universe and the frozen autonomous incumbent. It intentionally does
 * not load either the manual 26-group plan or the automatic22 witness.</p>
 */
final class Djx188Target24Fixture {

    private static final String BASE =
            "/research-baselines/djx188-target24-incumbent-v1";

    private Djx188Target24Fixture() {
    }

    static Baseline load() throws IOException {
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe =
                new CompletePatternEnumerator(params, 5)
                        .generate(aggregateWidthDemand(items));
        Input input = new Input(items, universe, params, 169, 36_870, 1, 0);

        List<String> signatures = readLines(BASE + ".txt");
        List<GroupColumn> columns = signatures.stream()
                .map(signature ->
                        OrderGroupResidualBundlePricer.parseColumn(
                                input, signature))
                .toList();

        Properties properties = new Properties();
        try (InputStream stream = resource(BASE + ".properties")) {
            properties.load(stream);
        }
        return new Baseline(input, columns, signatures, properties);
    }

    static String resourceSha256(String resourceName) throws IOException {
        try (InputStream stream = resource(resourceName)) {
            return sha256(stream.readAllBytes());
        }
    }

    static String normalizedSignatureSha256(List<String> signatures) {
        String normalized = signatures.stream()
                .filter(line -> !line.isBlank())
                .map(String::trim)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        if (!normalized.isEmpty()) {
            normalized += "\n";
        }
        return sha256(normalized.getBytes(StandardCharsets.UTF_8));
    }

    static Map<DemandKey, Integer> aggregateCoverage(
            List<GroupColumn> columns) {
        Map<DemandKey, Integer> coverage = new TreeMap<>();
        columns.forEach(column -> column.coverage().forEach(
                (key, value) -> coverage.merge(
                        key, value, Math::addExact)));
        return coverage;
    }

    private static List<String> readLines(String resourceName)
            throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        resource(resourceName), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
        }
        return List.copyOf(lines);
    }

    private static Map<Integer, Integer> aggregateWidthDemand(
            List<SolverOrderItem> items) {
        Map<Integer, Integer> demand = new TreeMap<>();
        for (SolverOrderItem item : items) {
            demand.merge(
                    item.getWidth(), item.getDemand(), Math::addExact);
        }
        return demand;
    }

    private static InputStream resource(String name) {
        InputStream stream =
                Djx188Target24Fixture.class.getResourceAsStream(name);
        if (stream == null) {
            throw new IllegalStateException(
                    name + " not found on test classpath");
        }
        return stream;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    record Baseline(
            Input input,
            List<GroupColumn> columns,
            List<String> signatures,
            Properties metadata) {

        Baseline {
            columns = List.copyOf(columns);
            signatures = List.copyOf(signatures);
        }
    }
}
