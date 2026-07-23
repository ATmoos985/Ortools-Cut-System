package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateAuditTracker.CandidateExplanation;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateAuditTracker.LossStage;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ColumnPool;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DualVector;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderGroupMissingColumnAuditTest {

    @Test
    void presentColumnIsClassifiedBeforeReplay() {
        GroupColumn target = fixtureColumn(2);
        ColumnPool pool = new ColumnPool(4);
        pool.add(target);
        OrderGroupColumnCandidateAuditTracker tracker =
                new OrderGroupColumnCandidateAuditTracker(List.of(target), pool);

        assertEquals(
                LossStage.PRESENT_IN_AUTONOMOUS_POOL,
                freeze(tracker, target).stage());
    }

    @Test
    void absentCarCountIsClassified() {
        GroupColumn target = fixtureColumn(2);
        OrderGroupColumnCandidateAuditTracker tracker = tracker(target);
        tracker.observePattern(target.pattern());

        assertEquals(
                LossStage.CAR_COUNT_PRUNED,
                freeze(tracker, target).stage());
    }

    @Test
    void localConfigurationPruningKeepsRankAndLimit() {
        GroupColumn target = fixtureColumn(2);
        OrderGroupColumnCandidateAuditTracker tracker = tracker(target);
        tracker.observePattern(target.pattern());
        tracker.observeCar(target.pattern(), target.cars());
        tracker.observeLocal(target, 50, true, 3, false, 1);

        CandidateExplanation explanation = freeze(tracker, target);
        assertEquals(LossStage.LOCAL_CONFIGURATION_PRUNED, explanation.stage());
        assertEquals(3, explanation.rank());
        assertEquals(1, explanation.limit());
        assertEquals("width=50", explanation.locus());
    }

    @Test
    void beamPruningKeepsFirstFailedWidth() {
        GroupColumn target = fixtureColumn(2);
        OrderGroupColumnCandidateAuditTracker tracker = tracker(target);
        tracker.observePattern(target.pattern());
        tracker.observeCar(target.pattern(), target.cars());
        tracker.observeBeam(target, 50, 4, false, 2);

        CandidateExplanation explanation = freeze(tracker, target);
        assertEquals(LossStage.BEAM_PRUNED, explanation.stage());
        assertEquals(4, explanation.rank());
        assertEquals(2, explanation.limit());
    }

    @Test
    void materializedNonnegativeColumnIsNotMisreportedAsPruned() {
        GroupColumn target = fixtureColumn(2);
        OrderGroupColumnCandidateAuditTracker tracker = tracker(target);
        observeMaterialized(tracker, target, 0.25);

        CandidateExplanation explanation = freeze(tracker, target);
        assertEquals(LossStage.MATERIALIZED_NONNEGATIVE, explanation.stage());
        assertEquals(0.25, explanation.rawReducedCost(), 1e-9);
    }

    @Test
    void negativeColumnBelowPatternCapIsClassified() {
        GroupColumn target = fixtureColumn(2);
        OrderGroupColumnCandidateAuditTracker tracker = tracker(target);
        observeMaterialized(tracker, target, -1.0);
        tracker.observePatternRanking(
                target.pattern(), List.of("better", target.signature()), 1);

        CandidateExplanation explanation = freeze(tracker, target);
        assertEquals(LossStage.NEGATIVE_PATTERN_CAP_PRUNED, explanation.stage());
        assertEquals(2, explanation.rank());
        assertEquals(1, explanation.limit());
    }

    @Test
    void negativeColumnMayPassPatternCapButLoseGlobalBudget() {
        GroupColumn target = fixtureColumn(2);
        OrderGroupColumnCandidateAuditTracker tracker = tracker(target);
        observeMaterialized(tracker, target, -1.0);
        tracker.observePatternRanking(
                target.pattern(), List.of(target.signature()), 1);
        tracker.observeGlobalRetained(List.of(target));

        CandidateExplanation explanation = freeze(tracker, target);
        assertEquals(
                LossStage.NEGATIVE_GLOBAL_SELECTION_PRUNED,
                explanation.stage());
        assertEquals("role/global-selection", explanation.locus());
    }

    @Test
    void returnedNegativeColumnIsSeparatedFromMissingCandidates() {
        GroupColumn target = fixtureColumn(2);
        OrderGroupColumnCandidateAuditTracker tracker = tracker(target);
        observeMaterialized(tracker, target, -1.0);
        tracker.observePatternRanking(
                target.pattern(), List.of(target.signature()), 1);
        tracker.observeGlobalRetained(List.of(target));
        tracker.observeReturned(List.of(target));

        assertEquals(
                LossStage.GENERATED_IN_DIAGNOSTIC_REPLAY,
                freeze(tracker, target).stage());
    }

    @Test
    void exactExchangeProvesResourceConservationAndTwoGroupGain() {
        ExchangeFixture fixture = exchangeFixture();

        OrderGroupMissingColumnAudit.ExchangeAudit exchange =
                OrderGroupMissingColumnAudit.auditExchange(
                        fixture.autonomous(),
                        fixture.witness(),
                        emptyDual());

        assertTrue(exchange.conserved());
        assertEquals(-2, exchange.groupDelta());
        assertTrue(exchange.objectiveDelta() < -2.0);
        assertEquals(1, exchange.nonnegativeAddedColumns());
        assertEquals(
                exchange.removedResources(),
                exchange.addedResources());
    }

    @Test
    void nonconservingExchangeIsRejected() {
        ExchangeFixture fixture = exchangeFixture();
        List<GroupColumn> incompleteWitness = List.of();

        OrderGroupMissingColumnAudit.ExchangeAudit exchange =
                OrderGroupMissingColumnAudit.auditExchange(
                        fixture.autonomous(),
                        incompleteWitness,
                        emptyDual());

        assertFalse(exchange.conserved());
        assertEquals(-3, exchange.groupDelta());
    }

    @Test
    void repeatedTrackerReplayIsDeterministic() {
        GroupColumn target = fixtureColumn(2);
        CandidateExplanation first = replayReturned(target);
        CandidateExplanation second = replayReturned(target);

        assertEquals(first, second);
    }

    private static CandidateExplanation replayReturned(GroupColumn target) {
        OrderGroupColumnCandidateAuditTracker tracker = tracker(target);
        observeMaterialized(tracker, target, -0.5);
        tracker.observePatternRanking(
                target.pattern(), List.of(target.signature()), 1);
        tracker.observeGlobalRetained(List.of(target));
        tracker.observeReturned(List.of(target));
        return freeze(tracker, target);
    }

    private static void observeMaterialized(
            OrderGroupColumnCandidateAuditTracker tracker,
            GroupColumn target,
            double rawReducedCost) {
        tracker.observePattern(target.pattern());
        tracker.observeCar(target.pattern(), target.cars());
        tracker.observeMaterialized(target, rawReducedCost);
    }

    private static CandidateExplanation freeze(
            OrderGroupColumnCandidateAuditTracker tracker,
            GroupColumn target) {
        return tracker.freeze(
                        false,
                        1e-7,
                        OrderGroupColumnPricingOracle.Diagnostics.empty())
                .explanations()
                .get(target.signature());
    }

    private static OrderGroupColumnCandidateAuditTracker tracker(
            GroupColumn target) {
        return new OrderGroupColumnCandidateAuditTracker(
                List.of(target), new ColumnPool(4));
    }

    private static GroupColumn fixtureColumn(int cars) {
        PatternCandidate pattern = new PatternCandidate(Map.of(50, 1), 60);
        Input input = input(
                List.of(item("A", 50, 10)),
                List.of(pattern),
                10,
                500,
                0,
                0);
        return GroupColumn.create(input, pattern, Map.of(50, List.of("A")), cars);
    }

    private static ExchangeFixture exchangeFixture() {
        PatternCandidate p1 = new PatternCandidate(Map.of(50, 1), 60);
        PatternCandidate p2 = new PatternCandidate(Map.of(50, 1), 70);
        PatternCandidate p3 = new PatternCandidate(Map.of(50, 1), 80);
        PatternCandidate combined = new PatternCandidate(Map.of(50, 1), 90);
        Input input = input(
                List.of(item("A", 50, 6)),
                List.of(p1, p2, p3, combined),
                6,
                300,
                0,
                0);
        Map<Integer, List<String>> config = Map.of(50, List.of("A"));
        List<GroupColumn> autonomous = List.of(
                GroupColumn.create(input, p1, config, 2),
                GroupColumn.create(input, p2, config, 2),
                GroupColumn.create(input, p3, config, 2));
        List<GroupColumn> witness = List.of(
                GroupColumn.create(input, combined, config, 6));
        return new ExchangeFixture(autonomous, witness);
    }

    private static Input input(
            List<SolverOrderItem> items,
            List<PatternCandidate> universe,
            int cars,
            int waste,
            int odd,
            int one) {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(50);
        params.setMaxRollWidth(100);
        params.setStepSize(10);
        params.setTotalWidth(100);
        params.setTotalOverCap(0);
        params.setMaxDistinctWidths(4);
        params.sanitize();
        return new Input(items, universe, params, cars, waste, odd, one);
    }

    private static SolverOrderItem item(
            String message, int width, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setMessageText(message);
        item.setWidth(width);
        item.setDemand(demand);
        item.setLength(1000);
        item.setSurfaceTreatment("TEST");
        item.setGroupKey("1000m+TEST");
        return item;
    }

    private static DualVector emptyDual() {
        return new DualVector(Map.of(), 0.0, 0.0, 0.0, 0.0, Map.of());
    }

    private record ExchangeFixture(
            List<GroupColumn> autonomous,
            List<GroupColumn> witness) {
    }
}
