package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Durable research-side archive for a frozen sparse candidate snapshot. */
final class SupportCandidateSnapshotArchive {

    static final String FORMAT = "support-candidate-snapshot-v1";
    private static final String STATE_PREFIX = "STATE\t";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder()
            .withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private SupportCandidateSnapshotArchive() {
    }

    static Snapshot create(
            String dataset,
            Map<String, String> identityMetadata,
            Map<String, String> producerMetadata,
            SupportBucketedCandidateEvaluator.Snapshot frozen,
            Map<String, ProductionNeutralMoveSearch.FastUpperBound> phase2ByState,
            ValidationContext validation) throws IOException {
        Objects.requireNonNull(frozen, "frozen");
        Objects.requireNonNull(phase2ByState, "phase2ByState");
        Set<String> expected = frozen.staticOrder().stream()
                .map(SupportBucketedCandidateEvaluator.FrozenCandidate
                        ::stateSignature)
                .collect(TreeSet::new, Set::add, Set::addAll);
        if (!expected.equals(new TreeSet<>(phase2ByState.keySet()))) {
            throw new IOException("Phase2 states do not match frozen snapshot");
        }
        List<PersistedCandidate> candidates = frozen.staticOrder().stream()
                .map(state -> new PersistedCandidate(
                        state,
                        Objects.requireNonNull(
                                phase2ByState.get(state.stateSignature()),
                                "missing Phase2 score")))
                .sorted(Comparator.comparing(value ->
                        value.state().stateSignature()))
                .toList();
        Snapshot unhashed = new Snapshot(
                dataset,
                identityMetadata,
                producerMetadata,
                candidates,
                "");
        Snapshot snapshot = new Snapshot(
                dataset,
                identityMetadata,
                producerMetadata,
                candidates,
                payloadSha256(unhashed));
        validate(snapshot, validation);
        return snapshot;
    }

