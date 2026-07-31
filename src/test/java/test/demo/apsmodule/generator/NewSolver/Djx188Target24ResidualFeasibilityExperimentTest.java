package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.Djx188Target24Fixture.AutonomousIncumbent;
import test.demo.apsmodule.generator.NewSolver.Djx188Target24Fixture.Baseline;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.ResidualTargetSolver.ProofState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in executable research stages for the DJX188 Target-24 design.
 */
class Djx188Target24ResidualFeasibilityExperimentTest {

    private static final String R1_PROPERTY =
            "cutting.test.target24.r1";
    private static final String R2_PROPERTY =
            "cutting.test.target24.r2";

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void r1WithoutWitnessReachesAtMost29Groups() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean(R1_PROPERTY),
                "enable with -D" + R1_PROPERTY + "=true");
        AutonomousIncumbent autonomous =
                Djx188Target24Fixture.loadAutonomousIncumbent();
        Input input = autonomous.input();
        List<GroupColumn> current =
                autonomous.result().selectedColumns();
        assertEquals(31, current.size(),
                "autonomous baseline drifted");

        int pass = 0;
        while (current.size() > 29) {
            pass++;
            int target = current.size() - 1;
            TargetGroupFeasibilityHarness.Result result =
                    TargetGroupFeasibilityHarness.search(
                            input,
                            current,
                            target,
                            r1Options());
            printResult("R1-pass-" + pass, result);
            writeArchive("r1-pass-" + pass, result);
            assertEquals(
                    ProofState.FEASIBLE,
                    result.state(),
                    "R1 failed to find autonomous one-group improvement: "
                            + result.detail());
            current = result.columns();
        }

        assertTrue(current.size() <= 29);
        assertTrue(ResidualTargetSolver.conserves(
                current, input.demand(), input.exactCars(),
                input.exactWaste(), input.exactOddGroups(),
                input.exactOneCarGroups()));
    }

    @Test
    void r2BoundedSearchFromFrozen25GroupIncumbent()
            throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean(R2_PROPERTY),
                "enable with -D" + R2_PROPERTY + "=true");
        Baseline baseline = Djx188Target24Fixture.load();
        String sizeTag = System.getProperty(
                        "cutting.test.target24.r2.sizes", "7,8")
                .replace(',', '-')
                .replaceAll("[^0-9-]", "");
        String stage = "R2-target24-sizes-" + sizeTag;
        TargetGroupFeasibilityHarness.Result result =
                TargetGroupFeasibilityHarness.search(
                        baseline.input(),
                        baseline.columns(),
                        24,
                        r2Options());
        printResult(stage, result);
        writeArchive(stage.toLowerCase(), result);

        if (result.state() == ProofState.FEASIBLE) {
            assertTrue(result.columns().size() <= 24);
            assertTrue(ResidualTargetSolver.conserves(
                    result.columns(),
                    baseline.input().demand(),
                    baseline.input().exactCars(),
                    baseline.input().exactWaste(),
                    baseline.input().exactOddGroups(),
                    baseline.input().exactOneCarGroups()));
            Path output = Path.of(System.getProperty(
                    "cutting.test.target24.saveTo",
                    "target/djx188-target24-incumbent.txt"));
            Files.createDirectories(
                    output.toAbsolutePath().getParent());
            Files.write(
                    output,
                    result.columns().stream()
                            .map(GroupColumn::signature)
                            .toList(),
                    StandardCharsets.UTF_8);
            System.out.printf(
                    "TARGET24_WITNESS saved=%s%n",
                    output.toAbsolutePath());
        }
    }

    private static TargetGroupFeasibilityHarness.Options
            r1Options() {
        int setsPerSize = Integer.getInteger(
                "cutting.test.target24.r1.setsPerSize",
                400);
        return new TargetGroupFeasibilityHarness.Options(
                new OrderGroupDestroySetPlanner.Options(
                        List.of(2, 3, 4),
                        setsPerSize,
                        Math.max(setsPerSize * 8, 800)),
                Integer.getInteger(
                        "cutting.test.target24.r1.maxCandidates",
                        20_000),
                Long.getLong(
                        "cutting.test.target24.r1.generationMs",
                        10_000L),
                Long.getLong(
                        "cutting.test.target24.r1.mipMs",
                        3_000L),
                Long.getLong(
                        "cutting.test.target24.r1.totalMs",
                        180_000L));
    }

    private static TargetGroupFeasibilityHarness.Options
            r2Options() {
        int setsPerSize = Integer.getInteger(
                "cutting.test.target24.r2.setsPerSize",
                4);
        return new TargetGroupFeasibilityHarness.Options(
                new OrderGroupDestroySetPlanner.Options(
                        parseSizes(System.getProperty(
                                "cutting.test.target24.r2.sizes",
                                "7,8")),
                        setsPerSize,
                        Math.max(setsPerSize * 16, 400)),
                Integer.getInteger(
                        "cutting.test.target24.r2.maxCandidates",
                        50_000),
                Long.getLong(
                        "cutting.test.target24.r2.generationMs",
                        30_000L),
                Long.getLong(
                        "cutting.test.target24.r2.mipMs",
                        30_000L),
                Long.getLong(
                        "cutting.test.target24.r2.totalMs",
                        300_000L));
    }

    private static List<Integer> parseSizes(String encoded) {
        return java.util.Arrays.stream(encoded.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }

    private static void printResult(
            String stage,
            TargetGroupFeasibilityHarness.Result result) {
        System.out.printf(
                "%s state=%s target=%d groups=%d planned=%d "
                        + "attempted=%d provenInfeasible=%d "
                        + "inconclusive=%d budgetExhausted=%s "
                        + "elapsedMs=%d detail=%s%n",
                stage,
                result.state(),
                result.targetGroups(),
                result.columns().size(),
                result.neighborhoodsPlanned(),
                result.neighborhoodsAttempted(),
                result.neighborhoodsProvenInfeasible(),
                result.neighborhoodsInconclusive(),
                result.budgetExhausted(),
                result.elapsedMs(),
                result.detail());
        result.attempts().stream()
                .filter(attempt ->
                        attempt.solverResult().state()
                                == ProofState.FEASIBLE
                                || attempt.candidateTruncated()
                                || attempt.solverResult().state()
                                == ProofState.INCONCLUSIVE)
                .limit(10)
                .forEach(attempt ->
                System.out.printf(
                        "  notable destroy=%d score=%d kept=%d "
                                + "targetResidual=%d candidates=%d "
                                + "truncated=%s blockedFamilies=%d "
                                + "candidateHash=%s state=%s "
                                + "solver=%s status=%s nodes=%d "
                                + "elapsedMs=%d reason=%s%n",
                        attempt.destroySet().size(),
                        attempt.destroySet().score(),
                        attempt.keptGroups(),
                        attempt.targetResidualGroups(),
                        attempt.candidateCount(),
                        attempt.candidateTruncated(),
                        attempt.blockedFamilyCount(),
                        attempt.candidateSignatureHash(),
                        attempt.solverResult().state(),
                        attempt.solverResult().solverName(),
                        attempt.solverResult().solverStatus(),
                        attempt.solverResult().nodes(),
                        attempt.elapsedMs(),
                        attempt.solverResult().inconclusiveReason()));
    }

    private static void writeArchive(
            String stage,
            TargetGroupFeasibilityHarness.Result result)
            throws Exception {
        Path output = Path.of(
                "target",
                "djx188-target24-" + stage + "-attempts.tsv");
        Files.createDirectories(
                output.toAbsolutePath().getParent());
        List<String> lines = new java.util.ArrayList<>();
        lines.add(
                "stage\toverallState\ttarget\tplanned\tattempted"
                        + "\tprovenInfeasible\tinconclusive"
                        + "\tbudgetExhausted\ttotalElapsedMs");
        lines.add(String.join(
                "\t",
                stage,
                result.state().name(),
                Integer.toString(result.targetGroups()),
                Integer.toString(result.neighborhoodsPlanned()),
                Integer.toString(result.neighborhoodsAttempted()),
                Integer.toString(
                        result.neighborhoodsProvenInfeasible()),
                Integer.toString(
                        result.neighborhoodsInconclusive()),
                Boolean.toString(result.budgetExhausted()),
                Long.toString(result.elapsedMs())));
        lines.add(
                "attempt\tdestroySize\tdestroyScore\tkept"
                        + "\ttargetResidual\tcandidates\ttruncated"
                        + "\tblockedFamilies\tcandidateHash\tstate"
                        + "\tsolver\tstatus\tnodes\telapsedMs\treason"
                        + "\tdestroySignatures");
        for (int index = 0;
                index < result.attempts().size();
                index++) {
            TargetGroupFeasibilityHarness.Attempt attempt =
                    result.attempts().get(index);
            lines.add(String.join(
                    "\t",
                    Integer.toString(index + 1),
                    Integer.toString(attempt.destroySet().size()),
                    Long.toString(attempt.destroySet().score()),
                    Integer.toString(attempt.keptGroups()),
                    Integer.toString(
                            attempt.targetResidualGroups()),
                    Integer.toString(attempt.candidateCount()),
                    Boolean.toString(
                            attempt.candidateTruncated()),
                    Integer.toString(
                            attempt.blockedFamilyCount()),
                    attempt.candidateSignatureHash(),
                    attempt.solverResult().state().name(),
                    attempt.solverResult().solverName(),
                    attempt.solverResult().solverStatus().name(),
                    Long.toString(
                            attempt.solverResult().nodes()),
                    Long.toString(attempt.elapsedMs()),
                    attempt.solverResult()
                            .inconclusiveReason().name(),
                    String.join(
                            " || ",
                            attempt.destroySet().signatures())));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
        System.out.printf(
                "%s archive=%s rows=%d%n",
                stage,
                output.toAbsolutePath(),
                result.attempts().size());
    }
}
