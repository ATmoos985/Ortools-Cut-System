package test.demo.apsmodule.generator.NewSolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Top-N support ranking and resumable exact evidence for a fixed snapshot. */
final class SupportTopNExactEvaluator {

    static final String EXACT_RESULTS_FORMAT =
            "support-candidate-exact-results-v1";
    private static final String RESULT_PREFIX = "RESULT\t";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder()
            .withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private SupportTopNExactEvaluator() {
    }

    static TopNPlan plan(
            SupportCandidateSnapshotArchive.Snapshot snapshot,
            int maxDepth) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive");
        }
        Map<String, List<SupportCandidateSnapshotArchive.PersistedCandidate>>
                grouped = new TreeMap<>();
        snapshot.candidates().forEach(candidate -> grouped
                .computeIfAbsent(candidate.state().supportIdentity(),
                        ignored -> new ArrayList<>())
                .add(candidate));

        Map<String, List<RankedCandidate>> buckets = new TreeMap<>();
        Map<String, RankedCandidate> byState = new TreeMap<>();
        for (Map.Entry<String,
                List<SupportCandidateSnapshotArchive.PersistedCandidate>> entry
                : grouped.entrySet()) {
            List<SupportCandidateSnapshotArchive.PersistedCandidate> ordered =
                    entry.getValue().stream()
                            .sorted((left, right) ->
                                    SupportBucketedCandidateEvaluator
                                            .scoreOrder().compare(
                                            left.scored(), right.scored()))
                            .toList();
            List<RankedCandidate> ranked = new ArrayList<>();
            for (int index = 0; index < ordered.size(); index++) {
                RankedCandidate value = new RankedCandidate(
                        ordered.get(index), index + 1);
                ranked.add(value);
                if (byState.put(value.stateSignature(), value) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate state in Top-N plan: "
                                    + value.stateSignature());
                }
            }
            buckets.put(entry.getKey(), List.copyOf(ranked));
        }

        List<DepthPlan> depths = new ArrayList<>();
        List<RankedCandidate> cumulative = new ArrayList<>();
        for (int depth = 1; depth <= maxDepth; depth++) {
            List<RankedCandidate> added = new ArrayList<>();
            for (List<RankedCandidate> bucket : buckets.values()) {
                if (bucket.size() >= depth) {
                    added.add(bucket.get(depth - 1));
                }
            }
            added.sort(Comparator.comparing(RankedCandidate::supportIdentity));
            cumulative.addAll(added);
            depths.add(new DepthPlan(
                    depth,
                    added.size(),
                    List.copyOf(added),
                    List.copyOf(cumulative),
                    snapshot.candidates().size()));
        }
        return new TopNPlan(
                snapshot.payloadSha256(),
                snapshot.candidates().size(),
                snapshot.supportCount(),
                maxDepth,
                buckets,
                byState,
                depths);
    }

    static ExactRun evaluateAndPersist(
            TopNPlan plan,
            int depth,
            Path exactResultsPath,
            ExactConfig config,
            int maxNewStates,
            ExactSolver solver,
            Consumer<ExactResult> listener) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(exactResultsPath, "exactResultsPath");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(solver, "solver");
        listener = listener == null ? ignored -> { } : listener;
        if (depth <= 0 || depth > plan.maxDepth()) {
            throw new IllegalArgumentException("depth is outside the plan");
        }
        if (maxNewStates < 0) {
            throw new IllegalArgumentException(
                    "maxNewStates must be zero (unlimited) or positive");
        }

        ExactResults exact = Files.exists(exactResultsPath)
                ? readExactResults(exactResultsPath, plan, config)
                : emptyExactResults(plan, config);
        if (!Files.exists(exactResultsPath)) {
            exact = writeExactResults(exactResultsPath, exact, plan, config);
        }
        Map<String, ExactResult> byState = new LinkedHashMap<>(
                exact.byState());
        int newlyEvaluated = 0;
        for (RankedCandidate candidate
                : plan.depth(depth).cumulativeCandidates()) {
            if (byState.containsKey(candidate.stateSignature())) {
                continue;
            }
            if (maxNewStates > 0 && newlyEvaluated >= maxNewStates) {
                break;
            }
            ExactResult result = evaluate(candidate, config, solver);
            byState.put(result.stateSignature(), result);
            ExactResults updated = exactResults(plan, config, byState);
            exact = writeExactResults(
                    exactResultsPath, updated, plan, config);
            newlyEvaluated++;
            listener.accept(result);
        }
        return new ExactRun(
                exact,
                newlyEvaluated,
                summaries(plan, exact.byState()),
                exact.byState().size(),
                plan.totalStates());
    }

    static ExactResults readExactResults(
            Path path,
            TopNPlan plan,
            ExactConfig config) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> resultLines = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("# ")) {
                int equals = line.indexOf('=');
                if (equals <= 2) {
                    throw new IOException("Malformed exact-results header");
                }
                String key = line.substring(2, equals);
                if (headers.putIfAbsent(
                        key, line.substring(equals + 1)) != null) {
                    throw new IOException(
                            "Duplicate exact-results header: " + key);
                }
            } else if (line.startsWith(RESULT_PREFIX)) {
                resultLines.add(line);
            } else {
                throw new IOException("Unknown exact-results line: " + line);
            }
        }
        if (!EXACT_RESULTS_FORMAT.equals(headers.get("format"))) {
            throw new IOException("Unsupported exact-results format: "
                    + headers.get("format"));
        }
        if (!plan.snapshotPayloadSha256().equals(
                required(headers, "snapshotPayloadSha256"))) {
            throw new IOException(
                    "Exact results belong to another candidate snapshot");
        }
        if (!config.sha256().equals(
                required(headers, "exactModelConfigSha256"))) {
            throw new IOException(
                    "Exact results use another exact model configuration");
        }
        Map<String, ExactResult> byState = new TreeMap<>();
        for (String line : resultLines) {
            ExactResult result = parseResult(line);
            RankedCandidate planned = plan.byState().get(
                    result.stateSignature());
            if (planned == null
                    || planned.rank() != result.rank()
                    || !planned.supportIdentity().equals(
                    result.supportIdentity())) {
                throw new IOException(
                        "Exact result does not match the Top-N plan: "
                                + result.stateSignature());
            }
            validateStateMachine(result, config);
            if (classify(
                    result.firstThreshold(),
                    result.secondThreshold(),
                    result.fullAnalysis(),
                    config) != result.classification()) {
                throw new IOException(
                        "Exact result classification is inconsistent: "
                                + result.stateSignature());
            }
            if (byState.put(result.stateSignature(), result) != null) {
                throw new IOException("Duplicate exact result: "
                        + result.stateSignature());
            }
        }
        if (integer(headers, "resultCount") != byState.size()) {
            throw new IOException(
                    "Exact-results count does not match its payload");
        }
        ExactResults exact = new ExactResults(
                plan.snapshotPayloadSha256(),
                config.sha256(),
                byState,
                required(headers, "payloadSha256"));
        if (!exactPayloadSha256(exact).equals(exact.payloadSha256())) {
            throw new IOException("Exact-results payload hash mismatch");
        }
        return exact;
    }

    private static void validateStateMachine(
            ExactResult result,
            ExactConfig config) throws IOException {
        boolean firstConclusive = conclusive(result.firstThreshold().status());
        if (firstConclusive != (result.secondThreshold() == null)) {
            throw new IOException("Invalid exact retry state: "
                    + result.stateSignature());
        }
        ThresholdObservation decisive = result.secondThreshold() == null
                ? result.firstThreshold() : result.secondThreshold();
        boolean requiresFull = decisive.status()
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE
                || !conclusive(decisive.status());
        if (requiresFull != (result.fullAnalysis() != null)) {
            throw new IOException("Invalid full-analysis state: "
                    + result.stateSignature());
        }
        for (ThresholdObservation threshold : List.of(
                result.firstThreshold(), decisive)) {
            if (threshold.status()
                    == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE
                    && (threshold.feasibleGroups() > config.maxGroups()
                    || threshold.feasibleOddGroups()
                    != config.exactOddGroups()
                    || threshold.feasibleOneGroups()
                    != config.exactOneGroups())) {
                throw new IOException("Threshold witness violates target: "
                        + result.stateSignature());
            }
        }
        try {
            Instant.parse(result.evaluatedAtUtc());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid exact evaluation timestamp",
                    exception);
        }
    }

    static Path exactResultsPath(
            Path snapshotPath,
            String snapshotPayloadSha256) {
        String fileName = snapshotPath.getFileName().toString();
        String stem = fileName.endsWith(".tsv")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        String exactName = stem.replace(
                "snapshot-v1", "exact-results-v1")
                + "-" + snapshotPayloadSha256 + ".tsv";
        return snapshotPath.toAbsolutePath().normalize()
                .resolveSibling(exactName);
    }

    private static ExactResult evaluate(
            RankedCandidate candidate,
            ExactConfig config,
            ExactSolver solver) {
        ThresholdObservation first = solver.checkThreshold(
                candidate, config.firstThresholdMs());
        ThresholdObservation second = null;
        FullObservation full = null;
        if (!conclusive(first.status())) {
            second = solver.checkThreshold(
                    candidate, config.secondThresholdMs());
        }
        ThresholdObservation decisive = conclusive(first.status())
                ? first : second;
        if (decisive != null
                && decisive.status()
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE) {
            full = solver.analyze(candidate,
                    config.fullGroupMs(), config.fullShapeMs());
        } else if (decisive == null || !conclusive(decisive.status())) {
            full = solver.analyze(candidate,
                    config.fullGroupMs(), config.fullShapeMs());
        }
        FinalClassification classification = classify(
                first, second, full, config);
        return new ExactResult(
                candidate.stateSignature(),
                candidate.supportIdentity(),
                candidate.rank(),
                first,
                second,
                full,
                classification,
                Instant.now().toString());
    }

    private static FinalClassification classify(
            ThresholdObservation first,
            ThresholdObservation second,
            FullObservation full,
            ExactConfig config) {
        for (ThresholdObservation threshold : List.of(first,
                second == null ? first : second)) {
            if (threshold.status()
                    == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE) {
                return FinalClassification.TARGET_FEASIBLE;
            }
            if (threshold.status()
                    == OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE) {
                return FinalClassification.PROVEN_NOT_TARGET;
            }
            if (threshold == second || second == null) {
                break;
            }
        }
        if (full != null) {
            if (full.provenGroupLowerBound() > config.maxGroups()
                    || (full.groupOptimal()
                    && full.exactMinimumGroups() > config.maxGroups())) {
                return FinalClassification.PROVEN_NOT_TARGET;
            }
            if (full.groupOptimal()
                    && full.shapeOptimal()
                    && full.exactMinimumGroups() > 0
                    && full.exactMinimumGroups() <= config.maxGroups()
                    && full.oddGroups() == config.exactOddGroups()
                    && full.oneGroups() == config.exactOneGroups()) {
                return FinalClassification.TARGET_FEASIBLE;
            }
        }
        return FinalClassification.UNKNOWN;
    }

    private static boolean conclusive(
            OrderCompatibilityKernelAnalyzer.ThresholdStatus status) {
        return status
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE
                || status
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE;
    }

    private static ExactResults emptyExactResults(
            TopNPlan plan,
            ExactConfig config) {
        return exactResults(plan, config, Map.of());
    }

    private static ExactResults exactResults(
            TopNPlan plan,
            ExactConfig config,
            Map<String, ExactResult> byState) {
        ExactResults unhashed = new ExactResults(
                plan.snapshotPayloadSha256(),
                config.sha256(),
                byState,
                "");
        return new ExactResults(
                plan.snapshotPayloadSha256(),
                config.sha256(),
                byState,
                exactPayloadSha256(unhashed));
    }

    private static ExactResults writeExactResults(
            Path path,
            ExactResults exact,
            TopNPlan plan,
            ExactConfig config) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(absolute.getParent());
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(
                parent, absolute.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, exactLines(exact), StandardCharsets.UTF_8);
            ExactResults reread = readExactResults(
                    temporary, plan, config);
            if (!exact.payloadSha256().equals(reread.payloadSha256())) {
                throw new IOException(
                        "Exact results changed during write verification");
            }
            try {
                Files.move(temporary, absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return readExactResults(absolute, plan, config);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static List<String> exactLines(ExactResults exact) {
        List<String> lines = new ArrayList<>();
        lines.add("# format=" + EXACT_RESULTS_FORMAT);
        lines.add("# snapshotPayloadSha256="
                + exact.snapshotPayloadSha256());
        lines.add("# exactModelConfigSha256="
                + exact.exactModelConfigSha256());
        lines.add("# resultCount=" + exact.byState().size());
        lines.add("# payloadSha256=" + exact.payloadSha256());
        exact.byState().values().stream()
                .sorted(Comparator.comparing(ExactResult::stateSignature))
                .map(SupportTopNExactEvaluator::resultLine)
                .forEach(lines::add);
        return lines;
    }

    private static String exactPayloadSha256(ExactResults exact) {
        List<String> lines = new ArrayList<>();
        lines.add("format=" + EXACT_RESULTS_FORMAT);
        lines.add("snapshotPayloadSha256="
                + exact.snapshotPayloadSha256());
        lines.add("exactModelConfigSha256="
                + exact.exactModelConfigSha256());
        exact.byState().values().stream()
                .sorted(Comparator.comparing(ExactResult::stateSignature))
                .map(SupportTopNExactEvaluator::resultLine)
                .forEach(lines::add);
        return SupportCandidateSnapshotArchive.sha256OfLines(lines);
    }

    private static String resultLine(ExactResult result) {
        return String.join("\t",
                "RESULT",
                encode(result.stateSignature()),
                encode(result.supportIdentity()),
                Integer.toString(result.rank()),
                encode(threshold(result.firstThreshold())),
                result.secondThreshold() == null ? "-"
                        : encode(threshold(result.secondThreshold())),
                result.fullAnalysis() == null ? "-"
                        : encode(full(result.fullAnalysis())),
                result.classification().name(),
                encode(result.evaluatedAtUtc()));
    }

    private static ExactResult parseResult(String line) throws IOException {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 9 || !"RESULT".equals(fields[0])) {
            throw new IOException("Malformed exact result line");
        }
        try {
            return new ExactResult(
                    decode(fields[1]),
                    decode(fields[2]),
                    Integer.parseInt(fields[3]),
                    parseThreshold(decode(fields[4])),
                    "-".equals(fields[5]) ? null
                            : parseThreshold(decode(fields[5])),
                    "-".equals(fields[6]) ? null
                            : parseFull(decode(fields[6])),
                    FinalClassification.valueOf(fields[7]),
                    decode(fields[8]));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid exact result payload", exception);
        }
    }

    private static String threshold(ThresholdObservation value) {
        return String.join("|",
                value.status().name(),
                value.solverName(),
                value.solverStatus(),
                Integer.toString(value.feasibleGroups()),
                Integer.toString(value.feasibleOddGroups()),
                Integer.toString(value.feasibleOneGroups()),
                Long.toString(value.configurationCount()),
                Integer.toString(value.variableCount()),
                Integer.toString(value.constraintCount()),
                Long.toString(value.nodes()),
                Long.toString(value.enumerationMs()),
                Long.toString(value.solveMs()),
                Long.toString(value.totalElapsedMs()));
    }

    private static ThresholdObservation parseThreshold(String encoded) {
        String[] values = encoded.split("\\|", -1);
        if (values.length != 13) {
            throw new IllegalArgumentException("Malformed threshold evidence");
        }
        return new ThresholdObservation(
                OrderCompatibilityKernelAnalyzer.ThresholdStatus.valueOf(
                        values[0]),
                values[1],
                values[2],
                Integer.parseInt(values[3]),
                Integer.parseInt(values[4]),
                Integer.parseInt(values[5]),
                Long.parseLong(values[6]),
                Integer.parseInt(values[7]),
                Integer.parseInt(values[8]),
                Long.parseLong(values[9]),
                Long.parseLong(values[10]),
                Long.parseLong(values[11]),
                Long.parseLong(values[12]));
    }

    private static String full(FullObservation value) {
        return String.join("|",
                value.status().name(),
                value.solverName(),
                value.groupSolverStatus(),
                value.oddSolverStatus(),
                value.oneSolverStatus(),
                Boolean.toString(value.groupOptimal()),
                Boolean.toString(value.shapeOptimal()),
                Integer.toString(value.feasibleGroups()),
                Integer.toString(value.provenGroupLowerBound()),
                Integer.toString(value.exactMinimumGroups()),
                Integer.toString(value.exactExtraGroups()),
                Integer.toString(value.oddGroups()),
                Integer.toString(value.oneGroups()),
                Long.toString(value.configurationCount()),
                Integer.toString(value.variableCount()),
                Integer.toString(value.constraintCount()),
                Long.toString(value.nodes()),
                Long.toString(value.enumerationMs()),
                Long.toString(value.groupSolveMs()),
                Long.toString(value.shapeSolveMs()),
                Long.toString(value.totalElapsedMs()));
    }

    private static FullObservation parseFull(String encoded) {
        String[] values = encoded.split("\\|", -1);
        if (values.length != 21) {
            throw new IllegalArgumentException("Malformed full evidence");
        }
        return new FullObservation(
                OrderCompatibilityKernelAnalyzer.Status.valueOf(values[0]),
                values[1],
                values[2],
                values[3],
                values[4],
                Boolean.parseBoolean(values[5]),
                Boolean.parseBoolean(values[6]),
                Integer.parseInt(values[7]),
                Integer.parseInt(values[8]),
                Integer.parseInt(values[9]),
                Integer.parseInt(values[10]),
                Integer.parseInt(values[11]),
                Integer.parseInt(values[12]),
                Long.parseLong(values[13]),
                Integer.parseInt(values[14]),
                Integer.parseInt(values[15]),
                Long.parseLong(values[16]),
                Long.parseLong(values[17]),
                Long.parseLong(values[18]),
                Long.parseLong(values[19]),
                Long.parseLong(values[20]));
    }

    private static List<DepthExactSummary> summaries(
            TopNPlan plan,
            Map<String, ExactResult> results) {
        List<DepthExactSummary> summaries = new ArrayList<>();
        for (DepthPlan depth : plan.depths()) {
            int target = 0;
            int proven = 0;
            int unknown = 0;
            int evaluated = 0;
            for (RankedCandidate candidate : depth.cumulativeCandidates()) {
                ExactResult result = results.get(candidate.stateSignature());
                if (result == null) {
                    continue;
                }
                evaluated++;
                switch (result.classification()) {
                    case TARGET_FEASIBLE -> target++;
                    case PROVEN_NOT_TARGET -> proven++;
                    case UNKNOWN -> unknown++;
                }
            }
            summaries.add(new DepthExactSummary(
                    depth.depth(),
                    depth.supportsWithRank(),
                    depth.newCandidates().size(),
                    depth.cumulativeCandidates().size(),
                    evaluated,
                    target,
                    proven,
                    unknown,
                    depth.stateDenominator()));
        }
        return List.copyOf(summaries);
    }

    private static String required(Map<String, String> values, String key)
            throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing exact-results header: " + key);
        }
        return value;
    }

    private static int integer(Map<String, String> values, String key)
            throws IOException {
        try {
            return Integer.parseInt(required(values, key));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid exact-results integer: " + key,
                    exception);
        }
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) throws IOException {
        try {
            return new String(DECODER.decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid exact-results Base64 value",
                    exception);
        }
    }

    interface ExactSolver {
        ThresholdObservation checkThreshold(
                RankedCandidate candidate,
                long timeLimitMs);

        FullObservation analyze(
                RankedCandidate candidate,
                long groupTimeLimitMs,
                long shapeTimeLimitMs);
    }

    enum FinalClassification {
        TARGET_FEASIBLE,
        PROVEN_NOT_TARGET,
        UNKNOWN
    }

    record ExactConfig(
            int maxGroups,
            int exactOddGroups,
            int exactOneGroups,
            long firstThresholdMs,
            long secondThresholdMs,
            long fullGroupMs,
            long fullShapeMs,
            long maxConfigurations,
            long nodeLimit) {

        ExactConfig {
            if (maxGroups <= 0 || exactOddGroups < 0 || exactOneGroups < 0
                    || firstThresholdMs <= 0 || secondThresholdMs <= 0
                    || fullGroupMs <= 0 || fullShapeMs < 0
                    || maxConfigurations <= 0
                    || nodeLimit == 0 || nodeLimit < -1) {
                throw new IllegalArgumentException("Invalid exact config");
            }
        }

        String sha256() {
            Map<String, String> values = new TreeMap<>();
            values.put("maxGroups", Integer.toString(maxGroups));
            values.put("exactOddGroups", Integer.toString(exactOddGroups));
            values.put("exactOneGroups", Integer.toString(exactOneGroups));
            values.put("firstThresholdMs", Long.toString(firstThresholdMs));
            values.put("secondThresholdMs", Long.toString(secondThresholdMs));
            values.put("fullGroupMs", Long.toString(fullGroupMs));
            values.put("fullShapeMs", Long.toString(fullShapeMs));
            values.put("maxConfigurations", Long.toString(maxConfigurations));
            values.put("nodeLimit", Long.toString(nodeLimit));
            return SupportCandidateSnapshotArchive.hashMetadata(values);
        }
    }

    record RankedCandidate(
            SupportCandidateSnapshotArchive.PersistedCandidate candidate,
            int rank) {

        RankedCandidate {
            candidate = Objects.requireNonNull(candidate);
            if (rank <= 0) {
                throw new IllegalArgumentException("rank must be positive");
            }
        }

        String stateSignature() {
            return candidate.state().stateSignature();
        }

        String supportIdentity() {
            return candidate.state().supportIdentity();
        }
    }

    record DepthPlan(
            int depth,
            int supportsWithRank,
            List<RankedCandidate> newCandidates,
            List<RankedCandidate> cumulativeCandidates,
            int stateDenominator) {

        DepthPlan {
            newCandidates = List.copyOf(newCandidates);
            cumulativeCandidates = List.copyOf(cumulativeCandidates);
        }
    }

    record TopNPlan(
            String snapshotPayloadSha256,
            int totalStates,
            int totalSupports,
            int maxDepth,
            Map<String, List<RankedCandidate>> buckets,
            Map<String, RankedCandidate> byState,
            List<DepthPlan> depths) {

        TopNPlan {
            snapshotPayloadSha256 = Objects.requireNonNull(
                    snapshotPayloadSha256);
            Map<String, List<RankedCandidate>> bucketCopy = new TreeMap<>();
            buckets.forEach((support, values) -> bucketCopy.put(
                    support, List.copyOf(values)));
            buckets = Collections.unmodifiableMap(bucketCopy);
            byState = Collections.unmodifiableMap(new TreeMap<>(byState));
            depths = List.copyOf(depths);
        }

        DepthPlan depth(int value) {
            return depths.stream()
                    .filter(depth -> depth.depth() == value)
                    .findFirst()
                    .orElseThrow();
        }
    }

    record ThresholdObservation(
            OrderCompatibilityKernelAnalyzer.ThresholdStatus status,
            String solverName,
            String solverStatus,
            int feasibleGroups,
            int feasibleOddGroups,
            int feasibleOneGroups,
            long configurationCount,
            int variableCount,
            int constraintCount,
            long nodes,
            long enumerationMs,
            long solveMs,
            long totalElapsedMs) {

        ThresholdObservation {
            status = Objects.requireNonNull(status);
            solverName = Objects.requireNonNull(solverName);
            solverStatus = Objects.requireNonNull(solverStatus);
        }

        static ThresholdObservation from(
                OrderCompatibilityKernelAnalyzer.ThresholdAnalysis analysis) {
            return new ThresholdObservation(
                    analysis.status(),
                    analysis.solverName(),
                    analysis.solverStatus().name(),
                    analysis.feasibleGroups(),
                    analysis.feasibleOddGroups(),
                    analysis.feasibleOneGroups(),
                    analysis.configurationCount(),
                    analysis.variableCount(),
                    analysis.constraintCount(),
                    analysis.nodes(),
                    analysis.enumerationMs(),
                    analysis.solveMs(),
                    analysis.totalElapsedMs());
        }
    }

    record FullObservation(
            OrderCompatibilityKernelAnalyzer.Status status,
            String solverName,
            String groupSolverStatus,
            String oddSolverStatus,
            String oneSolverStatus,
            boolean groupOptimal,
            boolean shapeOptimal,
            int feasibleGroups,
            int provenGroupLowerBound,
            int exactMinimumGroups,
            int exactExtraGroups,
            int oddGroups,
            int oneGroups,
            long configurationCount,
            int variableCount,
            int constraintCount,
            long nodes,
            long enumerationMs,
            long groupSolveMs,
            long shapeSolveMs,
            long totalElapsedMs) {

        FullObservation {
            status = Objects.requireNonNull(status);
            solverName = Objects.requireNonNull(solverName);
            groupSolverStatus = Objects.requireNonNull(groupSolverStatus);
            oddSolverStatus = Objects.requireNonNull(oddSolverStatus);
            oneSolverStatus = Objects.requireNonNull(oneSolverStatus);
        }

        static FullObservation from(
                OrderCompatibilityKernelAnalyzer.Analysis analysis) {
            return new FullObservation(
                    analysis.status(),
                    analysis.solverName(),
                    analysis.groupSolverStatus().name(),
                    analysis.oddSolverStatus().name(),
                    analysis.oneSolverStatus().name(),
                    analysis.groupOptimal(),
                    analysis.shapeOptimal(),
                    analysis.feasibleGroups(),
                    analysis.provenGroupLowerBound(),
                    analysis.exactMinimumGroups(),
                    analysis.exactExtraGroups(),
                    analysis.oddGroups(),
                    analysis.oneGroups(),
                    analysis.configurationCount(),
                    analysis.variableCount(),
                    analysis.constraintCount(),
                    analysis.nodes(),
                    analysis.enumerationMs(),
                    analysis.groupSolveMs(),
                    analysis.shapeSolveMs(),
                    analysis.totalElapsedMs());
        }
    }

    record ExactResult(
            String stateSignature,
            String supportIdentity,
            int rank,
            ThresholdObservation firstThreshold,
            ThresholdObservation secondThreshold,
            FullObservation fullAnalysis,
            FinalClassification classification,
            String evaluatedAtUtc) {

        ExactResult {
            stateSignature = Objects.requireNonNull(stateSignature);
            supportIdentity = Objects.requireNonNull(supportIdentity);
            firstThreshold = Objects.requireNonNull(firstThreshold);
            classification = Objects.requireNonNull(classification);
            evaluatedAtUtc = Objects.requireNonNull(evaluatedAtUtc);
        }
    }

    record ExactResults(
            String snapshotPayloadSha256,
            String exactModelConfigSha256,
            Map<String, ExactResult> byState,
            String payloadSha256) {

        ExactResults {
            snapshotPayloadSha256 = Objects.requireNonNull(
                    snapshotPayloadSha256);
            exactModelConfigSha256 = Objects.requireNonNull(
                    exactModelConfigSha256);
            byState = Collections.unmodifiableMap(new TreeMap<>(byState));
            payloadSha256 = Objects.requireNonNull(payloadSha256);
        }
    }

    record DepthExactSummary(
            int depth,
            int supportsWithRank,
            int newlyPlannedStates,
            int cumulativePlannedStates,
            int evaluatedStates,
            int targetFeasible,
            int provenNotTarget,
            int unknown,
            int stateDenominator) {
    }

    record ExactRun(
            ExactResults exactResults,
            int newlyEvaluated,
            List<DepthExactSummary> depthSummaries,
            int checkedUniqueStates,
            int stateDenominator) {

        ExactRun {
            exactResults = Objects.requireNonNull(exactResults);
            depthSummaries = List.copyOf(depthSummaries);
        }
    }
}
