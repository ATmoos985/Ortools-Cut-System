package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.Djx188Target24Fixture.Baseline;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ConvertedOutput;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Metrics;
import test.demo.apsmodule.generator.NewSolver.ResidualTargetSolver.ProofState;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Djx188Target24BaselineTest {

    private static Baseline baseline;

    @BeforeAll
    static void loadBaseline() throws Exception {
        Loader.loadNativeLibraries();
        baseline = Djx188Target24Fixture.load();
    }

    @Test
    void frozenBaselineMatchesVersionedInputAndSignatureHashes()
            throws Exception {
        assertEquals(7_717, baseline.input().universe().size());
        assertEquals(25, baseline.signatures().size());
        assertEquals(
                baseline.metadata().getProperty("source.input.sha256"),
                Djx188Target24Fixture.resourceSha256("/djx188.csv"));
        assertEquals(
                baseline.metadata().getProperty(
                        "signatures.normalized.sha256"),
                Djx188Target24Fixture.normalizedSignatureSha256(
                        baseline.signatures()));
    }

    @Test
    void frozenBaselineIsAnExact25GroupSolution() {
        Metrics metrics = Metrics.fromColumns(baseline.columns());

        assertEquals(25, metrics.groups());
        assertEquals(169, metrics.cars());
        assertEquals(36_870, metrics.waste());
        assertEquals(1, metrics.oddGroups());
        assertEquals(0, metrics.oneCarGroups());
        assertEquals(
                baseline.input().demand(),
                Djx188Target24Fixture.aggregateCoverage(
                        baseline.columns()));
        assertEquals(
                25,
                new HashSet<>(baseline.columns().stream()
                        .map(column -> column.familySignature())
                        .toList()).size());

        ResidualTargetSolver.Result judged =
                ResidualTargetSolver.solve(
                        new ResidualTargetSolver.Request(
                                baseline.input().demand(),
                                baseline.input().exactCars(),
                                baseline.input().exactWaste(),
                                baseline.input().exactOddGroups(),
                                baseline.input().exactOneCarGroups(),
                                baseline.columns(),
                                25,
                                false,
                                10_000L));
        assertEquals(ProofState.FEASIBLE, judged.state(), judged.detail());
    }

    @Test
    void convertedDisplayMetricsMatchDirectColumnMetrics() {
        Metrics direct = Metrics.fromColumns(baseline.columns());
        ConvertedOutput converted =
                OrderGroupColumnPricingEngine.convertSelected(
                        baseline.input(), baseline.columns());
        SequenceGroupPostProcessor.GroupStats displayed =
                SequenceGroupPostProcessor.computeGroupStats(
                        converted.instructions());

        assertEquals(direct.groups(), displayed.groups());
        assertEquals(direct.oddGroups(), displayed.oddCarGroups());
        assertEquals(direct.oneCarGroups(), displayed.oneCarGroups());
        assertEquals(direct.cars(),
                converted.solution().values().stream()
                        .mapToInt(Integer::intValue)
                        .sum());
    }
}
