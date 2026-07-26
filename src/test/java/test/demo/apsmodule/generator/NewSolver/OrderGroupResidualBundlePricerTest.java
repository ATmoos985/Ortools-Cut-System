package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderGroupResidualBundlePricerTest {

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void conservedMergeChainIsFoundByBundleSwaps() {
        Fixture fixture = mergeChainFixture();

        OrderGroupResidualBundlePricer.Result result =
                OrderGroupResidualBundlePricer.improve(
                        fixture.input(), fixture.incumbent(), unitOptions());

        assertEquals(1, result.metrics().groups());
        assertEquals(6, result.metrics().cars());
        assertEquals(300, result.metrics().waste());
        assertEquals(0, result.metrics().oddGroups());
        assertEquals(0, result.metrics().oneCarGroups());
        assertEquals(2, result.swapsApplied());
        result.swaps().forEach(swap -> assertEquals(-1, swap.groupDelta()));
        assertFalse(result.budgetExhausted());
    }

    @Test
    void familyCollisionWithKeptColumnsIsExcludedFromCandidates() {
        Fixture fixture = mergeChainFixture();
        List<GroupColumn> bundle = fixture.incumbent().subList(0, 2);
        Map<OrderGroupColumnPricingPrototype.DemandKey, Integer> residual =
                new TreeMap<>();
        bundle.forEach(column -> column.coverage().forEach(
                (key, value) -> residual.merge(key, value, Math::addExact)));
        PatternCandidate combined = fixture.universe().get(3);
        String blockedFamily = GroupColumn.create(
                        fixture.input(), combined, Map.of(50, List.of("A")), 2)
                .familySignature();

        OrderGroupResidualBundlePricer.CandidateSet open =
                OrderGroupResidualBundlePricer.enumerateResidualColumns(
                        fixture.input(), residual, 4, 200, 0, 0,
                        Set.of(), bundle, 1_000,
                        System.currentTimeMillis() + 10_000L);
        OrderGroupResidualBundlePricer.CandidateSet blocked =
                OrderGroupResidualBundlePricer.enumerateResidualColumns(
                        fixture.input(), residual, 4, 200, 0, 0,
                        Set.of(blockedFamily), bundle, 1_000,
                        System.currentTimeMillis() + 10_000L);

        assertTrue(open.columns().stream()
                .anyMatch(column -> column.familySignature().equals(blockedFamily)));
        assertTrue(blocked.columns().stream()
                .noneMatch(column -> column.familySignature().equals(blockedFamily)));
        assertFalse(open.truncated());
    }

    @Test
    void noSwapWhenNoConservedReplacementExists() {
        SolverParameters params = params();
        PatternCandidate single = new PatternCandidate(Map.of(50, 1), 60);
        Input input = new Input(
                List.of(item("A", 50, 2), item("B", 50, 2), item("C", 50, 2)),
                List.of(single),
                params,
                6,
                300,
                0,
                0);
        List<GroupColumn> incumbent = List.of(
                GroupColumn.create(input, single, Map.of(50, List.of("A")), 2),
                GroupColumn.create(input, single, Map.of(50, List.of("B")), 2),
                GroupColumn.create(input, single, Map.of(50, List.of("C")), 2));

        OrderGroupResidualBundlePricer.Result result =
                OrderGroupResidualBundlePricer.improve(input, incumbent, unitOptions());

        assertEquals(0, result.swapsApplied());
        assertEquals(3, result.metrics().groups());
        assertTrue(result.bundlesScanned() >= 3);
        assertEquals(
                incumbent.stream().map(GroupColumn::signature).sorted().toList(),
                result.columns().stream().map(GroupColumn::signature).toList());
    }

    @Test
    void repeatedRunsAreDeterministic() {
        Fixture fixture = mergeChainFixture();

        OrderGroupResidualBundlePricer.Result first =
                OrderGroupResidualBundlePricer.improve(
                        fixture.input(), fixture.incumbent(), unitOptions());
        OrderGroupResidualBundlePricer.Result second =
                OrderGroupResidualBundlePricer.improve(
                        fixture.input(), fixture.incumbent(), unitOptions());

        assertEquals(first.swaps(), second.swaps());
        assertEquals(
                first.columns().stream().map(GroupColumn::signature).toList(),
                second.columns().stream().map(GroupColumn::signature).toList());
    }

    @Test
    void candidateCapIsReportedAsTruncationNotSilence() {
        Fixture fixture = mergeChainFixture();
        OrderGroupResidualBundlePricer.Options tight =
                new OrderGroupResidualBundlePricer.Options(
                        2, 3, 50, 2, 2_000L, 20_000L, 8);

        OrderGroupResidualBundlePricer.Result result =
                OrderGroupResidualBundlePricer.improve(
                        fixture.input(), fixture.incumbent(), tight);

        assertTrue(result.bundlesTruncated() > 0);
        assertEquals(6, result.metrics().cars());
        assertEquals(300, result.metrics().waste());
    }

    @Test
    void signatureRoundTripRebuildsIdenticalColumn() {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(100);
        params.setMaxRollWidth(200);
        params.setStepSize(10);
        params.setTotalWidth(200);
        params.setTotalOverCap(0);
        params.setMaxDistinctWidths(4);
        params.sanitize();
        PatternCandidate pattern = new PatternCandidate(
                Map.of(40, 2, 60, 1), 150);
        Input input = new Input(
                List.of(item("A", 40, 4), item("B", 40, 4), item("C", 60, 2)),
                List.of(pattern),
                params,
                2,
                120,
                0,
                0);
        GroupColumn original = GroupColumn.create(
                input, pattern,
                Map.of(40, List.of("A", "A"), 60, List.of("C")), 2);

        GroupColumn parsed = OrderGroupResidualBundlePricer.parseColumn(
                input, original.signature());

        assertEquals(original.signature(), parsed.signature());
        assertEquals(original.resourceSignature(), parsed.resourceSignature());
        assertEquals(original.familySignature(), parsed.familySignature());
        assertEquals(original.coverage(), parsed.coverage());
    }

    private static OrderGroupResidualBundlePricer.Options unitOptions() {
        return new OrderGroupResidualBundlePricer.Options(
                2, 3, 50, 1_000, 2_000L, 20_000L, 8);
    }

    /**
     * Single order A with demand 6 covered by three two-car columns on three
     * distinct patterns; the universe also holds a fourth pattern so the
     * pricer can merge 3 groups down to 1 via two conserved swaps.
     */
    private static Fixture mergeChainFixture() {
        SolverParameters params = params();
        PatternCandidate p1 = new PatternCandidate(Map.of(50, 1), 60);
        PatternCandidate p2 = new PatternCandidate(Map.of(50, 1), 70);
        PatternCandidate p3 = new PatternCandidate(Map.of(50, 1), 80);
        PatternCandidate combined = new PatternCandidate(Map.of(50, 1), 90);
        List<PatternCandidate> universe = List.of(p1, p2, p3, combined);
        Input input = new Input(
                List.of(item("A", 50, 6)),
                universe,
                params,
                6,
                300,
                0,
                0);
        Map<Integer, List<String>> config = Map.of(50, List.of("A"));
        List<GroupColumn> incumbent = List.of(
                GroupColumn.create(input, p1, config, 2),
                GroupColumn.create(input, p2, config, 2),
                GroupColumn.create(input, p3, config, 2));
        return new Fixture(input, universe, incumbent);
    }

    private static SolverParameters params() {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(50);
        params.setMaxRollWidth(100);
        params.setStepSize(10);
        params.setTotalWidth(100);
        params.setTotalOverCap(0);
        params.setMaxDistinctWidths(4);
        params.sanitize();
        return params;
    }

    private static SolverOrderItem item(String message, int width, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setMessageText(message);
        item.setWidth(width);
        item.setDemand(demand);
        item.setLength(1000);
        item.setSurfaceTreatment("TEST");
        item.setGroupKey("1000m+TEST");
        return item;
    }

    private record Fixture(
            Input input,
            List<PatternCandidate> universe,
            List<GroupColumn> incumbent) {
    }
}
