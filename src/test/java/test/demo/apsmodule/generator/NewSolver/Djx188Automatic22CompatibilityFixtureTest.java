package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Djx188Automatic22CompatibilityFixtureTest {

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void fixtureLoadsWithoutSolvingAndKeepsPublishedAnchor() throws Exception {
        OrderCompatibilityKernelAnalyzer.Analysis fixture =
                Djx188Automatic22CompatibilityFixture.load(
                        Djx188ProductionNeutralFixture.loadAutomatic22());

        assertEquals(28, fixture.exactMinimumGroups());
        assertEquals(6, fixture.exactExtraGroups());
        assertEquals(1, fixture.oddGroups());
        assertEquals(0, fixture.oneGroups());
        assertEquals(6, fixture.splitWitnesses().size());
        assertEquals(
                Djx188Automatic22CompatibilityFixture.EXPECTED_PAYLOAD_SHA256,
                Djx188Automatic22CompatibilityFixture.payloadSha256(fixture));
        assertTrue(fixture.splitWitnesses().stream().allMatch(split ->
                split.configurations().stream()
                        .mapToInt(OrderCompatibilityKernelAnalyzer.ConfigUse::cars)
                        .sum() == split.patternUsage()));
    }

    @Test
    void fixtureMatchesLiveCompatibilityKernel() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("cutting.test.automatic22WitnessLive"),
                "live fixture comparison is opt-in");
        OrderCompatibilityKernelAnalyzer.Analysis live =
                OrderCompatibilityKernelAnalyzer.analyze(
                        Djx188ProductionNeutralFixture.loadAutomatic22(),
                        Djx188ManualBaselineFixture.loadItems(),
                        OrderCompatibilityKernelAnalyzer.Options.regressionDefaults());

        assertEquals(OrderCompatibilityKernelAnalyzer.Status.OPTIMAL,
                live.status());
        assertTrue(live.groupOptimal());
        assertTrue(live.shapeOptimal());
        assertEquals(
                Djx188Automatic22CompatibilityFixture.EXPECTED_PAYLOAD_SHA256,
                Djx188Automatic22CompatibilityFixture.payloadSha256(live));
    }
}
