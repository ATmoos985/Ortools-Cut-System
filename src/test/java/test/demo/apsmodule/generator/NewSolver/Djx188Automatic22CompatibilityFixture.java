package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPSolver;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Solver-free, persisted compatibility witnesses for the fixed automatic22 state. */
final class Djx188Automatic22CompatibilityFixture {

    static final String FORMAT =
            "djx188-automatic22-compatibility-witness-v1";
    static final String EXPECTED_PAYLOAD_SHA256 =
            "0cae7698c94cef6105b3ea2836dcb4421d231c62d05de893e7b5820da13d7d06";
    static final Path PATH = Path.of(
            "src", "test", "resources", "research-baselines",
            "djx188-automatic22-compatibility-witness-v1.tsv");
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Djx188Automatic22CompatibilityFixture() {
    }

    static OrderCompatibilityKernelAnalyzer.Analysis load(
            Map<PatternCandidate, Integer> baseline) throws IOException {
        Objects.requireNonNull(baseline, "baseline");
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> payload = new ArrayList<>();
        for (String line : Files.readAllLines(PATH, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("# ")) {
                int equals = line.indexOf('=');
                if (equals <= 2 || headers.putIfAbsent(
                        line.substring(2, equals),
                        line.substring(equals + 1)) != null) {
                    throw new IOException("Malformed or duplicate witness header");
                }
            } else if (line.startsWith("SPLIT\t")
                    || line.startsWith("CONFIG\t")) {
                payload.add(line);
            } else {
                throw new IOException("Unknown witness fixture line: " + line);
            }
        }
        if (!FORMAT.equals(required(headers, "format"))) {
            throw new IOException("Unsupported witness fixture format");
        }
        String baselineSha256 = SupportCandidateSnapshotArchive.sha256OfLines(
                List.of(SupportCandidateSnapshotArchive.stateSignature(baseline)));
        if (!baselineSha256.equals(required(headers, "baselineSha256"))) {
            throw new IOException("Witness fixture belongs to another baseline");
        }
        String payloadSha256 = SupportCandidateSnapshotArchive.sha256OfLines(
                payload);
        if (!payloadSha256.equals(required(headers, "payloadSha256"))
                || !EXPECTED_PAYLOAD_SHA256.equals(payloadSha256)) {
            throw new IOException("Witness fixture payload hash mismatch");
        }

        Map<String, MutableSplit> splits = new TreeMap<>();
        for (String line : payload) {
            String[] fields = line.split("\\t", -1);
            if ("SPLIT".equals(fields[0])) {
                if (fields.length != 5) {
                    throw new IOException("Malformed SPLIT witness line");
                }
                String pattern = decode(fields[1]);
                MutableSplit previous = splits.putIfAbsent(pattern,
                        new MutableSplit(
                                pattern,
                                integer(fields[2], "pattern usage"),
                                widths(decode(fields[3])),
                                config(decode(fields[4]))));
                if (previous != null) {
                    throw new IOException("Duplicate split witness: " + pattern);
                }
            } else if ("CONFIG".equals(fields[0])) {
                if (fields.length != 5) {
                    throw new IOException("Malformed CONFIG witness line");
                }
                String pattern = decode(fields[1]);
                MutableSplit split = splits.get(pattern);
                if (split == null) {
                    throw new IOException("Configuration precedes split: " + pattern);
                }
                split.configurations.add(new OrderCompatibilityKernelAnalyzer.ConfigUse(
                        integer(fields[2], "configuration cars"),
                        config(decode(fields[4])),
                        decode(fields[3])));
            } else {
                throw new IOException("Unknown witness payload type");
            }
        }

        List<OrderCompatibilityKernelAnalyzer.PatternSplit> witnesses =
                splits.values().stream().map(MutableSplit::freeze).toList();
        if (witnesses.size() != integer(
                required(headers, "splitCount"), "split count")) {
            throw new IOException("Witness split count mismatch");
        }
        Map<String, Integer> usageByPattern = new TreeMap<>();
        baseline.forEach((pattern, usage) -> usageByPattern.put(
                pattern.signature(), usage));
        for (OrderCompatibilityKernelAnalyzer.PatternSplit split : witnesses) {
            if (!Objects.equals(
                    usageByPattern.get(split.patternSignature()),
                    split.patternUsage())) {
                throw new IOException("Witness usage differs from baseline: "
                        + split.patternSignature());
            }
            int configuredCars = split.configurations().stream()
                    .mapToInt(OrderCompatibilityKernelAnalyzer.ConfigUse::cars)
                    .sum();
            if (configuredCars != split.patternUsage()) {
                throw new IOException("Witness configurations do not sum to usage: "
                        + split.patternSignature());
            }
        }
        return analysis(witnesses, baseline.size());
    }

