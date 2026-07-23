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

/** Opt-in support-generation variance and budget-plateau probe for DJX188. */
class Djx188SupportGenerationPlateauExperimentTest {

    private static final String FIXED_SNAPSHOT_SHA256 =
            "52ebe9a752b827cd3cea64a7da70cb042224ce8f7a6652c8f73a0af1a7f671e3";
    private static final Path SNAPSHOT_PATH = Path.of(
            "src", "test", "resources", "research-baselines",
            "djx188-support-candidate-snapshot-v1.tsv");
    private static final Path PLATEAU_PATH = Path.of(
            "src", "test", "resources", "research-baselines",
            "djx188-support-generation-plateau-v1.tsv");
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
    void fixedThreeLayerProbeMeasuresSupportGenerationPlateau()
            throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("cutting.test.supportGenerationPlateau"),
                "research-only support plateau probe is opt-in");

        Mode mode = Mode.valueOf(System.getProperty(
                "cutting.test.plateau.mode", "REPLAY").toUpperCase());
        if (mode == Mode.REPLACE && !Boolean.getBoolean(
                "cutting.test.plateau.allowReplace")) {
            throw new IllegalStateException(
                    "REPLACE requires cutting.test.plateau.allowReplace=true");
        }

        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        SolverParameters parameters = Djx188ManualBaselineFixture.parameters();
        Map<PatternCandidate, Integer> baseline =
                Djx188ProductionNeutralFixture.loadAutomatic22();
        SupportCandidateSnapshotArchive.ValidationContext snapshotValidation =
                new SupportCandidateSnapshotArchive.ValidationContext(
                        demands,
                        baseline,
                        169,
                        36_870,
                        parameters.getTotalWidth());
        SupportCandidateSnapshotArchive.Snapshot fixedSnapshot =
                SupportCandidateSnapshotArchive.read(
                        SNAPSHOT_PATH, snapshotValidation);
        assertEquals(FIXED_SNAPSHOT_SHA256, fixedSnapshot.payloadSha256());
        assertEquals(316, fixedSnapshot.candidates().size());
        assertEquals(26, fixedSnapshot.supportCount());
        Set<String> fixedSupports = fixedSnapshot.candidates().stream()
                .map(candidate -> candidate.state().supportIdentity())
                .collect(TreeSet::new, Set::add, Set::addAll);

        SupportGenerationPlateauArchive.ValidationContext plateauValidation =
                new SupportGenerationPlateauArchive.ValidationContext(
                        "DJX188",
                        fixedSnapshot.payloadSha256(),
                        fixedSupports,
                        SupportGenerationPlateauArchive.DEFAULT_PLAN);
        Path exactPath = SupportTopNExactEvaluator.exactResultsPath(
                SNAPSHOT_PATH, fixedSnapshot.payloadSha256());
        List<Path> protectedEvidence = List.of(
                SNAPSHOT_PATH,
                exactPath,
                Djx188Automatic22CompatibilityFixture.PATH);
        String protectedBefore = SupportCandidateSnapshotArchive.hashFiles(
                Path.of("").toAbsolutePath().normalize(), protectedEvidence);

        boolean generationInvoked = mode == Mode.CREATE || mode == Mode.REPLACE;
        boolean phase2Invoked = false;
        boolean exactInvoked = false;
        SupportGenerationPlateauArchive.Report report;
        if (generationInvoked) {
            GeneratedRuns generated = generate(
                    demands,
                    parameters,
                    baseline,
                    fixedSnapshot);
            SupportGenerationPlateauArchive.Report created =
                    SupportGenerationPlateauArchive.create(
                            "DJX188",
                            fixedSnapshot.payloadSha256(),
                            generated.identityMetadata(),
                            producerMetadata(generated.elapsedMs()),
                            fixedSupports,
                            generated.runs(),
                            plateauValidation);
            SupportGenerationPlateauArchive.WriteMode writeMode =
                    mode == Mode.CREATE
                            ? SupportGenerationPlateauArchive.WriteMode.CREATE
                            : SupportGenerationPlateauArchive.WriteMode.REPLACE;
            report = SupportGenerationPlateauArchive.write(
                    PLATEAU_PATH,
                    created,
                    writeMode,
                    plateauValidation);
        } else {
            report = SupportGenerationPlateauArchive.read(
                    PLATEAU_PATH, plateauValidation);
        }

        String protectedAfter = SupportCandidateSnapshotArchive.hashFiles(
                Path.of("").toAbsolutePath().normalize(), protectedEvidence);
        assertEquals(protectedBefore, protectedAfter,
                "plateau probe changed protected candidate evidence");
        printReport(mode, generationInvoked, phase2Invoked, exactInvoked, report);

        assertFalse(phase2Invoked);
        assertFalse(exactInvoked);
        assertEquals(FIXED_SNAPSHOT_SHA256,
                report.baselineSnapshotPayloadSha256());
        assertEquals(7, report.runs().size());
        if (!generationInvoked) {
            assertFalse(generationInvoked);
        }
    }

    private static GeneratedRuns generate(
            Map<Integer, Integer> demands,
            SolverParameters parameters,
            Map<PatternCandidate, Integer> baseline,
            SupportCandidateSnapshotArchive.Snapshot fixedSnapshot)
            throws Exception {
        long startedAt = System.currentTimeMillis();
        List<PatternCandidate> universe =
                new CompletePatternEnumerator(parameters, 5).generate(demands);
        Map<PatternCandidate, Integer> upper =
                Djx188ProductionNeutralFixture.upperBounds(
                        universe, demands, 169);
        assertEquals(7_717, universe.size());
        assertEquals(22, baseline.size());

        OrderCompatibilityKernelAnalyzer.Analysis fixture =
                Djx188Automatic22CompatibilityFixture.load(baseline);
        assertEquals(6, fixture.splitWitnesses().size());
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
                        universe, baseline, upper, fixture, buildOptions);
        assertLayerAnchors(build, fixedSnapshot);

        UnitTwoForTwoMoveEnumerator.Result completeUnit =
                UnitTwoForTwoMoveEnumerator.enumerate(
                        universe, baseline, upper);
        assertTrue(completeUnit.exhausted());
        Set<String> excludedStates = completeUnit.candidates().stream()
                .map(UnitTwoForTwoMoveEnumerator.Candidate::stateSignature)
                .collect(TreeSet::new, Set::add, Set::addAll);
        Set<String> preferredPatterns = fixture.splitWitnesses().stream()
                .map(OrderCompatibilityKernelAnalyzer.PatternSplit
                        ::patternSignature)
                .collect(TreeSet::new, Set::add, Set::addAll);
        Set<Integer> preferredWidths = fixture.splitWitnesses().stream()
                .flatMap(split -> split.varyingWidths().stream())
                .collect(TreeSet::new, Set::add, Set::addAll);

        List<SupportGenerationPlateauArchive.RunObservation> runs =
                new ArrayList<>();
        for (BudgetRun budgetRun : budgetRuns()) {
            SparseProductionNeutralMoveEnumerator.Options options =
                    new SparseProductionNeutralMoveEnumerator.Options(
                            budgetRun.budgetMs(),
                            budgetRun.budgetMs(),
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
            Map<StructuredLocalPatternUniverseBuilder.UniverseScope,
                    SupportGenerationPlateauArchive.LayerObservation> layers =
                    new EnumMap<>(StructuredLocalPatternUniverseBuilder
                            .UniverseScope.class);
            for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                    : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
                StructuredLocalPatternUniverseBuilder.LocalPatternUniverse layer =
                        build.layer(scope);
                Map<PatternCandidate, Integer> localUpper =
                        new LinkedHashMap<>();
                layer.patterns().forEach(pattern -> localUpper.put(
                        pattern, upper.get(pattern)));
                SparseProductionNeutralMoveEnumerator.Result sparse =
                        SparseProductionNeutralMoveEnumerator.enumerate(
                                layer.patterns(),
                                baseline,
                                localUpper,
                                excludedStates,
                                options);
                sparseRuns.put(scope, sparse);
                SupportGenerationPlateauArchive.LayerObservation observation =
                        SupportGenerationPlateauArchive.LayerObservation.from(
                                scope, layer.patterns().size(), sparse);
                layers.put(scope, observation);
                System.out.printf(
                        "DJX188 PLATEAU-LAYER budgetMs=%d repetition=%d "
                                + "scope=%s patterns=%d status=%s "
                                + "supports=%d coefficients=%d states=%d "
                                + "masterNodes=%d subNodes=%d elapsedMs=%d%n",
                        budgetRun.budgetMs(),
                        budgetRun.repetition(),
                        scope,
                        observation.patternCount(),
                        observation.status(),
                        observation.supportsVisited(),
                        observation.coefficientSolutions(),
                        observation.uniqueStates(),
                        observation.masterNodes(),
                        observation.subproblemNodes(),
                        observation.elapsedMs());
            }
            SupportBucketedCandidateEvaluator.Snapshot frozen =
                    SupportBucketedCandidateEvaluator.freeze(sparseRuns, 316);
            Set<String> supports = new TreeSet<>(
                    frozen.statesBySupport().keySet());
            Set<String> states = frozen.staticOrder().stream()
                    .map(SupportBucketedCandidateEvaluator.FrozenCandidate
                            ::stateSignature)
                    .collect(TreeSet::new, Set::add, Set::addAll);
            SupportGenerationPlateauArchive.RunObservation run =
                    new SupportGenerationPlateauArchive.RunObservation(
                            budgetRun.budgetMs(),
                            budgetRun.repetition(),
                            budgetRun.runId(),
                            layers,
                            supports,
                            states);
            runs.add(run);
            Set<String> fixedSupports = fixedSnapshot.candidates().stream()
                    .map(candidate -> candidate.state().supportIdentity())
                    .collect(TreeSet::new, Set::add, Set::addAll);
            SupportGenerationPlateauArchive.BaselineOverlap overlap =
                    SupportGenerationPlateauArchive.baselineOverlap(
                            run, fixedSupports);
            System.out.printf(
                    "DJX188 PLATEAU-RUN budgetMs=%d repetition=%d "
                            + "states=%d supports=%d baseline=%d/%d "
                            + "onlyRun=%d onlyBaseline=%d jaccard=%.6f%n",
                    run.budgetMs(),
                    run.repetition(),
                    run.stateSignatures().size(),
                    run.supportIdentities().size(),
                    overlap.intersection(),
                    fixedSupports.size(),
                    overlap.onlyRun(),
                    overlap.onlyBaseline(),
                    overlap.jaccard());
        }

        Map<String, String> identity = identityMetadata(
                demands,
                parameters,
                baseline,
                universe,
                build,
                excludedStates,
                preferredPatterns,
                preferredWidths,
                buildOptions);
        return new GeneratedRuns(
                List.copyOf(runs),
                identity,
                System.currentTimeMillis() - startedAt);
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
            StructuredLocalPatternUniverseBuilder.Options buildOptions) {
        Map<String, String> values = new TreeMap<>();
        values.put("budgetPlan", "20000x3,60000x2,180000x2");
        values.put("demandSha256",
                SupportCandidateSnapshotArchive.hashDemands(demands));
        values.put("baselineSha256",
                SupportCandidateSnapshotArchive.sha256OfLines(List.of(
                        SupportCandidateSnapshotArchive.stateSignature(
                                baseline))));
        values.put("completeUniverseSha256",
                SupportCandidateSnapshotArchive.hashPatterns(universe));
        values.put("witnessDirectUniverseSha256",
                SupportCandidateSnapshotArchive.hashPatterns(build.layer(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .WITNESS_DIRECT).patterns()));
        values.put("neutralClosureUniverseSha256",
                SupportCandidateSnapshotArchive.hashPatterns(build.layer(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .NEUTRAL_CLOSURE).patterns()));
        values.put("transferBridgeUniverseSha256",
                SupportCandidateSnapshotArchive.hashPatterns(build.layer(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .TRANSFER_BRIDGE_1_HOP).patterns()));
        values.put("compatibilityWitnessSha256",
                Djx188Automatic22CompatibilityFixture.EXPECTED_PAYLOAD_SHA256);
        values.put("excludedUnitStatesSha256",
                SupportCandidateSnapshotArchive.hashStrings(excludedStates));
        values.put("preferredPatternsSha256",
                SupportCandidateSnapshotArchive.hashStrings(preferredPatterns));
        values.put("preferredWidthsSha256",
                SupportCandidateSnapshotArchive.hashStrings(
                        preferredWidths.stream().map(String::valueOf).toList()));
        values.put("patternEnumeratorMaxParts", "5");
        values.put("totalWidth",
                Integer.toString(parameters.getTotalWidth()));
        values.put("expectedCars", "169");
        values.put("expectedWaste", "36870");
        values.put("buildMs", Long.toString(buildOptions.timeLimitMs()));
        values.put("buildMaxPatterns",
                Integer.toString(buildOptions.maxPatterns()));
        values.put("buildMaxEquations",
                Integer.toString(buildOptions.maxEquations()));
        values.put("buildMaxBridgePaths",
                Integer.toString(buildOptions.maxBridgePaths()));
        values.put("sparseSupportMs", Long.toString(SPARSE_SUPPORT_MS));
        values.put("sparseNodeLimit", Long.toString(SPARSE_NODE_LIMIT));
        values.put("sparseMaxSupports",
                Integer.toString(SPARSE_MAX_SUPPORTS));
        values.put("sparseMaxCoefficients",
                Integer.toString(SPARSE_MAX_COEFFICIENTS));
        values.put("sparseMaxStates",
                Integer.toString(SPARSE_MAX_STATES));
        values.put("scipThreads", "1");
        values.put("scipRandomSeedShift", "0");
        values.put("scipPermutationSeed", "0");
        values.put("scipLpSeed", "0");
        values.put("phase2Invoked", "false");
        values.put("exactInvoked", "false");
        values.put("probeConfigSha256",
                SupportCandidateSnapshotArchive.hashMetadata(values));
        return Map.copyOf(values);
    }

    private static Map<String, String> producerMetadata(long elapsedMs)
            throws IOException {
        Path root = Path.of("").toAbsolutePath().normalize();
        List<Path> relevant = List.of(
                root.resolve("src/test/java/test/demo/apsmodule/generator/"
                        + "NewSolver/Djx188SupportGenerationPlateauExperimentTest.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/"
                        + "NewSolver/SupportGenerationPlateauArchive.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/"
                        + "NewSolver/Djx188Automatic22CompatibilityFixture.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/"
                        + "NewSolver/StructuredLocalPatternUniverseBuilder.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/"
                        + "NewSolver/SparseProductionNeutralMoveEnumerator.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/"
                        + "NewSolver/SupportBucketedCandidateEvaluator.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/"
                        + "NewSolver/UnitTwoForTwoMoveEnumerator.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/"
                        + "NewSolver/Djx188ManualBaselineFixture.java"),
                root.resolve("src/test/java/test/demo/apsmodule/generator/"
                        + "NewSolver/Djx188ProductionNeutralFixture.java"));
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
            if (process.waitFor() != 0) {
                throw new IOException("Git metadata command failed: " + output);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading Git metadata",
                    interrupted);
        }
        return output;
    }

    private static void assertLayerAnchors(
            StructuredLocalPatternUniverseBuilder.BuildResult build,
            SupportCandidateSnapshotArchive.Snapshot fixedSnapshot) {
        assertEquals(121, build.layer(
                StructuredLocalPatternUniverseBuilder.UniverseScope
                        .WITNESS_DIRECT).patterns().size());
        assertEquals(370, build.layer(
                StructuredLocalPatternUniverseBuilder.UniverseScope
                        .NEUTRAL_CLOSURE).patterns().size());
        assertEquals(1_050, build.layer(
                StructuredLocalPatternUniverseBuilder.UniverseScope
                        .TRANSFER_BRIDGE_1_HOP).patterns().size());
        assertEquals(252, build.metrics().completeBaselineEquations());
        assertEquals(252, build.metrics().processedBaselineEquations());
        assertEquals(93, build.metrics().directRepairPatterns());
        assertEquals(1_044, build.metrics().eligibleBridgeEquations());
        assertEquals(
                fixedSnapshot.identityMetadata().get(
                        "witnessDirectUniverseSha256"),
                SupportCandidateSnapshotArchive.hashPatterns(build.layer(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .WITNESS_DIRECT).patterns()));
        assertEquals(
                fixedSnapshot.identityMetadata().get(
                        "neutralClosureUniverseSha256"),
                SupportCandidateSnapshotArchive.hashPatterns(build.layer(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .NEUTRAL_CLOSURE).patterns()));
        assertEquals(
                fixedSnapshot.identityMetadata().get(
                        "transferBridgeUniverseSha256"),
                SupportCandidateSnapshotArchive.hashPatterns(build.layer(
                        StructuredLocalPatternUniverseBuilder.UniverseScope
                                .TRANSFER_BRIDGE_1_HOP).patterns()));
    }

    private static List<BudgetRun> budgetRuns() {
        return List.of(
                new BudgetRun(20_000L, 1),
                new BudgetRun(20_000L, 2),
                new BudgetRun(20_000L, 3),
                new BudgetRun(60_000L, 1),
                new BudgetRun(60_000L, 2),
                new BudgetRun(180_000L, 1),
                new BudgetRun(180_000L, 2));
    }

    private static void printReport(
            Mode mode,
            boolean generationInvoked,
            boolean phase2Invoked,
            boolean exactInvoked,
            SupportGenerationPlateauArchive.Report report) {
        System.out.printf(
                "DJX188 PLATEAU-IDENTITY mode=%s source=%s "
                        + "generationInvoked=%s phase2Invoked=%s "
                        + "exactInvoked=%s snapshotModified=false "
                        + "path=%s snapshotSha256=%s payloadSha256=%s "
                        + "runs=%d baselineSupports=%d%n",
                mode,
                generationInvoked ? "CREATE" : "REPLAY",
                generationInvoked,
                phase2Invoked,
                exactInvoked,
                PLATEAU_PATH.toAbsolutePath().normalize(),
                report.baselineSnapshotPayloadSha256(),
                report.payloadSha256(),
                report.runs().size(),
                report.baselineSupports().size());
        SupportGenerationPlateauArchive.Analysis analysis =
                SupportGenerationPlateauArchive.analyze(report);
        for (SupportGenerationPlateauArchive.RunObservation run
                : report.runs()) {
            SupportGenerationPlateauArchive.BaselineOverlap overlap =
                    SupportGenerationPlateauArchive.baselineOverlap(
                            run, report.baselineSupports());
            System.out.printf(
                    "DJX188 PLATEAU-RUN-REPLAY budgetMs=%d repetition=%d "
                            + "states=%d supports=%d baselineIntersection=%d "
                            + "onlyRun=%d onlyBaseline=%d jaccard=%.6f "
                            + "excluded=%s wallClockContaminated=%s%n",
                    run.budgetMs(),
                    run.repetition(),
                    run.stateSignatures().size(),
                    run.supportIdentities().size(),
                    overlap.intersection(),
                    overlap.onlyRun(),
                    overlap.onlyBaseline(),
                    overlap.jaccard(),
                    analysis.excludedRunIds().contains(run.runId()),
                    analysis.wallClockContaminatedRunIds()
                            .contains(run.runId()));
        }
        analysis.budgetSummaries().forEach((budget, summary) ->
                System.out.printf(
                        "DJX188 PLATEAU-BUDGET budgetMs=%d repetitions=%d "
                                + "supportMin=%d supportMax=%d "
                                + "supportMean=%.3f supportMedian=%.3f "
                                + "supportStdDev=%.3f stateMin=%d stateMax=%d "
                                + "jaccardMin=%.6f jaccardMax=%.6f "
                                + "jaccardMean=%.6f stableCore=%d union=%d%n",
                        budget,
                        summary.repetitions(),
                        summary.supportStats().min(),
                        summary.supportStats().max(),
                        summary.supportStats().mean(),
                        summary.supportStats().median(),
                        summary.supportStats().sampleStandardDeviation(),
                        summary.stateStats().min(),
                        summary.stateStats().max(),
                        summary.pairwiseJaccard().min(),
                        summary.pairwiseJaccard().max(),
                        summary.pairwiseJaccard().mean(),
                        summary.stableCore().size(),
                        summary.supportUnion().size()));
        System.out.printf(
                "DJX188 PLATEAU-MARGINAL union20=%d union60=%d "
                        + "union180=%d new60Vs20=%d new180VsLower=%d "
                        + "cumulativeUnion=%d%n",
                analysis.union20().size(),
                analysis.union60().size(),
                analysis.union180().size(),
                analysis.novel60Vs20().size(),
                analysis.novel180VsLower().size(),
                analysis.cumulativeUnion().size());
        System.out.printf(
                "DJX188 PLATEAU-CONCLUSION status=%s stable60=%s "
                        + "stable180=%s all180Exhausted=%s capped60Or180=%s "
                        + "incomplete=%s excludedRuns=%s "
                        + "wallClockContaminatedRuns=%s "
                        + "thresholdJaccard=0.90 "
                        + "thresholdRelativeRange=0.10%n",
                analysis.status(),
                analysis.stable60(),
                analysis.stable180(),
                analysis.all180Exhausted(),
                analysis.capped60Or180(),
                analysis.incompleteRun(),
                analysis.excludedRunIds(),
                analysis.wallClockContaminatedRunIds());
        System.out.printf(
                "DJX188 PLATEAU-NOVEL-60 supports=%s%n",
                analysis.novel60Vs20());
        System.out.printf(
                "DJX188 PLATEAU-NOVEL-180 supports=%s%n",
                analysis.novel180VsLower());
    }

    private enum Mode {
        CREATE,
        REPLACE,
        REPLAY,
        VERIFY
    }

    private record BudgetRun(long budgetMs, int repetition) {
        private String runId() {
            return "b" + budgetMs + "-r" + repetition;
        }
    }

    private record GeneratedRuns(
            List<SupportGenerationPlateauArchive.RunObservation> runs,
            Map<String, String> identityMetadata,
            long elapsedMs) {
    }
}
