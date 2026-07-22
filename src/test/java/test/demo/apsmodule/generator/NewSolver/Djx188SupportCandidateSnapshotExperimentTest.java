package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in creation and replay of the durable DJX188 candidate baseline. */
class Djx188SupportCandidateSnapshotExperimentTest {

    private static final Path SNAPSHOT_PATH = Path.of(
            "src", "test", "resources", "research-baselines",
            "djx188-support-candidate-snapshot-v1.tsv");
    private static final long SPARSE_TOTAL_MS = 20_000L;
    private static final long SPARSE_MASTER_MS = 20_000L;
    private static final long SPARSE_SUPPORT_MS = 15_000L;
    private static final long SPARSE_NODE_LIMIT = 200_000L;
    private static final int SPARSE_MAX_SUPPORTS = 200;
    private static final int SPARSE_MAX_COEFFICIENTS = 2_000;
    private static final int SPARSE_MAX_STATES = 500;

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void fixedSnapshotSupportsRepeatableTopThreeExactEvaluation()
            throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("cutting.test.supportCandidateSnapshot"),
                "research-only durable candidate snapshot experiment is opt-in");

        Mode mode = Mode.valueOf(System.getProperty(
                "cutting.test.snapshot.mode", "REPLAY").toUpperCase());
        if (mode == Mode.REPLACE && !Boolean.getBoolean(
                "cutting.test.snapshot.allowReplace")) {
            throw new IllegalStateException(
                    "REPLACE requires cutting.test.snapshot.allowReplace=true");
        }

        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        SolverParameters parameters = Djx188ManualBaselineFixture.parameters();
        Map<PatternCandidate, Integer> baseline =
                Djx188ProductionNeutralFixture.loadAutomatic22();
        SupportCandidateSnapshotArchive.ValidationContext validation =
                new SupportCandidateSnapshotArchive.ValidationContext(
                        demands,
                        baseline,
                        169,
                        36_870,
                        parameters.getTotalWidth());

        boolean generationInvoked = mode == Mode.CREATE || mode == Mode.REPLACE;
        boolean phase2Invoked = generationInvoked;
        SupportCandidateSnapshotArchive.Snapshot snapshot;
        if (generationInvoked) {
            GeneratedSnapshot generated = generate(
                    items, demands, parameters, baseline, validation);
            SupportCandidateSnapshotArchive.WriteMode writeMode =
                    mode == Mode.CREATE
                            ? SupportCandidateSnapshotArchive.WriteMode.CREATE
                            : SupportCandidateSnapshotArchive.WriteMode.REPLACE;
            snapshot = SupportCandidateSnapshotArchive.write(
                    SNAPSHOT_PATH,
                    generated.snapshot(),
                    writeMode,
                    validation);
        } else {
            snapshot = SupportCandidateSnapshotArchive.read(
                    SNAPSHOT_PATH, validation);
        }

        printSnapshot(mode, generationInvoked, phase2Invoked, snapshot);
        assertEquals(169, Integer.parseInt(
                snapshot.identityMetadata().get("expectedCars")));
        assertEquals(36_870, Integer.parseInt(
                snapshot.identityMetadata().get("expectedWaste")));
        assertEquals(
                SupportCandidateSnapshotArchive.hashDemands(demands),
                snapshot.identityMetadata().get("demandSha256"));
        assertEquals(
                SupportCandidateSnapshotArchive.sha256OfLines(List.of(
                        SupportCandidateSnapshotArchive.stateSignature(
                                baseline))),
                snapshot.identityMetadata().get("baselineSha256"));

        if (mode == Mode.VERIFY) {
            assertFalse(generationInvoked);
            assertFalse(phase2Invoked);
            return;
        }

        SupportTopNExactEvaluator.TopNPlan plan =
                SupportTopNExactEvaluator.plan(snapshot, 3);
        printPlan(plan);
        Path exactPath = SupportTopNExactEvaluator.exactResultsPath(
                SNAPSHOT_PATH, snapshot.payloadSha256());
        SupportTopNExactEvaluator.ExactConfig exactConfig =
                new SupportTopNExactEvaluator.ExactConfig(
                        27,
                        1,
                        0,
                        Long.getLong(
                                "cutting.test.snapshot.threshold60Ms", 60_000L),
                        Long.getLong(
                                "cutting.test.snapshot.threshold180Ms", 180_000L),
                        Long.getLong(
                                "cutting.test.snapshot.fullGroupMs", 300_000L),
                        Long.getLong(
                                "cutting.test.snapshot.fullShapeMs", 60_000L),
                        Long.getLong(
                                "cutting.test.snapshot.maxConfigurations",
                                100_000L),
                        Long.getLong(
                                "cutting.test.snapshot.nodeLimit", -1L));
        int exactDepth = Integer.getInteger(
                "cutting.test.snapshot.exactDepth", 3);
        int maxNewStates = Integer.getInteger(
                "cutting.test.snapshot.maxNewExactStates", 0);
        SupportTopNExactEvaluator.ExactRun exact =
                SupportTopNExactEvaluator.evaluateAndPersist(
                        plan,
                        exactDepth,
                        exactPath,
                        exactConfig,
                        maxNewStates,
                        new AnalyzerExactSolver(items, exactConfig),
                        Djx188SupportCandidateSnapshotExperimentTest
                                ::printExactResult);
        printExactRun(exactPath, plan, exact);

        if (mode == Mode.REPLAY) {
            assertFalse(generationInvoked,
                    "REPLAY must not invoke sparse candidate generation");
            assertFalse(phase2Invoked,
                    "REPLAY must not invoke Phase2");
        }
        assertEquals(snapshot.candidates().size(), plan.totalStates());
        assertEquals(snapshot.supportCount(), plan.totalSupports());
    }

    private static GeneratedSnapshot generate(
            List<SolverOrderItem> items,
            Map<Integer, Integer> demands,
            SolverParameters parameters,
            Map<PatternCandidate, Integer> baseline,
            SupportCandidateSnapshotArchive.ValidationContext validation)
            throws Exception {
        long startedAt = System.currentTimeMillis();
        List<PatternCandidate> universe =
                new CompletePatternEnumerator(parameters, 5).generate(demands);
        Map<PatternCandidate, Integer> upper =
                Djx188ProductionNeutralFixture.upperBounds(
                        universe, demands, 169);
        assertEquals(7_717, universe.size());
        assertEquals(22, baseline.size());

        OrderCompatibilityKernelAnalyzer.Options baselineAnalysisOptions =
                new OrderCompatibilityKernelAnalyzer.Options(
                        180_000L,
                        60_000L,
                        100_000L,
                        -1L,
                        false);
        OrderCompatibilityKernelAnalyzer.Analysis baselineAnalysis =
                OrderCompatibilityKernelAnalyzer.analyze(
                        baseline, items, baselineAnalysisOptions);
        assertEquals(OrderCompatibilityKernelAnalyzer.Status.OPTIMAL,
                baselineAnalysis.status());
        assertTrue(baselineAnalysis.groupOptimal());
        assertTrue(baselineAnalysis.shapeOptimal());
        assertEquals(28, baselineAnalysis.exactMinimumGroups());
        assertEquals(6, baselineAnalysis.exactExtraGroups());
        assertEquals(1, baselineAnalysis.oddGroups());
        assertEquals(0, baselineAnalysis.oneGroups());
        assertEquals(6, baselineAnalysis.splitWitnesses().size());

        StructuredLocalPatternUniverseBuilder.Options buildOptions =
                new StructuredLocalPatternUniverseBuilder.Options(
                        180_000L,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        200_000,
                        parameters.getTotalWidth(),
                        169,
                        36_870,
                        System::currentTimeMillis);
        StructuredLocalPatternUniverseBuilder.BuildResult build =
                StructuredLocalPatternUniverseBuilder.build(
                        universe,
                        baseline,
                        upper,
                        baselineAnalysis,
                        buildOptions);
        assertLayerAnchors(build);

        UnitTwoForTwoMoveEnumerator.Result completeUnit =
                UnitTwoForTwoMoveEnumerator.enumerate(
                        universe, baseline, upper);
        assertTrue(completeUnit.exhausted());
        Set<String> excludedStates = completeUnit.candidates().stream()
                .map(UnitTwoForTwoMoveEnumerator.Candidate::stateSignature)
                .collect(TreeSet::new, Set::add, Set::addAll);
        Set<String> preferredPatterns = baselineAnalysis.splitWitnesses().stream()
                .map(OrderCompatibilityKernelAnalyzer.PatternSplit
                        ::patternSignature)
                .collect(TreeSet::new, Set::add, Set::addAll);
        Set<Integer> preferredWidths = baselineAnalysis.splitWitnesses().stream()
                .flatMap(split -> split.varyingWidths().stream())
                .collect(TreeSet::new, Set::add, Set::addAll);

        SparseProductionNeutralMoveEnumerator.Options sparseOptions =
                new SparseProductionNeutralMoveEnumerator.Options(
                        SPARSE_TOTAL_MS,
                        SPARSE_MASTER_MS,
                        SPARSE_SUPPORT_MS,
                        SPARSE_NODE_LIMIT,
                        SPARSE_MAX_SUPPORTS,
                        SPARSE_MAX_COEFFICIENTS,
                        SPARSE_MAX_STATES,
                        preferredPatterns,
                        preferredWidths);
        Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                SparseProductionNeutralMoveEnumerator.Result> sparseRuns =
                new EnumMap<>(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.class);
        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            StructuredLocalPatternUniverseBuilder.LocalPatternUniverse layer =
                    build.layer(scope);
            Map<PatternCandidate, Integer> localUpper = new LinkedHashMap<>();
            layer.patterns().forEach(pattern -> localUpper.put(
                    pattern,
                    upper.get(pattern)));
            SparseProductionNeutralMoveEnumerator.Result sparse =
                    SparseProductionNeutralMoveEnumerator.enumerate(
                            layer.patterns(),
                            baseline,
                            localUpper,
                            excludedStates,
                            sparseOptions);
            sparseRuns.put(scope, sparse);
            System.out.printf(
                    "DJX188 SNAPSHOT-GENERATE scope=%s patterns=%d status=%s "
                            + "supports=%d states=%d masterNodes=%d "
                            + "subNodes=%d elapsedMs=%d%n",
                    scope,
                    layer.patterns().size(),
                    sparse.status(),
                    sparse.metrics().supportsVisited(),
                    sparse.metrics().uniqueStates(),
                    sparse.metrics().masterNodes(),
                    sparse.metrics().subproblemNodes(),
                    sparse.metrics().totalElapsedMs());
        }

        SupportBucketedCandidateEvaluator.Snapshot frozen =
                SupportBucketedCandidateEvaluator.freeze(sparseRuns, 232);
        Map<String, ProductionNeutralMoveSearch.FastUpperBound> phase2 =
                evaluateAllPhase2(frozen, items, parameters);
        Map<String, String> identity = identityMetadata(
                demands,
                parameters,
                baseline,
                universe,
                build,
                excludedStates,
                preferredPatterns,
                preferredWidths,
                baselineAnalysisOptions,
                buildOptions,
                sparseOptions);
        Map<String, String> producer = producerMetadata(
                System.currentTimeMillis() - startedAt);
        SupportCandidateSnapshotArchive.Snapshot snapshot =
                SupportCandidateSnapshotArchive.create(
                        "DJX188",
                        identity,
                        producer,
                        frozen,
                        phase2,
                        validation);
        return new GeneratedSnapshot(snapshot);
    }

    private static Map<String, ProductionNeutralMoveSearch.FastUpperBound>
            evaluateAllPhase2(
                    SupportBucketedCandidateEvaluator.Snapshot frozen,
                    List<SolverOrderItem> items,
                    SolverParameters parameters) {
        Map<String, ProductionNeutralMoveSearch.FastUpperBound> result =
                new LinkedHashMap<>();
        long startedAt = System.currentTimeMillis();
        for (int index = 0; index < frozen.staticOrder().size(); index++) {
            SupportBucketedCandidateEvaluator.FrozenCandidate candidate =
                    frozen.staticOrder().get(index);
            ProductionNeutralMoveSearch.FastUpperBound phase2 =
                    ProductionNeutralMoveSearch.fastUpperBound(
                            candidate.candidate().solution(), items, parameters);
            result.put(candidate.stateSignature(), phase2);
            int completed = index + 1;
            if (completed % 10 == 0
                    || completed == frozen.staticOrder().size()) {
                System.out.printf(
                        "DJX188 SNAPSHOT-PHASE2 progress=%d/%d status=%s "
                                + "groups=%d odd=%d one=%d lastMs=%d "
                                + "wallMs=%d%n",
                        completed,
                        frozen.staticOrder().size(),
                        phase2.status(),
                        phase2.groups(),
                        phase2.oddGroups(),
                        phase2.oneGroups(),
                        phase2.elapsedMs(),
                        System.currentTimeMillis() - startedAt);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> identityMetadata(
            Map<Integer, Integer> demands,
            SolverParameters parameters,
            Map<PatternCandidate, Integer> baseline,
            List<PatternCandidate> universe,
            StructuredLocalPatternUniverseBuilder.BuildResult build,
            Set<String> excludedStates,
            Set<String> preferredPatterns,
            Set<Integer> preferredWidths,
            OrderCompatibilityKernelAnalyzer.Options analysisOptions,
            StructuredLocalPatternUniverseBuilder.Options buildOptions,
            SparseProductionNeutralMoveEnumerator.Options sparseOptions) {
        Map<String, String> values = new TreeMap<>();
        values.put("expectedCars", "169");
        values.put("expectedWaste", "36870");
        values.put("totalWidth", Integer.toString(parameters.getTotalWidth()));
        values.put("demandSha256",
                SupportCandidateSnapshotArchive.hashDemands(demands));
        values.put("baselineSha256",
                SupportCandidateSnapshotArchive.sha256OfLines(List.of(
                        SupportCandidateSnapshotArchive.stateSignature(
                                baseline))));
        values.put("completeUniverseSha256",
                SupportCandidateSnapshotArchive.hashPatterns(universe));
        values.put("patternEnumeratorMaxParts", "5");
        values.put("minRollWidth",
                Integer.toString(parameters.getMinRollWidth()));
        values.put("maxRollWidth",
                Integer.toString(parameters.getMaxRollWidth()));
        values.put("rollWidthStep",
                Integer.toString(parameters.getStepSize()));
        values.put("maxDistinctWidths",
                Integer.toString(parameters.getMaxDistinctWidths()));
        values.put("totalOverCap",
                Integer.toString(parameters.getTotalOverCap()));
        values.put("topK", Integer.toString(parameters.getTopK()));
        values.put("forceAllowOverWidthsSha256",
                SupportCandidateSnapshotArchive.hashStrings(
                        parameters.getForceAllowOverWidths().stream()
                                .map(String::valueOf).toList()));
        values.put("underPenalty",
                Double.toString(parameters.getUnderPenalty()));
        values.put("maxIterations",
                Integer.toString(parameters.getMaxIterations()));
        values.put("maxPatterns",
                Integer.toString(parameters.getMaxPatterns()));
        values.put("timeoutMs",
                Long.toString(parameters.getTimeoutMs()));
        values.put("stage4TimeLimit",
                Long.toString(parameters.getStage4TimeLimit()));
        values.put("aLayerScipSeed",
                Integer.toString(parameters.getALayerScipSeed()));
        values.put("aLayerAlignmentLambda",
                Double.toString(parameters.getALayerAlignmentLambda()));
        values.put("seqGroupAlpha",
                Double.toString(parameters.getSeqGroupAlpha()));
        values.put("seqGroupBeta",
                Double.toString(parameters.getSeqGroupBeta()));
        values.put("useOptimizedAssignment",
                Boolean.toString(parameters.isUseOptimizedAssignment()));
        values.put("witnessDirectUniverseSha256",
                SupportCandidateSnapshotArchive.hashPatterns(
                        build.layer(StructuredLocalPatternUniverseBuilder
                                .UniverseScope.WITNESS_DIRECT).patterns()));
        values.put("neutralClosureUniverseSha256",
                SupportCandidateSnapshotArchive.hashPatterns(
                        build.layer(StructuredLocalPatternUniverseBuilder
                                .UniverseScope.NEUTRAL_CLOSURE).patterns()));
        values.put("transferBridgeUniverseSha256",
                SupportCandidateSnapshotArchive.hashPatterns(
                        build.layer(StructuredLocalPatternUniverseBuilder
                                .UniverseScope.TRANSFER_BRIDGE_1_HOP).patterns()));
        values.put("universeRules",
                "WITNESS_DIRECT,NEUTRAL_CLOSURE,TRANSFER_BRIDGE_1_HOP");
        values.put("excludedUnitStateCount",
                Integer.toString(excludedStates.size()));
        values.put("excludedUnitStatesSha256",
                SupportCandidateSnapshotArchive.hashStrings(excludedStates));
        values.put("preferredPatternsSha256",
                SupportCandidateSnapshotArchive.hashStrings(preferredPatterns));
        values.put("preferredWidthsSha256",
                SupportCandidateSnapshotArchive.hashStrings(
                        preferredWidths.stream().map(String::valueOf).toList()));
        values.put("baselineGroupMs",
                Long.toString(analysisOptions.groupTimeLimitMs()));
        values.put("baselineShapeMs",
                Long.toString(analysisOptions.shapeTimeLimitMs()));
        values.put("baselineMaxConfigurations",
                Long.toString(analysisOptions.maxConfigurations()));
        values.put("baselineNodeLimit",
                Long.toString(analysisOptions.nodeLimit()));
        values.put("baselineAllowCbcFallback",
                Boolean.toString(analysisOptions.allowCbcFallback()));
        values.put("buildMs", Long.toString(buildOptions.timeLimitMs()));
        values.put("buildMaxPatterns",
                Integer.toString(buildOptions.maxPatterns()));
        values.put("buildMaxEquations",
                Integer.toString(buildOptions.maxEquations()));
        values.put("buildMaxBridgePaths",
                Integer.toString(buildOptions.maxBridgePaths()));
        values.put("buildExpectedCars",
                Integer.toString(buildOptions.expectedCars()));
        values.put("buildExpectedWaste",
                Integer.toString(buildOptions.expectedWaste()));
        values.put("sparseTotalMs",
                Long.toString(sparseOptions.totalTimeLimitMs()));
        values.put("sparseMasterMs",
                Long.toString(sparseOptions.masterSolveTimeLimitMs()));
        values.put("sparseSupportMs",
                Long.toString(sparseOptions.subproblemTimeLimitMs()));
        values.put("sparseNodeLimit",
                Long.toString(sparseOptions.nodeLimit()));
        values.put("sparseMaxSupports",
                Integer.toString(sparseOptions.maxSupports()));
        values.put("sparseMaxCoefficients",
                Integer.toString(sparseOptions.maxCoefficientSolutions()));
        values.put("sparseMaxStates",
                Integer.toString(sparseOptions.maxUniqueStates()));
        values.put("scipThreads", "1");
        values.put("scipRandomSeedShift", "0");
        values.put("scipPermutationSeed", "0");
        values.put("scipLpSeed", "0");
        values.put("generationConfigSha256",
                SupportCandidateSnapshotArchive.hashMetadata(values));
        return Map.copyOf(values);
    }

    private static Map<String, String> producerMetadata(long elapsedMs)
            throws IOException {
        Path root = Path.of("").toAbsolutePath().normalize();
        List<Path> relevant = List.of(
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "Djx188SupportCandidateSnapshotExperimentTest.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "Djx188ManualBaselineFixture.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "Djx188ProductionNeutralFixture.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "SupportCandidateSnapshotArchive.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "SupportBucketedCandidateEvaluator.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "StructuredLocalPatternUniverseBuilder.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "SparseProductionNeutralMoveEnumerator.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "UnitTwoForTwoMoveEnumerator.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "OrderCompatibilityKernelAnalyzer.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/NewSolver/"
                        + "ProductionNeutralMoveSearch.java"),
                root.resolve("src/main/java/test/demo/apsmodule/generator/NewSolver/"
                        + "mip/Phase2SequenceGroupSolver.java"),
                root.resolve("src/main/java/test/demo/apsmodule/generator/NewSolver/"
                        + "mip/AssignmentMIPSolver.java"),
                root.resolve("src/main/java/test/demo/apsmodule/generator/NewSolver/"
                        + "pattern/CompletePatternEnumerator.java"),
                root.resolve("src/main/java/test/demo/apsmodule/generator/NewSolver/"
                        + "model/PatternCandidate.java"));
        for (Path path : relevant) {
            if (!Files.isRegularFile(path)) {
                throw new IOException("Relevant source is missing: " + path);
            }
        }
        Map<String, String> values = new TreeMap<>();
        values.put("createdAtUtc", Instant.now().toString());
        values.put("producerGitHead", git(root, "rev-parse", "HEAD"));
        values.put("producerWorktreeDirty",
                Boolean.toString(!git(root, "status", "--porcelain").isBlank()));
        values.put("producerRelevantSourceSha256",
                SupportCandidateSnapshotArchive.hashFiles(root, relevant));
        values.put("javaVersion", System.getProperty("java.version"));
        values.put("ortoolsVersion", "9.10.4067");
        values.put("solver", "SCIP");
        values.put("generationElapsedMs", Long.toString(elapsedMs));
        return Map.copyOf(values);
    }

    private static String git(Path root, String... arguments)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .trim();
        }
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("git command failed: " + command
                        + " output=" + output);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("git command interrupted", interrupted);
        }
        return output;
    }

    private static void assertLayerAnchors(
            StructuredLocalPatternUniverseBuilder.BuildResult build) {
        assertEquals(252, build.metrics().completeBaselineEquations());
        assertEquals(252, build.metrics().processedBaselineEquations());
        assertEquals(93, build.metrics().directRepairPatterns());
        assertEquals(1_044, build.metrics().eligibleBridgeEquations());
        assertEquals(121,
                build.layer(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.WITNESS_DIRECT).patterns().size());
        assertEquals(370,
                build.layer(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.NEUTRAL_CLOSURE).patterns().size());
        assertEquals(1_050,
                build.layer(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.TRANSFER_BRIDGE_1_HOP).patterns().size());
        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            assertEquals(StructuredLocalPatternUniverseBuilder
                            .UniverseBuildStatus.EXHAUSTED_WITHIN_RULE,
                    build.layer(scope).buildStatus());
        }
    }

    private static void printSnapshot(
            Mode mode,
            boolean generationInvoked,
            boolean phase2Invoked,
            SupportCandidateSnapshotArchive.Snapshot snapshot) {
        System.out.printf(
                "DJX188 SNAPSHOT-IDENTITY mode=%s snapshotSource=%s "
                        + "generationInvoked=%s phase2Invoked=%s path=%s "
                        + "payloadSha256=%s generationConfigSha256=%s "
                        + "states=%d supports=%d%n",
                mode,
                mode == Mode.CREATE || mode == Mode.REPLACE
                        ? "CREATE" : "REPLAY",
                generationInvoked,
                phase2Invoked,
                SNAPSHOT_PATH.toAbsolutePath().normalize(),
                snapshot.payloadSha256(),
                snapshot.identityMetadata().get("generationConfigSha256"),
                snapshot.candidates().size(),
                snapshot.supportCount());
        System.out.println(
                "DJX188 SNAPSHOT-HISTORY old232x17=HISTORICAL_ONLY "
                        + "old24x232Coverage=NOT_MERGEABLE newDenominator="
                        + snapshot.candidates().size());
    }

    private static void printPlan(
            SupportTopNExactEvaluator.TopNPlan plan) {
        for (SupportTopNExactEvaluator.DepthPlan depth : plan.depths()) {
            System.out.printf(
                    "DJX188 SNAPSHOT-TOPN depth=%d supportsWithRank=%d/%d "
                            + "newStates=%d cumulativeStates=%d/%d "
                            + "coverage=%.2f%%%n",
                    depth.depth(),
                    depth.supportsWithRank(),
                    plan.totalSupports(),
                    depth.newCandidates().size(),
                    depth.cumulativeCandidates().size(),
                    depth.stateDenominator(),
                    100.0 * depth.cumulativeCandidates().size()
                            / depth.stateDenominator());
        }
        for (int rank = 1; rank <= plan.maxDepth(); rank++) {
            int currentRank = rank;
            List<SupportTopNExactEvaluator.RankedCandidate> candidates =
                    plan.buckets().values().stream()
                            .filter(bucket -> bucket.size() >= currentRank)
                            .map(bucket -> bucket.get(currentRank - 1))
                            .toList();
            Map<Integer, Integer> groups = new TreeMap<>();
            Map<String, Integer> statuses = new TreeMap<>();
            int targetShape = 0;
            for (SupportTopNExactEvaluator.RankedCandidate candidate
                    : candidates) {
                ProductionNeutralMoveSearch.FastUpperBound phase2 =
                        candidate.candidate().phase2();
                groups.merge(phase2.groups(), 1, Integer::sum);
                statuses.merge(phase2.status().name(), 1, Integer::sum);
                if (phase2.targetShapeFeasible()) {
                    targetShape++;
                }
            }
            System.out.printf(
                    "  TOPN-RANK rank=%d states=%d statuses=%s "
                            + "targetShape=%d groupFrequency=%s%n",
                    rank,
                    candidates.size(),
                    statuses,
                    targetShape,
                    groups);
        }
    }

    private static void printExactResult(
            SupportTopNExactEvaluator.ExactResult result) {
        SupportTopNExactEvaluator.ThresholdObservation decisive =
                result.secondThreshold() == null
                        ? result.firstThreshold()
                        : result.secondThreshold();
        System.out.printf(
                "DJX188 SNAPSHOT-EXACT state=%s supportHash=%s rank=%d "
                        + "first=%s second=%s full=%s lower=%d exact=%d "
                        + "classification=%s%n",
                result.stateSignature(),
                Integer.toHexString(result.supportIdentity().hashCode()),
                result.rank(),
                result.firstThreshold().status(),
                result.secondThreshold() == null
                        ? "SKIPPED" : result.secondThreshold().status(),
                result.fullAnalysis() == null
                        ? "SKIPPED" : result.fullAnalysis().status(),
                result.fullAnalysis() == null
                        ? -1 : result.fullAnalysis().provenGroupLowerBound(),
                result.fullAnalysis() == null
                        ? -1 : result.fullAnalysis().exactMinimumGroups(),
                result.classification());
        assertTrue(decisive.totalElapsedMs() >= 0);
    }

    private static void printExactRun(
            Path exactPath,
            SupportTopNExactEvaluator.TopNPlan plan,
            SupportTopNExactEvaluator.ExactRun run) {
        System.out.printf(
                "DJX188 SNAPSHOT-EXACT-SIDECAR path=%s payloadSha256=%s "
                        + "new=%d checkedStates=%d/%d coverage=%.2f%%%n",
                exactPath.toAbsolutePath().normalize(),
                run.exactResults().payloadSha256(),
                run.newlyEvaluated(),
                run.checkedUniqueStates(),
                run.stateDenominator(),
                100.0 * run.checkedUniqueStates() / run.stateDenominator());
        run.depthSummaries().forEach(summary -> System.out.printf(
                "  EXACT-DEPTH depth=%d supportsWithRank=%d "
                        + "newPlanned=%d cumulativePlanned=%d "
                        + "evaluated=%d target=%d provenNot=%d unknown=%d "
                        + "stateDenominator=%d%n",
                summary.depth(),
                summary.supportsWithRank(),
                summary.newlyPlannedStates(),
                summary.cumulativePlannedStates(),
                summary.evaluatedStates(),
                summary.targetFeasible(),
                summary.provenNotTarget(),
                summary.unknown(),
                summary.stateDenominator()));
        Map<Integer, Map<String, Integer>> classificationsByRank =
                new TreeMap<>();
        run.exactResults().byState().values().forEach(result ->
                classificationsByRank
                        .computeIfAbsent(result.rank(), ignored ->
                                new TreeMap<>())
                        .merge(result.classification().name(), 1, Integer::sum));
        classificationsByRank.forEach((rank, classifications) ->
                System.out.printf(
                        "  EXACT-RANK rank=%d classifications=%s%n",
                        rank,
                        classifications));

        int targetReversals = 0;
        int exactGroupReversals = 0;
        for (List<SupportTopNExactEvaluator.RankedCandidate> bucket
                : plan.buckets().values()) {
            SupportTopNExactEvaluator.ExactResult first =
                    run.exactResults().byState().get(
                            bucket.get(0).stateSignature());
            if (first == null) {
                continue;
            }
            for (int index = 1; index < Math.min(3, bucket.size()); index++) {
                SupportTopNExactEvaluator.ExactResult deeper =
                        run.exactResults().byState().get(
                                bucket.get(index).stateSignature());
                if (deeper == null) {
                    continue;
                }
                if (first.classification()
                        != SupportTopNExactEvaluator.FinalClassification
                                .TARGET_FEASIBLE
                        && deeper.classification()
                        == SupportTopNExactEvaluator.FinalClassification
                                .TARGET_FEASIBLE) {
                    targetReversals++;
                }
                int firstGroups = exactGroups(first);
                int deeperGroups = exactGroups(deeper);
                if (firstGroups > 0 && deeperGroups > 0
                        && deeperGroups < firstGroups) {
                    exactGroupReversals++;
                }
            }
        }
        System.out.printf(
                "DJX188 SNAPSHOT-RANK-DIAGNOSTIC targetReversals=%d "
                        + "exactGroupReversals=%d%n",
                targetReversals,
                exactGroupReversals);
    }

    private static int exactGroups(
            SupportTopNExactEvaluator.ExactResult result) {
        if (result.fullAnalysis() != null
                && result.fullAnalysis().exactMinimumGroups() > 0) {
            return result.fullAnalysis().exactMinimumGroups();
        }
        if (result.firstThreshold().status()
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE) {
            return result.firstThreshold().feasibleGroups();
        }
        if (result.secondThreshold() != null
                && result.secondThreshold().status()
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE) {
            return result.secondThreshold().feasibleGroups();
        }
        return -1;
    }

    private enum Mode {
        CREATE,
        REPLACE,
        REPLAY,
        VERIFY
    }

    private record GeneratedSnapshot(
            SupportCandidateSnapshotArchive.Snapshot snapshot) {
    }

    private static final class AnalyzerExactSolver
            implements SupportTopNExactEvaluator.ExactSolver {

        private final List<SolverOrderItem> items;
        private final SupportTopNExactEvaluator.ExactConfig config;

        private AnalyzerExactSolver(
                List<SolverOrderItem> items,
                SupportTopNExactEvaluator.ExactConfig config) {
            this.items = List.copyOf(items);
            this.config = config;
        }

        @Override
        public SupportTopNExactEvaluator.ThresholdObservation checkThreshold(
                SupportTopNExactEvaluator.RankedCandidate candidate,
                long timeLimitMs) {
            OrderCompatibilityKernelAnalyzer.ThresholdAnalysis result =
                    OrderCompatibilityKernelAnalyzer.checkThreshold(
                            candidate.candidate().state().candidate().solution(),
                            items,
                            config.maxGroups(),
                            config.exactOddGroups(),
                            config.exactOneGroups(),
                            new OrderCompatibilityKernelAnalyzer.Options(
                                    timeLimitMs,
                                    0L,
                                    config.maxConfigurations(),
                                    config.nodeLimit(),
                                    false));
            return SupportTopNExactEvaluator.ThresholdObservation.from(result);
        }

        @Override
        public SupportTopNExactEvaluator.FullObservation analyze(
                SupportTopNExactEvaluator.RankedCandidate candidate,
                long groupTimeLimitMs,
                long shapeTimeLimitMs) {
            OrderCompatibilityKernelAnalyzer.Analysis result =
                    OrderCompatibilityKernelAnalyzer.analyze(
                            candidate.candidate().state().candidate().solution(),
                            items,
                            new OrderCompatibilityKernelAnalyzer.Options(
                                    groupTimeLimitMs,
                                    shapeTimeLimitMs,
                                    config.maxConfigurations(),
                                    config.nodeLimit(),
                                    false));
            return SupportTopNExactEvaluator.FullObservation.from(result);
        }
    }
}