    static Snapshot read(Path path, ValidationContext validation)
            throws IOException {
        Objects.requireNonNull(path, "path");
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> stateLines = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("# ")) {
                int equals = line.indexOf('=');
                if (equals <= 2) {
                    throw new IOException("Malformed snapshot header: " + line);
                }
                String key = line.substring(2, equals);
                String previous = headers.putIfAbsent(
                        key, line.substring(equals + 1));
                if (previous != null) {
                    throw new IOException("Duplicate snapshot header: " + key);
                }
            } else if (line.startsWith(STATE_PREFIX)) {
                stateLines.add(line);
            } else {
                throw new IOException("Unknown snapshot line: " + line);
            }
        }
        if (!FORMAT.equals(headers.get("format"))) {
            throw new IOException("Unsupported candidate snapshot format: "
                    + headers.get("format"));
        }
        String dataset = required(headers, "dataset");
        Map<String, String> identity = metadata(headers, "identity.");
        Map<String, String> producer = metadata(headers, "producer.");
        List<PersistedCandidate> candidates = new ArrayList<>();
        for (String stateLine : stateLines) {
            candidates.add(parseState(stateLine, validation.baseline()));
        }
        candidates.sort(Comparator.comparing(value ->
                value.state().stateSignature()));
        Snapshot snapshot = new Snapshot(
                dataset,
                identity,
                producer,
                candidates,
                required(headers, "payloadSha256"));
        if (integer(headers, "stateCount") != snapshot.candidates().size()) {
            throw new IOException("Snapshot state count does not match payload");
        }
        if (integer(headers, "supportCount") != snapshot.supportCount()) {
            throw new IOException("Snapshot support count does not match payload");
        }
        Map<ProductionNeutralMoveSearch.FastStatus, Integer> statusCounts =
                phase2StatusCounts(snapshot.candidates());
        for (ProductionNeutralMoveSearch.FastStatus status
                : ProductionNeutralMoveSearch.FastStatus.values()) {
            int expected = integer(headers, "phase2." + status.name());
            if (expected != statusCounts.getOrDefault(status, 0)) {
                throw new IOException("Phase2 status count mismatch for " + status);
            }
        }
        validate(snapshot, validation);
        return snapshot;
    }

    static Snapshot write(
            Path path,
            Snapshot snapshot,
            WriteMode mode,
            ValidationContext validation) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        validate(snapshot, validation);
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(
                absolute.getParent(), "snapshot path requires a parent");
        Files.createDirectories(parent);
        if (mode == WriteMode.CREATE && Files.exists(absolute)) {
            throw new FileAlreadyExistsException(absolute.toString());
        }
        Path temporary = Files.createTempFile(
                parent, absolute.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, lines(snapshot), StandardCharsets.UTF_8);
            Snapshot reread = read(temporary, validation);
            if (!snapshot.payloadSha256().equals(reread.payloadSha256())
                    || snapshot.candidates().size()
                    != reread.candidates().size()
                    || !snapshot.dataset().equals(reread.dataset())) {
                throw new IOException(
                        "Candidate snapshot changed during write verification");
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

    static String payloadSha256(Snapshot snapshot) {
        List<String> canonical = new ArrayList<>();
        canonical.add("format=" + token(FORMAT));
        canonical.add("dataset=" + token(snapshot.dataset()));
        new TreeMap<>(snapshot.identityMetadata()).forEach((key, value) ->
                canonical.add("identity." + key + "=" + token(value)));
        snapshot.candidates().stream()
                .sorted(Comparator.comparing(value ->
                        value.state().stateSignature()))
                .map(SupportCandidateSnapshotArchive::stateLine)
                .forEach(canonical::add);
        return sha256OfLines(canonical);
    }

    static String hashMetadata(Map<String, String> metadata) {
        List<String> canonical = new ArrayList<>();
        new TreeMap<>(metadata).forEach((key, value) ->
                canonical.add(token(key) + "=" + token(value)));
        return sha256OfLines(canonical);
    }

    static String sha256OfLines(Iterable<String> values) {
        MessageDigest digest = digest();
        for (String value : values) {
            digest.update(Objects.requireNonNull(value)
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    static String hashDemands(Map<Integer, Integer> demands) {
        return sha256OfLines(new TreeMap<>(demands).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList());
    }

    static String hashPatterns(Iterable<PatternCandidate> patterns) {
        TreeSet<String> signatures = new TreeSet<>();
        patterns.forEach(pattern -> signatures.add(pattern.signature()));
        return sha256OfLines(signatures);
    }

    static String hashStrings(Iterable<String> values) {
        TreeSet<String> sorted = new TreeSet<>();
        values.forEach(sorted::add);
        return sha256OfLines(sorted);
    }

    static String hashFiles(Path root, Iterable<Path> files) throws IOException {
        MessageDigest digest = digest();
        List<Path> sorted = new ArrayList<>();
        files.forEach(sorted::add);
        sorted.sort(Comparator.comparing(path ->
                root.toAbsolutePath().normalize().relativize(
                        path.toAbsolutePath().normalize()).toString()));
        for (Path file : sorted) {
            String relative = root.toAbsolutePath().normalize().relativize(
                    file.toAbsolutePath().normalize()).toString()
                    .replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    static String stateSignature(Map<PatternCandidate, Integer> solution) {
        return solution.entrySet().stream()
                .filter(entry -> entry.getValue() != null
                        && entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(PatternCandidate::signature)))
                .map(entry -> entry.getKey().signature()
                        + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private static void validate(
            Snapshot snapshot,
            ValidationContext validation) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(validation, "validation");
        if (snapshot.dataset().isBlank()) {
            throw new IOException("Snapshot dataset must not be blank");
        }
        if (snapshot.candidates().isEmpty()) {
            throw new IOException("Snapshot candidates must not be empty");
        }
        requireIdentity(snapshot, "generationConfigSha256");
        String expectedConfigHash = hashMetadata(withoutKey(
                snapshot.identityMetadata(), "generationConfigSha256"));
        if (!expectedConfigHash.equals(
                snapshot.identityMetadata().get("generationConfigSha256"))) {
            throw new IOException("Generation config hash mismatch");
        }
        verifyIntegerIdentity(snapshot, "expectedCars", validation.expectedCars());
        verifyIntegerIdentity(snapshot, "expectedWaste", validation.expectedWaste());
        verifyIntegerIdentity(snapshot, "totalWidth", validation.totalWidth());
        verifyOptionalIdentity(
                snapshot, "demandSha256", hashDemands(validation.demands()));
        verifyOptionalIdentity(
                snapshot, "baselineSha256", sha256OfLines(List.of(
                        stateSignature(validation.baseline()))));

        Set<String> states = new TreeSet<>();
        for (PersistedCandidate persisted : snapshot.candidates()) {
            SupportBucketedCandidateEvaluator.FrozenCandidate state =
                    persisted.state();
            if (!states.add(state.stateSignature())) {
                throw new IOException("Duplicate state signature: "
                        + state.stateSignature());
            }
            if (!state.stateSignature().equals(
                    stateSignature(state.candidate().solution()))) {
                throw new IOException("State signature cannot be reconstructed: "
                        + state.stateSignature());
            }
            if (!state.supportIdentity().equals(
                    SupportBucketedCandidateEvaluator.supportIdentity(
                            state.candidate()))) {
                throw new IOException("Support identity mismatch: "
                        + state.stateSignature());
            }
            if (!state.scopes().equals(state.occurrences().stream()
                    .map(SupportBucketedCandidateEvaluator.SourceOccurrence::scope)
                    .collect(() -> EnumSet.noneOf(
                                    StructuredLocalPatternUniverseBuilder
                                            .UniverseScope.class),
                            Set::add,
                            Set::addAll))) {
                throw new IOException("Occurrence scopes mismatch: "
                        + state.stateSignature());
            }
            Map<PatternCandidate, Integer> solution =
                    state.candidate().solution();
            if (!deltaBySignature(validation.baseline(), solution).equals(
                    deltaBySignature(state.candidate().delta()))) {
                throw new IOException("Candidate delta mismatch: "
                        + state.stateSignature());
            }
            int cars = solution.values().stream()
                    .mapToInt(Integer::intValue).sum();
            if (cars != validation.expectedCars()) {
                throw new IOException("Unexpected car count for state: "
                        + state.stateSignature());
            }
            if (!production(solution).equals(validation.demands())) {
                throw new IOException("Demand mismatch for state: "
                        + state.stateSignature());
            }
            int waste = solution.entrySet().stream()
                    .mapToInt(entry -> entry.getKey().getRealWaste(
                                    validation.totalWidth())
                            * entry.getValue())
                    .sum();
            if (waste != validation.expectedWaste()) {
                throw new IOException("Waste mismatch for state: "
                        + state.stateSignature());
            }
        }
        if (!payloadSha256(snapshot).equals(snapshot.payloadSha256())) {
            throw new IOException("Candidate snapshot payload hash mismatch");
        }
    }

    private static PersistedCandidate parseState(
            String line,
            Map<PatternCandidate, Integer> baseline) throws IOException {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 12 || !"STATE".equals(fields[0])) {
            throw new IOException("Malformed candidate state line");
        }
        String stateSignature = decode(fields[1]);
        String supportIdentity = decode(fields[2]);
        EnumSet<StructuredLocalPatternUniverseBuilder.UniverseScope> scopes =
                parseScopes(fields[3]);
        List<SupportBucketedCandidateEvaluator.SourceOccurrence> occurrences =
                parseOccurrences(decode(fields[4]));
        Set<Double> objectives = parseObjectives(decode(fields[5]));
        ProductionNeutralMoveSearch.FastUpperBound phase2;
        try {
            phase2 = new ProductionNeutralMoveSearch.FastUpperBound(
                    ProductionNeutralMoveSearch.FastStatus.valueOf(fields[6]),
                    Integer.parseInt(fields[7]),
                    Integer.parseInt(fields[8]),
                    Integer.parseInt(fields[9]),
                    Long.parseLong(fields[10]));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Phase2 payload", exception);
        }
        if (phase2.targetShapeFeasible()
                != Boolean.parseBoolean(fields[11])) {
            throw new IOException("Phase2 target-shape flag mismatch");
        }
        Map<PatternCandidate, Integer> solution = parseSolution(stateSignature);
        Map<PatternCandidate, Integer> delta = delta(baseline, solution);
        if (occurrences.isEmpty()) {
            throw new IOException("Candidate occurrence list is empty");
        }
        SparseProductionNeutralMoveEnumerator.Candidate candidate =
                new SparseProductionNeutralMoveEnumerator.Candidate(
                        delta,
                        solution,
                        stateSignature,
                        occurrences.get(0).localSupportSignature(),
                        coefficientSignature(delta));
        SupportBucketedCandidateEvaluator.FrozenCandidate frozen =
                new SupportBucketedCandidateEvaluator.FrozenCandidate(
                        candidate,
                        stateSignature,
                        supportIdentity,
                        scopes,
                        occurrences,
                        objectives);
        return new PersistedCandidate(frozen, phase2);
    }

    private static List<String> lines(Snapshot snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add("# format=" + FORMAT);
        lines.add("# dataset=" + snapshot.dataset());
        snapshot.identityMetadata().forEach((key, value) -> lines.add(
                "# identity." + key + "=" + encode(value)));
        snapshot.producerMetadata().forEach((key, value) -> lines.add(
                "# producer." + key + "=" + encode(value)));
        lines.add("# stateCount=" + snapshot.candidates().size());
        lines.add("# supportCount=" + snapshot.supportCount());
        Map<ProductionNeutralMoveSearch.FastStatus, Integer> statusCounts =
                phase2StatusCounts(snapshot.candidates());
        for (ProductionNeutralMoveSearch.FastStatus status
                : ProductionNeutralMoveSearch.FastStatus.values()) {
            lines.add("# phase2." + status.name() + "="
                    + statusCounts.getOrDefault(status, 0));
        }
        lines.add("# payloadSha256=" + snapshot.payloadSha256());
        snapshot.candidates().stream()
                .map(SupportCandidateSnapshotArchive::stateLine)
                .forEach(lines::add);
        return lines;
    }

    private static String stateLine(PersistedCandidate persisted) {
        SupportBucketedCandidateEvaluator.FrozenCandidate state =
                persisted.state();
        ProductionNeutralMoveSearch.FastUpperBound phase2 = persisted.phase2();
        String scopes = state.scopes().stream()
                .sorted()
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String occurrences = state.occurrences().stream()
                .sorted(Comparator
                        .comparing((SupportBucketedCandidateEvaluator
                                .SourceOccurrence value) -> value.scope().name())
                        .thenComparing(SupportBucketedCandidateEvaluator
                                .SourceOccurrence::localSupportSignature)
                        .thenComparing(value -> value.masterObjective()
                                .isPresent()
                                ? Double.toString(value.masterObjective()
                                        .getAsDouble()) : "~"))
                .map(value -> value.scope().name() + ","
                        + encode(value.localSupportSignature()) + ","
                        + (value.masterObjective().isPresent()
                        ? Double.toString(value.masterObjective().getAsDouble())
                        : "~"))
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
        String objectives = state.masterObjectives().stream()
                .sorted()
                .map(value -> Double.toString(value))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return String.join("\t",
                "STATE",
                encode(state.stateSignature()),
                encode(state.supportIdentity()),
                scopes,
                encode(occurrences),
                encode(objectives),
                phase2.status().name(),
                Integer.toString(phase2.groups()),
                Integer.toString(phase2.oddGroups()),
                Integer.toString(phase2.oneGroups()),
                Long.toString(phase2.elapsedMs()),
                Boolean.toString(phase2.targetShapeFeasible()));
    }

    private static Map<ProductionNeutralMoveSearch.FastStatus, Integer>
            phase2StatusCounts(List<PersistedCandidate> candidates) {
        Map<ProductionNeutralMoveSearch.FastStatus, Integer> counts =
                new EnumMap<>(ProductionNeutralMoveSearch.FastStatus.class);
        candidates.forEach(candidate -> counts.merge(
                candidate.phase2().status(), 1, Integer::sum));
        return counts;
    }

    private static EnumSet<StructuredLocalPatternUniverseBuilder.UniverseScope>
            parseScopes(String encoded) throws IOException {
        EnumSet<StructuredLocalPatternUniverseBuilder.UniverseScope> scopes =
                EnumSet.noneOf(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.class);
        if (encoded.isBlank()) {
            throw new IOException("Candidate scopes are empty");
        }
        try {
            for (String value : encoded.split(",")) {
                scopes.add(StructuredLocalPatternUniverseBuilder.UniverseScope
                        .valueOf(value));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid candidate scope", exception);
        }
        return scopes;
    }

    private static List<SupportBucketedCandidateEvaluator.SourceOccurrence>
            parseOccurrences(String encoded) throws IOException {
        List<SupportBucketedCandidateEvaluator.SourceOccurrence> result =
                new ArrayList<>();
        if (encoded.isBlank()) {
            return result;
        }
        for (String part : encoded.split(";")) {
            String[] fields = part.split(",", -1);
            if (fields.length != 3) {
                throw new IOException("Malformed source occurrence");
            }
            try {
                OptionalDouble objective = "~".equals(fields[2])
                        ? OptionalDouble.empty()
                        : OptionalDouble.of(Double.parseDouble(fields[2]));
                result.add(new SupportBucketedCandidateEvaluator
                        .SourceOccurrence(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .valueOf(fields[0]),
                        decode(fields[1]),
                        objective));
            } catch (RuntimeException exception) {
                throw new IOException("Invalid source occurrence", exception);
            }
        }
        return List.copyOf(result);
    }

    private static Set<Double> parseObjectives(String encoded)
            throws IOException {
        Set<Double> objectives = new TreeSet<>();
        if (encoded.isBlank()) {
            return objectives;
        }
        try {
            for (String value : encoded.split(",")) {
                objectives.add(Double.parseDouble(value));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid master objective", exception);
        }
        return objectives;
    }

    private static Map<PatternCandidate, Integer> parseSolution(
            String signature) throws IOException {
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        if (signature.isBlank()) {
            throw new IOException("State signature is empty");
        }
        try {
            for (String part : signature.split(";")) {
                int equals = part.lastIndexOf('=');
                if (equals <= 0) {
                    throw new IllegalArgumentException("missing usage");
                }
                PatternCandidate pattern = pattern(
                        part.substring(0, equals));
                int usage = Integer.parseInt(part.substring(equals + 1));
                if (usage <= 0 || solution.put(pattern, usage) != null) {
                    throw new IllegalArgumentException("invalid usage");
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid state signature", exception);
        }
        return Collections.unmodifiableMap(solution);
    }

    private static PatternCandidate pattern(String signature) {
        int separator = signature.indexOf('|');
        int rollWidth = Integer.parseInt(signature.substring(0, separator));
        Map<Integer, Integer> cuts = new TreeMap<>();
        String encodedCuts = signature.substring(separator + 1);
        if (!encodedCuts.isBlank()) {
            for (String cut : encodedCuts.split(",")) {
                int x = cut.indexOf('x');
                cuts.put(
                        Integer.parseInt(cut.substring(0, x)),
                        Integer.parseInt(cut.substring(x + 1)));
            }
        }
        return new PatternCandidate(cuts, rollWidth);
    }

    private static Map<PatternCandidate, Integer> delta(
            Map<PatternCandidate, Integer> baseline,
            Map<PatternCandidate, Integer> solution) {
        Map<String, PatternCandidate> patterns = indexPatterns(
                baseline.keySet());
        solution.keySet().forEach(pattern -> patterns.putIfAbsent(
                pattern.signature(), pattern));
        Map<String, Integer> baselineUsage = usageBySignature(baseline);
        Map<String, Integer> solutionUsage = usageBySignature(solution);
        Map<PatternCandidate, Integer> delta = new LinkedHashMap<>();
        patterns.forEach((signature, pattern) -> {
            int value = solutionUsage.getOrDefault(signature, 0)
                    - baselineUsage.getOrDefault(signature, 0);
            if (value != 0) {
                delta.put(pattern, value);
            }
        });
        return Collections.unmodifiableMap(delta);
    }

    private static Map<String, Integer> deltaBySignature(
            Map<PatternCandidate, Integer> baseline,
            Map<PatternCandidate, Integer> solution) {
        Map<String, Integer> baselineUsage = usageBySignature(baseline);
        Map<String, Integer> solutionUsage = usageBySignature(solution);
        Set<String> signatures = new TreeSet<>(baselineUsage.keySet());
        signatures.addAll(solutionUsage.keySet());
        Map<String, Integer> delta = new TreeMap<>();
        for (String signature : signatures) {
            int value = solutionUsage.getOrDefault(signature, 0)
                    - baselineUsage.getOrDefault(signature, 0);
            if (value != 0) {
                delta.put(signature, value);
            }
        }
        return delta;
    }

    private static Map<String, Integer> deltaBySignature(
            Map<PatternCandidate, Integer> delta) throws IOException {
        Map<String, Integer> values = new TreeMap<>();
        for (Map.Entry<PatternCandidate, Integer> entry : delta.entrySet()) {
            if (entry.getValue() == null || entry.getValue() == 0) {
                throw new IOException("Delta contains a null or zero coefficient");
            }
            Integer previous = values.put(
                    entry.getKey().signature(), entry.getValue());
            if (previous != null) {
                throw new IOException("Delta contains duplicate pattern signatures");
            }
        }
        return values;
    }

    private static String coefficientSignature(
            Map<PatternCandidate, Integer> delta) {
        return delta.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(PatternCandidate::signature)))
                .map(entry -> entry.getKey().signature()
                        + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private static Map<String, PatternCandidate> indexPatterns(
            Iterable<PatternCandidate> patterns) {
        Map<String, PatternCandidate> bySignature = new TreeMap<>();
        patterns.forEach(pattern -> bySignature.put(
                pattern.signature(), pattern));
        return bySignature;
    }

    private static Map<String, Integer> usageBySignature(
            Map<PatternCandidate, Integer> solution) {
        Map<String, Integer> values = new TreeMap<>();
        solution.forEach((pattern, usage) -> values.put(
                pattern.signature(), usage));
        return values;
    }

    private static Map<Integer, Integer> production(
            Map<PatternCandidate, Integer> solution) {
        Map<Integer, Integer> production = new TreeMap<>();
        solution.forEach((pattern, usage) -> pattern.getPattern().forEach(
                (width, coefficient) -> production.merge(
                        width, coefficient * usage, Integer::sum)));
        return production;
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
            throw new IOException("Missing snapshot metadata section: " + prefix);
        }
        return result;
    }

    private static Map<String, String> withoutKey(
            Map<String, String> source,
            String excluded) {
        Map<String, String> copy = new TreeMap<>(source);
        copy.remove(excluded);
        return copy;
    }

    private static void requireIdentity(Snapshot snapshot, String key)
            throws IOException {
        if (!snapshot.identityMetadata().containsKey(key)) {
            throw new IOException("Missing identity metadata: " + key);
        }
    }

    private static void verifyIntegerIdentity(
            Snapshot snapshot,
            String key,
            int expected) throws IOException {
        requireIdentity(snapshot, key);
        try {
            if (Integer.parseInt(snapshot.identityMetadata().get(key))
                    != expected) {
                throw new IOException("Identity metadata mismatch: " + key);
            }
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid identity integer: " + key, exception);
        }
    }

    private static void verifyOptionalIdentity(
            Snapshot snapshot,
            String key,
            String expected) throws IOException {
        String actual = snapshot.identityMetadata().get(key);
        if (actual != null && !actual.equals(expected)) {
            throw new IOException("Identity metadata mismatch: " + key);
        }
    }

    private static void move(Path temporary, Path target, boolean replace)
            throws IOException {
        StandardCopyOption[] atomic = replace
                ? new StandardCopyOption[]{
                StandardCopyOption.ATOMIC_MOVE,
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

    private static String required(Map<String, String> values, String key)
            throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing snapshot header: " + key);
        }
        return value;
    }

    private static int integer(Map<String, String> values, String key)
            throws IOException {
        try {
            return Integer.parseInt(required(values, key));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid snapshot integer: " + key, exception);
        }
    }

    private static String encode(String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Snapshot values must be one line");
        }
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) throws IOException {
        try {
            return new String(DECODER.decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid snapshot Base64 value", exception);
        }
    }

    private static String token(String value) {
        return value.length() + ":" + value;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    enum WriteMode {
        CREATE,
        REPLACE
    }

    record ValidationContext(
            Map<Integer, Integer> demands,
            Map<PatternCandidate, Integer> baseline,
            int expectedCars,
            int expectedWaste,
            int totalWidth) {

        ValidationContext {
            demands = Collections.unmodifiableMap(new TreeMap<>(demands));
            baseline = Collections.unmodifiableMap(
                    new LinkedHashMap<>(baseline));
            if (demands.isEmpty() || baseline.isEmpty()
                    || expectedCars <= 0 || expectedWaste < 0
                    || totalWidth <= 0) {
                throw new IllegalArgumentException("Invalid validation context");
            }
        }
    }

    record PersistedCandidate(
            SupportBucketedCandidateEvaluator.FrozenCandidate state,
            ProductionNeutralMoveSearch.FastUpperBound phase2) {

        PersistedCandidate {
            state = Objects.requireNonNull(state);
            phase2 = Objects.requireNonNull(phase2);
        }

        SupportBucketedCandidateEvaluator.ScoredCandidate scored() {
            return new SupportBucketedCandidateEvaluator.ScoredCandidate(
                    state, phase2);
        }
    }

    record Snapshot(
            String dataset,
            Map<String, String> identityMetadata,
            Map<String, String> producerMetadata,
            List<PersistedCandidate> candidates,
            String payloadSha256) {

        Snapshot {
            dataset = Objects.requireNonNull(dataset);
            identityMetadata = Collections.unmodifiableMap(
                    new TreeMap<>(identityMetadata));
            producerMetadata = Collections.unmodifiableMap(
                    new TreeMap<>(producerMetadata));
            candidates = candidates.stream()
                    .sorted(Comparator.comparing(value ->
                            value.state().stateSignature()))
                    .toList();
            payloadSha256 = Objects.requireNonNull(payloadSha256);
        }

        int supportCount() {
            return (int) candidates.stream()
                    .map(candidate -> candidate.state().supportIdentity())
                    .distinct()
                    .count();
        }
    }
}
