package test.demo.apsmodule.generator.NewSolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Durable, solver-free evidence for support-generation plateau probes. */
final class SupportGenerationPlateauArchive {

    static final String FORMAT = "support-generation-plateau-v1";
    static final long BUDGET_20_MS = 20_000L;
    static final long BUDGET_60_MS = 60_000L;
    static final long BUDGET_180_MS = 180_000L;
    static final Map<Long, Integer> DEFAULT_PLAN = Map.of(
            BUDGET_20_MS, 3,
            BUDGET_60_MS, 2,
            BUDGET_180_MS, 2);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder()
            .withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final double STABLE_JACCARD = 0.90;
    private static final double STABLE_RELATIVE_RANGE = 0.10;
    private static final long MAX_WALL_CLOCK_BUDGET_MULTIPLIER = 2L;

    private SupportGenerationPlateauArchive() {
    }

    static Report create(
            String dataset,
            String baselineSnapshotPayloadSha256,
            Map<String, String> identityMetadata,
            Map<String, String> producerMetadata,
            Set<String> baselineSupports,
            List<RunObservation> runs,
            ValidationContext validation) throws IOException {
        Report unhashed = new Report(
                dataset,
                baselineSnapshotPayloadSha256,
                identityMetadata,
                producerMetadata,
                baselineSupports,
                runs,
                PlateauStatus.INCONCLUSIVE,
                "");
        PlateauStatus status = analyze(unhashed).status();
        Report classified = new Report(
                dataset,
                baselineSnapshotPayloadSha256,
                identityMetadata,
                producerMetadata,
                baselineSupports,
                runs,
                status,
                "");
        Report report = new Report(
                dataset,
                baselineSnapshotPayloadSha256,
                identityMetadata,
                producerMetadata,
                baselineSupports,
                runs,
                status,
                payloadSha256(classified));
        validate(report, validation);
        return report;
    }