    static String payloadSha256(
            OrderCompatibilityKernelAnalyzer.Analysis analysis) {
        return SupportCandidateSnapshotArchive.sha256OfLines(
                canonicalLines(analysis));
    }

    static List<String> canonicalLines(
            OrderCompatibilityKernelAnalyzer.Analysis analysis) {
        List<String> lines = new ArrayList<>();
        analysis.splitWitnesses().stream()
                .sorted(java.util.Comparator.comparing(
                        OrderCompatibilityKernelAnalyzer.PatternSplit
                                ::patternSignature))
                .forEach(split -> {
                    lines.add(String.join("\t",
                            "SPLIT",
                            encode(split.patternSignature()),
                            Integer.toString(split.patternUsage()),
                            encode(split.varyingWidths().stream().sorted()
                                    .map(String::valueOf)
                                    .reduce((left, right) -> left + "," + right)
                                    .orElse("")),
                            encode(configString(split.varyingMessages()))));
                    split.configurations().stream()
                            .sorted(java.util.Comparator
                                    .comparingInt(
                                            OrderCompatibilityKernelAnalyzer
                                                    .ConfigUse::cars)
                                    .reversed()
                                    .thenComparing(
                                            OrderCompatibilityKernelAnalyzer
                                                    .ConfigUse
                                                    ::configurationSignature))
                            .forEach(use -> lines.add(String.join("\t",
                                    "CONFIG",
                                    encode(split.patternSignature()),
                                    Integer.toString(use.cars()),
                                    encode(use.configurationSignature()),
                                    encode(configString(use.stationConfig())))));
                });
        return List.copyOf(lines);
    }

    private static OrderCompatibilityKernelAnalyzer.Analysis analysis(
            List<OrderCompatibilityKernelAnalyzer.PatternSplit> witnesses,
            int patternCount) {
        return new OrderCompatibilityKernelAnalyzer.Analysis(
                OrderCompatibilityKernelAnalyzer.Status.OPTIMAL,
                "FIXTURE",
                MPSolver.ResultStatus.OPTIMAL,
                MPSolver.ResultStatus.OPTIMAL,
                MPSolver.ResultStatus.OPTIMAL,
                true,
                true,
                patternCount,
                28,
                28,
                28,
                6,
                1,
                0,
                0L,
                0,
                0,
                0L,
                28.0,
                0.0,
                0L,
                0L,
                0L,
                0L,
                witnesses);
    }

    private static Set<Integer> widths(String value) throws IOException {
        Set<Integer> result = new TreeSet<>();
        if (value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            result.add(integer(part, "width"));
        }
        return Set.copyOf(result);
    }

    private static Map<Integer, List<String>> config(String value)
            throws IOException {
        Map<Integer, List<String>> result = new TreeMap<>();
        if (value.isBlank()) {
            return result;
        }
        for (String entry : value.split(";", -1)) {
            String[] fields = entry.split("=", -1);
            if (fields.length != 2) {
                throw new IOException("Malformed station configuration");
            }
            List<String> messages = new ArrayList<>();
            if (!fields[1].isBlank()) {
                for (String encoded : fields[1].split(",")) {
                    messages.add(decode(encoded));
                }
            }
            result.put(integer(fields[0], "configuration width"),
                    List.copyOf(messages));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String configString(Map<Integer, List<String>> source) {
        return new TreeMap<>(source).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().stream()
                        .sorted()
                        .map(Djx188Automatic22CompatibilityFixture::encode)
                        .reduce((left, right) -> left + "," + right)
                        .orElse(""))
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private static String required(Map<String, String> values, String key)
            throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing witness header: " + key);
        }
        return value;
    }

    private static int integer(String value, String name) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid " + name, exception);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) throws IOException {
        try {
            return new String(DECODER.decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid witness Base64", exception);
        }
    }

    private static final class MutableSplit {
        private final String patternSignature;
        private final int patternUsage;
        private final Set<Integer> varyingWidths;
        private final Map<Integer, List<String>> varyingMessages;
        private final List<OrderCompatibilityKernelAnalyzer.ConfigUse>
                configurations = new ArrayList<>();

        private MutableSplit(
                String patternSignature,
                int patternUsage,
                Set<Integer> varyingWidths,
                Map<Integer, List<String>> varyingMessages) {
            this.patternSignature = patternSignature;
            this.patternUsage = patternUsage;
            this.varyingWidths = varyingWidths;
            this.varyingMessages = varyingMessages;
        }

        private OrderCompatibilityKernelAnalyzer.PatternSplit freeze() {
            return new OrderCompatibilityKernelAnalyzer.PatternSplit(
                    patternSignature,
                    patternUsage,
                    configurations,
                    varyingWidths,
                    varyingMessages);
        }
    }
}