    static Report read(Path path, ValidationContext validation)
            throws IOException {
        Objects.requireNonNull(path, "path");
        Map<String, String> headers = new LinkedHashMap<>();
        Set<String> baselineSupports = new TreeSet<>();
        Map<RunKey, MutableRun> mutableRuns = new TreeMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("# ")) {
                int equals = line.indexOf('=');
                if (equals <= 2 || headers.putIfAbsent(
                        line.substring(2, equals),
                        line.substring(equals + 1)) != null) {
                    throw new IOException("Malformed or duplicate plateau header");
                }
                continue;
            }
            String[] fields = line.split("\\t", -1);
            switch (fields[0]) {
                case "BASELINE_SUPPORT" -> {
                    requireFields(fields, 2, "BASELINE_SUPPORT");
                    if (!baselineSupports.add(decode(fields[1]))) {
                        throw new IOException("Duplicate baseline support");
                    }
                }
                case "RUN" -> parseRun(fields, mutableRuns);
                case "LAYER" -> parseLayer(fields, mutableRuns);
                case "SUPPORT" -> parseSupport(fields, mutableRuns);
                case "STATE" -> parseState(fields, mutableRuns);
                default -> throw new IOException(
                        "Unknown plateau payload line: " + fields[0]);
            }
        }
        if (!FORMAT.equals(required(headers, "format"))) {
            throw new IOException("Unsupported plateau report format");
        }
        List<RunObservation> runs = new ArrayList<>();
        for (MutableRun mutable : mutableRuns.values()) {
            runs.add(mutable.freeze());
        }
        Report report = new Report(
                required(headers, "dataset"),
                required(headers, "baselineSnapshotPayloadSha256"),
                metadata(headers, "identity."),
                metadata(headers, "producer."),
                baselineSupports,
                runs,
                plateauStatus(required(headers, "plateauStatus")),
                required(headers, "payloadSha256"));
        verifyCount(headers, "baselineSupportCount",
                report.baselineSupports().size());
        verifyCount(headers, "runCount", report.runs().size());
        verifyCount(headers, "supportRecordCount", report.runs().stream()
                .mapToInt(run -> run.supportIdentities().size()).sum());
        verifyCount(headers, "stateRecordCount", report.runs().stream()
                .mapToInt(run -> run.stateSignatures().size()).sum());
        validate(report, validation);
        return report;
    }

    static Report write(
            Path path,
            Report report,
            WriteMode mode,
            ValidationContext validation) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        validate(report, validation);
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(absolute.getParent());
        Files.createDirectories(parent);
        if (mode == WriteMode.CREATE && Files.exists(absolute)) {
            throw new FileAlreadyExistsException(absolute.toString());
        }
        Path temporary = Files.createTempFile(
                parent, absolute.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, lines(report), StandardCharsets.UTF_8);
            Report reread = read(temporary, validation);
            if (!report.payloadSha256().equals(reread.payloadSha256())) {
                throw new IOException("Plateau report changed during write");
            }
            move(temporary, absolute, mode == WriteMode.REPLACE);
            moved = true;
            return read(absolute, validation);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static Analysis analyze(Report report) {
        Objects.requireNonNull(report, "report");
        Set<String> wallClockContaminatedRunIds = report.runs().stream()
                .filter(SupportGenerationPlateauArchive
                        ::hasWallClockBudgetOverrun)
                .map(RunObservation::runId)
                .collect(TreeSet::new, Set::add, Set::addAll);
        Set<String> excludedRunIds = report.runs().stream()
                .filter(run -> hasIncompleteLayer(run)
                        || wallClockContaminatedRunIds.contains(run.runId()))
                .map(RunObservation::runId)
                .collect(TreeSet::new, Set::add, Set::addAll);
        List<RunObservation> usableRuns = report.runs().stream()
                .filter(run -> !excludedRunIds.contains(run.runId()))
                .toList();
        Map<Long, List<RunObservation>> byBudget = new TreeMap<>();
        usableRuns.forEach(run -> byBudget
                .computeIfAbsent(run.budgetMs(), ignored -> new ArrayList<>())
                .add(run));
        Map<Long, BudgetSummary> summaries = new TreeMap<>();
        byBudget.forEach((budget, runs) -> summaries.put(
                budget, summarizeBudget(budget, runs)));

        Set<String> union20 = supportUnion(byBudget.getOrDefault(
                BUDGET_20_MS, List.of()));
        Set<String> union60 = supportUnion(byBudget.getOrDefault(
                BUDGET_60_MS, List.of()));
        Set<String> union180 = supportUnion(byBudget.getOrDefault(
                BUDGET_180_MS, List.of()));
        Set<String> lowerUnion = union(union20, union60);
        Set<String> novel60 = difference(union60, union20);
        Set<String> novel180 = difference(union180, lowerUnion);
        Set<String> cumulative = union(lowerUnion, union180);

        List<RunObservation> runs60 = byBudget.getOrDefault(
                BUDGET_60_MS, List.of());
        List<RunObservation> runs180 = byBudget.getOrDefault(
                BUDGET_180_MS, List.of());
        boolean incomplete = !excludedRunIds.isEmpty();
        boolean all180Exhausted = !runs180.isEmpty()
                && runs180.stream().allMatch(run -> run.layers().values().stream()
                .allMatch(layer -> layer.status()
                        == SparseProductionNeutralMoveEnumerator.Status.EXHAUSTED));
        boolean same180 = runs180.size() >= 2
                && runs180.stream().map(RunObservation::supportIdentities)
                .distinct().count() == 1;
        boolean capped60Or180 = report.runs().stream()
                .filter(run -> run.budgetMs() >= BUDGET_60_MS)
                .flatMap(run -> run.layers().values().stream())
                .anyMatch(layer -> layer.status()
                        == SparseProductionNeutralMoveEnumerator.Status.CAPPED);
        boolean stable60 = stable(summaries.get(BUDGET_60_MS));
        boolean stable180 = stable(summaries.get(BUDGET_180_MS));

        PlateauStatus status;
        if (!incomplete
                && all180Exhausted && same180 && novel180.isEmpty()) {
            status = PlateauStatus.PROVEN_EXHAUSTED;
        } else if (!novel180.isEmpty()) {
            status = PlateauStatus.STILL_GROWING;
        } else if (!incomplete
                && !capped60Or180 && stable60 && stable180) {
            status = PlateauStatus.EMPIRICAL_PLATEAU;
        } else {
            status = PlateauStatus.INCONCLUSIVE;
        }
        return new Analysis(
                status,
                summaries,
                union20,
                union60,
                union180,
                novel60,
                novel180,
                cumulative,
                incomplete,
                excludedRunIds,
                wallClockContaminatedRunIds,
                all180Exhausted,
                capped60Or180,
                stable60,
                stable180);
    }

    private static boolean hasIncompleteLayer(RunObservation run) {
        return run.layers().values().stream().anyMatch(layer ->
                layer.status()
                        == SparseProductionNeutralMoveEnumerator.Status.ABNORMAL
                        || layer.status()
                        == SparseProductionNeutralMoveEnumerator.Status
                                .SOLVER_UNAVAILABLE
                        || layer.status()
                        == SparseProductionNeutralMoveEnumerator.Status.PARTIAL);
    }

    private static boolean hasWallClockBudgetOverrun(RunObservation run) {
        long maximumElapsed =
                run.budgetMs() * MAX_WALL_CLOCK_BUDGET_MULTIPLIER;
        return run.layers().values().stream()
                .anyMatch(layer -> layer.elapsedMs() > maximumElapsed);
    }

    static BaselineOverlap baselineOverlap(
            RunObservation run,
            Set<String> baselineSupports) {
        Set<String> intersection = intersection(
                run.supportIdentities(), baselineSupports);
        Set<String> union = union(run.supportIdentities(), baselineSupports);
        return new BaselineOverlap(
                intersection.size(),
                difference(run.supportIdentities(), baselineSupports).size(),
                difference(baselineSupports, run.supportIdentities()).size(),
                union.isEmpty() ? 1.0
                        : (double) intersection.size() / union.size());
    }

    static String payloadSha256(Report report) {
        return SupportCandidateSnapshotArchive.sha256OfLines(
                payloadLines(report));
    }

    private static void validate(
            Report report,
            ValidationContext validation) throws IOException {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(validation, "validation");
        if (!validation.dataset().equals(report.dataset())) {
            throw new IOException("Plateau dataset mismatch");
        }
        if (!validation.baselineSnapshotPayloadSha256().equals(
                report.baselineSnapshotPayloadSha256())) {
            throw new IOException("Plateau report belongs to another snapshot");
        }
        if (!validation.baselineSupports().equals(report.baselineSupports())) {
            throw new IOException("Plateau baseline support set mismatch");
        }
        String expectedConfigHash = SupportCandidateSnapshotArchive.hashMetadata(
                withoutKey(report.identityMetadata(), "probeConfigSha256"));
        if (!expectedConfigHash.equals(
                report.identityMetadata().get("probeConfigSha256"))) {
            throw new IOException("Plateau probe configuration hash mismatch");
        }
        Map<Long, Integer> actualPlan = new TreeMap<>();
        Set<RunKey> runKeys = new TreeSet<>();
        for (RunObservation run : report.runs()) {
            RunKey key = new RunKey(run.budgetMs(), run.repetition());
            if (!runKeys.add(key)) {
                throw new IOException("Duplicate plateau run: " + run.runId());
            }
            actualPlan.merge(run.budgetMs(), 1, Integer::sum);
            if (!run.runId().equals(key.runId())) {
                throw new IOException("Unstable plateau run id");
            }
            if (run.layers().size()
                    != StructuredLocalPatternUniverseBuilder.UniverseScope
                            .values().length) {
                throw new IOException("Plateau run does not contain all layers");
            }
            if (run.supportIdentities().isEmpty()
                    || run.stateSignatures().isEmpty()
                    || run.supportIdentities().size()
                    > run.stateSignatures().size()) {
                throw new IOException("Invalid plateau run payload");
            }
        }
        if (!new TreeMap<>(validation.expectedBudgetRuns()).equals(actualPlan)) {
            throw new IOException("Plateau budget/repetition plan mismatch");
        }
        PlateauStatus recomputed = analyze(report).status();
        if (recomputed != report.plateauStatus()) {
            throw new IOException("Plateau classification mismatch");
        }
        if (!payloadSha256(report).equals(report.payloadSha256())) {
            throw new IOException("Plateau payload hash mismatch");
        }
    }

    private static BudgetSummary summarizeBudget(
            long budget,
            List<RunObservation> runs) {
        List<Integer> supports = runs.stream()
                .map(run -> run.supportIdentities().size()).sorted().toList();
        List<Integer> states = runs.stream()
                .map(run -> run.stateSignatures().size()).sorted().toList();
        List<Double> pairwise = new ArrayList<>();
        for (int left = 0; left < runs.size(); left++) {
            for (int right = left + 1; right < runs.size(); right++) {
                pairwise.add(jaccard(
                        runs.get(left).supportIdentities(),
                        runs.get(right).supportIdentities()));
            }
        }
        Set<String> intersection = runs.isEmpty()
                ? Set.of() : new TreeSet<>(runs.get(0).supportIdentities());
        for (int index = 1; index < runs.size(); index++) {
            intersection.retainAll(runs.get(index).supportIdentities());
        }
        return new BudgetSummary(
                budget,
                runs.size(),
                scalarStats(supports),
                scalarStats(states),
                jaccardStats(pairwise),
                intersection,
                supportUnion(runs));
    }

    private static ScalarStats scalarStats(List<Integer> sorted) {
        if (sorted.isEmpty()) {
            return new ScalarStats(0, 0, 0.0, 0.0, 0.0);
        }
        double mean = sorted.stream().mapToInt(Integer::intValue)
                .average().orElse(0.0);
        double median = sorted.size() % 2 == 1
                ? sorted.get(sorted.size() / 2)
                : (sorted.get(sorted.size() / 2 - 1)
                + sorted.get(sorted.size() / 2)) / 2.0;
        double variance = sorted.size() <= 1 ? 0.0 : sorted.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2.0)).sum()
                / (sorted.size() - 1);
        return new ScalarStats(
                sorted.get(0),
                sorted.get(sorted.size() - 1),
                mean,
                median,
                Math.sqrt(variance));
    }

    private static JaccardStats jaccardStats(List<Double> values) {
        if (values.isEmpty()) {
            return new JaccardStats(0, 1.0, 1.0, 1.0);
        }
        return new JaccardStats(
                values.size(),
                values.stream().mapToDouble(Double::doubleValue).min()
                        .orElse(1.0),
                values.stream().mapToDouble(Double::doubleValue).max()
                        .orElse(1.0),
                values.stream().mapToDouble(Double::doubleValue).average()
                        .orElse(1.0));
    }

    private static boolean stable(BudgetSummary summary) {
        if (summary == null || summary.repetitions() < 2) {
            return false;
        }
        ScalarStats supports = summary.supportStats();
        double relativeRange = supports.max() == 0 ? 0.0
                : (double) (supports.max() - supports.min()) / supports.max();
        return summary.pairwiseJaccard().min() >= STABLE_JACCARD
                && relativeRange <= STABLE_RELATIVE_RANGE;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        Set<String> union = union(left, right);
        return union.isEmpty() ? 1.0
                : (double) intersection(left, right).size() / union.size();
    }

    private static Set<String> supportUnion(List<RunObservation> runs) {
        Set<String> result = new TreeSet<>();
        runs.forEach(run -> result.addAll(run.supportIdentities()));
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.addAll(right);
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> intersection(
            Set<String> left,
            Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.retainAll(right);
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> difference(
            Set<String> left,
            Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return Collections.unmodifiableSet(result);
    }

    private static List<String> lines(Report report) {
        List<String> result = new ArrayList<>();
        result.add("# format=" + FORMAT);
        result.add("# dataset=" + report.dataset());
        result.add("# baselineSnapshotPayloadSha256="
                + report.baselineSnapshotPayloadSha256());
        report.identityMetadata().forEach((key, value) -> result.add(
                "# identity." + key + "=" + encode(value)));
        report.producerMetadata().forEach((key, value) -> result.add(
                "# producer." + key + "=" + encode(value)));
        result.add("# plateauStatus=" + report.plateauStatus());
        result.add("# baselineSupportCount="
                + report.baselineSupports().size());
        result.add("# runCount=" + report.runs().size());
        result.add("# supportRecordCount=" + report.runs().stream()
                .mapToInt(run -> run.supportIdentities().size()).sum());
        result.add("# stateRecordCount=" + report.runs().stream()
                .mapToInt(run -> run.stateSignatures().size()).sum());
        result.add("# payloadSha256=" + report.payloadSha256());
        result.addAll(payloadDataLines(report));
        return result;
    }

    private static List<String> payloadLines(Report report) {
        List<String> result = new ArrayList<>();
        result.add("format=" + FORMAT);
        result.add("dataset=" + token(report.dataset()));
        result.add("baselineSnapshotPayloadSha256="
                + report.baselineSnapshotPayloadSha256());
        report.identityMetadata().forEach((key, value) -> result.add(
                "identity." + token(key) + "=" + token(value)));
        result.add("plateauStatus=" + report.plateauStatus());
        result.addAll(payloadDataLines(report));
        return result;
    }

    private static List<String> payloadDataLines(Report report) {
        List<String> result = new ArrayList<>();
        report.baselineSupports().forEach(support -> result.add(
                "BASELINE_SUPPORT\t" + encode(support)));
        for (RunObservation run : report.runs()) {
            result.add(String.join("\t",
                    "RUN",
                    Long.toString(run.budgetMs()),
                    Integer.toString(run.repetition()),
                    encode(run.runId())));
            run.layers().values().forEach(layer -> result.add(layerLine(
                    run.budgetMs(), run.repetition(), layer)));
            run.supportIdentities().forEach(support -> result.add(String.join(
                    "\t", "SUPPORT", Long.toString(run.budgetMs()),
                    Integer.toString(run.repetition()), encode(support))));
            run.stateSignatures().forEach(state -> result.add(String.join(
                    "\t", "STATE", Long.toString(run.budgetMs()),
                    Integer.toString(run.repetition()), encode(state))));
        }
        return result;
    }

    private static String layerLine(
            long budget,
            int repetition,
            LayerObservation layer) {
        return String.join("\t",
                "LAYER",
                Long.toString(budget),
                Integer.toString(repetition),
                layer.scope().name(),
                Integer.toString(layer.patternCount()),
                layer.status().name(),
                Integer.toString(layer.masterSolves()),
                Integer.toString(layer.supportsVisited()),
                Integer.toString(layer.coefficientSolutions()),
                Integer.toString(layer.uniqueStates()),
                Long.toString(layer.masterNodes()),
                Long.toString(layer.subproblemNodes()),
                Boolean.toString(layer.supportExhausted()),
                Boolean.toString(layer.coefficientExhausted()),
                Long.toString(layer.elapsedMs()));
    }

    private static void parseRun(
            String[] fields,
            Map<RunKey, MutableRun> runs) throws IOException {
        requireFields(fields, 4, "RUN");
        RunKey key = key(fields[1], fields[2]);
        MutableRun mutable = runs.computeIfAbsent(key, MutableRun::new);
        if (mutable.declared) {
            throw new IOException("Duplicate RUN declaration: " + key.runId());
        }
        mutable.declared = true;
        mutable.runId = decode(fields[3]);
    }

    private static void parseLayer(
            String[] fields,
            Map<RunKey, MutableRun> runs) throws IOException {
        requireFields(fields, 15, "LAYER");
        RunKey key = key(fields[1], fields[2]);
        MutableRun mutable = runs.computeIfAbsent(key, MutableRun::new);
        try {
            LayerObservation layer = new LayerObservation(
                    StructuredLocalPatternUniverseBuilder.UniverseScope
                            .valueOf(fields[3]),
                    integer(fields[4], "pattern count"),
                    SparseProductionNeutralMoveEnumerator.Status
                            .valueOf(fields[5]),
                    integer(fields[6], "master solves"),
                    integer(fields[7], "supports visited"),
                    integer(fields[8], "coefficient solutions"),
                    integer(fields[9], "unique states"),
                    longValue(fields[10], "master nodes"),
                    longValue(fields[11], "subproblem nodes"),
                    Boolean.parseBoolean(fields[12]),
                    Boolean.parseBoolean(fields[13]),
                    longValue(fields[14], "layer elapsed"));
            if (mutable.layers.putIfAbsent(layer.scope(), layer) != null) {
                throw new IOException("Duplicate plateau layer");
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid plateau layer", exception);
        }
    }

    private static void parseSupport(
            String[] fields,
            Map<RunKey, MutableRun> runs) throws IOException {
        requireFields(fields, 4, "SUPPORT");
        MutableRun mutable = runs.computeIfAbsent(
                key(fields[1], fields[2]), MutableRun::new);
        if (!mutable.supports.add(decode(fields[3]))) {
            throw new IOException("Duplicate run support identity");
        }
    }

    private static void parseState(
            String[] fields,
            Map<RunKey, MutableRun> runs) throws IOException {
        requireFields(fields, 4, "STATE");
        MutableRun mutable = runs.computeIfAbsent(
                key(fields[1], fields[2]), MutableRun::new);
        if (!mutable.states.add(decode(fields[3]))) {
            throw new IOException("Duplicate run state signature");
        }
    }

    private static RunKey key(String budget, String repetition)
            throws IOException {
        return new RunKey(
                longValue(budget, "run budget"),
                integer(repetition, "run repetition"));
    }

    private static Map<String, String> metadata(
            Map<String, String> headers,
            String prefix) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()),
                        decode(entry.getValue()));
            }
        }
        if (result.isEmpty()) {
            throw new IOException("Missing plateau metadata: " + prefix);
        }
        return result;
    }

    private static Map<String, String> withoutKey(
            Map<String, String> source,
            String key) {
        Map<String, String> copy = new TreeMap<>(source);
        copy.remove(key);
        return copy;
    }

    private static void requireFields(
            String[] fields,
            int expected,
            String type) throws IOException {
        if (fields.length != expected) {
            throw new IOException("Malformed " + type + " plateau line");
        }
    }

    private static void verifyCount(
            Map<String, String> headers,
            String key,
            int actual) throws IOException {
        if (integer(required(headers, key), key) != actual) {
            throw new IOException("Plateau count mismatch: " + key);
        }
    }

    private static String required(Map<String, String> values, String key)
            throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing plateau header: " + key);
        }
        return value;
    }

    private static int integer(String value, String name) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid plateau integer: " + name, exception);
        }
    }

    private static long longValue(String value, String name)
            throws IOException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid plateau long: " + name, exception);
        }
    }

    private static PlateauStatus plateauStatus(String value)
            throws IOException {
        try {
            return PlateauStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid plateau status", exception);
        }
    }

    private static String encode(String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Plateau values must be one line");
        }
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) throws IOException {
        try {
            return new String(DECODER.decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid plateau Base64 value", exception);
        }
    }

    private static String token(String value) {
        return value.length() + ":" + value;
    }

    private static void move(Path temporary, Path target, boolean replace)
            throws IOException {
        StandardCopyOption[] atomic = replace
                ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        StandardCopyOption[] fallback = replace
                ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{};
        try {
            Files.move(temporary, target, atomic);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, fallback);
        }
    }

    enum PlateauStatus {
        PROVEN_EXHAUSTED,
        STILL_GROWING,
        EMPIRICAL_PLATEAU,
        INCONCLUSIVE
    }

    enum WriteMode {
        CREATE,
        REPLACE
    }

    record ValidationContext(
            String dataset,
            String baselineSnapshotPayloadSha256,
            Set<String> baselineSupports,
            Map<Long, Integer> expectedBudgetRuns) {

        ValidationContext {
            dataset = Objects.requireNonNull(dataset);
            baselineSnapshotPayloadSha256 = Objects.requireNonNull(
                    baselineSnapshotPayloadSha256);
            baselineSupports = Collections.unmodifiableSet(
                    new TreeSet<>(baselineSupports));
            expectedBudgetRuns = Collections.unmodifiableMap(
                    new TreeMap<>(expectedBudgetRuns));
            if (dataset.isBlank() || baselineSnapshotPayloadSha256.isBlank()
                    || baselineSupports.isEmpty()
                    || expectedBudgetRuns.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid plateau validation context");
            }
        }
    }

    record LayerObservation(
            StructuredLocalPatternUniverseBuilder.UniverseScope scope,
            int patternCount,
            SparseProductionNeutralMoveEnumerator.Status status,
            int masterSolves,
            int supportsVisited,
            int coefficientSolutions,
            int uniqueStates,
            long masterNodes,
            long subproblemNodes,
            boolean supportExhausted,
            boolean coefficientExhausted,
            long elapsedMs) {

        LayerObservation {
            scope = Objects.requireNonNull(scope);
            status = Objects.requireNonNull(status);
            if (patternCount <= 0 || masterSolves < 0 || supportsVisited < 0
                    || coefficientSolutions < 0 || uniqueStates < 0
                    || masterNodes < -1 || subproblemNodes < -1
                    || elapsedMs < 0) {
                throw new IllegalArgumentException(
                        "Invalid plateau layer observation");
            }
        }

        static LayerObservation from(
                StructuredLocalPatternUniverseBuilder.UniverseScope scope,
                int patternCount,
                SparseProductionNeutralMoveEnumerator.Result result) {
            SparseProductionNeutralMoveEnumerator.Metrics metrics =
                    result.metrics();
            return new LayerObservation(
                    scope,
                    patternCount,
                    result.status(),
                    metrics.masterSolves(),
                    metrics.supportsVisited(),
                    metrics.coefficientSolutions(),
                    metrics.uniqueStates(),
                    metrics.masterNodes(),
                    metrics.subproblemNodes(),
                    metrics.supportExhausted(),
                    metrics.coefficientExhausted(),
                    metrics.totalElapsedMs());
        }
    }

    record RunObservation(
            long budgetMs,
            int repetition,
            String runId,
            Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                    LayerObservation> layers,
            Set<String> supportIdentities,
            Set<String> stateSignatures) {

        RunObservation {
            if (budgetMs <= 0 || repetition <= 0) {
                throw new IllegalArgumentException("Invalid plateau run key");
            }
            runId = Objects.requireNonNull(runId);
            Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                    LayerObservation> layerCopy = new EnumMap<>(
                    StructuredLocalPatternUniverseBuilder.UniverseScope.class);
            layerCopy.putAll(layers);
            layers = Collections.unmodifiableMap(layerCopy);
            supportIdentities = Collections.unmodifiableSet(
                    new TreeSet<>(supportIdentities));
            stateSignatures = Collections.unmodifiableSet(
                    new TreeSet<>(stateSignatures));
        }
    }

    record Report(
            String dataset,
            String baselineSnapshotPayloadSha256,
            Map<String, String> identityMetadata,
            Map<String, String> producerMetadata,
            Set<String> baselineSupports,
            List<RunObservation> runs,
            PlateauStatus plateauStatus,
            String payloadSha256) {

        Report {
            dataset = Objects.requireNonNull(dataset);
            baselineSnapshotPayloadSha256 = Objects.requireNonNull(
                    baselineSnapshotPayloadSha256);
            identityMetadata = Collections.unmodifiableMap(
                    new TreeMap<>(identityMetadata));
            producerMetadata = Collections.unmodifiableMap(
                    new TreeMap<>(producerMetadata));
            baselineSupports = Collections.unmodifiableSet(
                    new TreeSet<>(baselineSupports));
            runs = runs.stream()
                    .sorted(Comparator.comparingLong(RunObservation::budgetMs)
                            .thenComparingInt(RunObservation::repetition))
                    .toList();
            plateauStatus = Objects.requireNonNull(plateauStatus);
            payloadSha256 = Objects.requireNonNull(payloadSha256);
        }
    }

    record ScalarStats(
            int min,
            int max,
            double mean,
            double median,
            double sampleStandardDeviation) {
    }

    record JaccardStats(
            int pairs,
            double min,
            double max,
            double mean) {
    }

    record BudgetSummary(
            long budgetMs,
            int repetitions,
            ScalarStats supportStats,
            ScalarStats stateStats,
            JaccardStats pairwiseJaccard,
            Set<String> stableCore,
            Set<String> supportUnion) {

        BudgetSummary {
            supportStats = Objects.requireNonNull(supportStats);
            stateStats = Objects.requireNonNull(stateStats);
            pairwiseJaccard = Objects.requireNonNull(pairwiseJaccard);
            stableCore = Collections.unmodifiableSet(new TreeSet<>(stableCore));
            supportUnion = Collections.unmodifiableSet(
                    new TreeSet<>(supportUnion));
        }
    }

    record BaselineOverlap(
            int intersection,
            int onlyRun,
            int onlyBaseline,
            double jaccard) {
    }

    record Analysis(
            PlateauStatus status,
            Map<Long, BudgetSummary> budgetSummaries,
            Set<String> union20,
            Set<String> union60,
            Set<String> union180,
            Set<String> novel60Vs20,
            Set<String> novel180VsLower,
            Set<String> cumulativeUnion,
            boolean incompleteRun,
            Set<String> excludedRunIds,
            Set<String> wallClockContaminatedRunIds,
            boolean all180Exhausted,
            boolean capped60Or180,
            boolean stable60,
            boolean stable180) {

        Analysis {
            status = Objects.requireNonNull(status);
            budgetSummaries = Collections.unmodifiableMap(
                    new TreeMap<>(budgetSummaries));
            union20 = Collections.unmodifiableSet(new TreeSet<>(union20));
            union60 = Collections.unmodifiableSet(new TreeSet<>(union60));
            union180 = Collections.unmodifiableSet(new TreeSet<>(union180));
            novel60Vs20 = Collections.unmodifiableSet(
                    new TreeSet<>(novel60Vs20));
            novel180VsLower = Collections.unmodifiableSet(
                    new TreeSet<>(novel180VsLower));
            cumulativeUnion = Collections.unmodifiableSet(
                    new TreeSet<>(cumulativeUnion));
            excludedRunIds = Collections.unmodifiableSet(
                    new TreeSet<>(excludedRunIds));
            wallClockContaminatedRunIds = Collections.unmodifiableSet(
                    new TreeSet<>(wallClockContaminatedRunIds));
        }
    }

    private record RunKey(long budgetMs, int repetition)
            implements Comparable<RunKey> {

        private RunKey {
            if (budgetMs <= 0 || repetition <= 0) {
                throw new IllegalArgumentException("Invalid plateau run key");
            }
        }

        private String runId() {
            return "b" + budgetMs + "-r" + repetition;
        }

        @Override
        public int compareTo(RunKey other) {
            int budget = Long.compare(budgetMs, other.budgetMs);
            return budget != 0 ? budget
                    : Integer.compare(repetition, other.repetition);
        }
    }

    private static final class MutableRun {
        private final RunKey key;
        private String runId;
        private boolean declared;
        private final Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                LayerObservation> layers = new EnumMap<>(
                StructuredLocalPatternUniverseBuilder.UniverseScope.class);
        private final Set<String> supports = new TreeSet<>();
        private final Set<String> states = new TreeSet<>();

        private MutableRun(RunKey key) {
            this.key = key;
        }

        private RunObservation freeze() throws IOException {
            if (!declared || runId == null) {
                throw new IOException("Plateau run has no RUN declaration: "
                        + key.runId());
            }
            return new RunObservation(
                    key.budgetMs(),
                    key.repetition(),
                    runId,
                    layers,
                    supports,
                    states);
        }
    }
}
