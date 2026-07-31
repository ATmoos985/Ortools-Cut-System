package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.ResidualTargetSolver.InconclusiveReason;
import test.demo.apsmodule.generator.NewSolver.ResidualTargetSolver.ProofState;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetGroupFeasibilityHarnessTest {

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void destroySetPlanningIsDeterministicAcrossIncumbentOrder() {
        Fixture fixture = mergeFixture();
        OrderGroupDestroySetPlanner.Options options =
                new OrderGroupDestroySetPlanner.Options(
                        List.of(2, 3), 10, 30);

        List<OrderGroupDestroySetPlanner.DestroySet> first =
                OrderGroupDestroySetPlanner.plan(
                        fixture.incumbent(), options);
        List<GroupColumn> reversed =
                new ArrayList<>(fixture.incumbent());
        Collections.reverse(reversed);
        List<OrderGroupDestroySetPlanner.DestroySet> second =
                OrderGroupDestroySetPlanner.plan(
                        reversed, options);

        assertEquals(
                first.stream()
                        .map(set -> set.signatures())
                        .toList(),
                second.stream()
                        .map(set -> set.signatures())
                        .toList());
        assertEquals(
                first.stream().map(set -> set.score()).toList(),
                second.stream().map(set -> set.score()).toList());
    }

    @Test
    void guidedResidualSearchFindsExactThreeToOneMerge() {
        Fixture fixture = mergeFixture();

        TargetGroupFeasibilityHarness.Result result =
                TargetGroupFeasibilityHarness.search(
                        fixture.input(),
                        fixture.incumbent(),
                        1,
                        options(1_000));

        assertEquals(ProofState.FEASIBLE, result.state(), result.detail());
        assertEquals(1, result.columns().size());
        assertTrue(ResidualTargetSolver.conserves(
                result.columns(),
                fixture.input().demand(),
                fixture.input().exactCars(),
                fixture.input().exactWaste(),
                fixture.input().exactOddGroups(),
                fixture.input().exactOneCarGroups()));
        assertFalse(result.attempts().get(0).candidateTruncated());
    }

    @Test
    void completeNeighborhoodCanBeProvedInfeasible() {
        Fixture fixture = separatedOrdersFixture();

        TargetGroupFeasibilityHarness.Result result =
                TargetGroupFeasibilityHarness.search(
                        fixture.input(),
                        fixture.incumbent(),
                        2,
                        options(1_000));

        assertEquals(
                ProofState.PROVEN_INFEASIBLE,
                result.state(),
                result.detail());
        assertEquals(1, result.neighborhoodsProvenInfeasible());
        assertEquals(0, result.neighborhoodsInconclusive());
    }

    @Test
    void truncatedNeighborhoodRemainsInconclusive() {
        Fixture fixture = separatedOrdersFixture();

        TargetGroupFeasibilityHarness.Result result =
                TargetGroupFeasibilityHarness.search(
                        fixture.input(),
                        fixture.incumbent(),
                        2,
                        options(1));

        assertEquals(ProofState.INCONCLUSIVE, result.state());
        assertTrue(result.attempts().get(0).candidateTruncated());
        assertEquals(
                InconclusiveReason.CANDIDATE_INCOMPLETE,
                result.attempts().get(0).solverResult()
                        .inconclusiveReason());
    }

    private static TargetGroupFeasibilityHarness.Options options(
            int maxCandidates) {
        return new TargetGroupFeasibilityHarness.Options(
                new OrderGroupDestroySetPlanner.Options(
                        List.of(3), 1, 10),
                maxCandidates,
                10_000L,
                5_000L,
                20_000L);
    }

    private static Fixture mergeFixture() {
        SolverParameters params = params();
        PatternCandidate first =
                new PatternCandidate(Map.of(50, 1), 60);
        PatternCandidate second =
                new PatternCandidate(Map.of(50, 1), 70);
        PatternCandidate third =
                new PatternCandidate(Map.of(50, 1), 80);
        PatternCandidate combined =
                new PatternCandidate(Map.of(50, 1), 90);
        Input input = new Input(
                List.of(item("A", 6)),
                List.of(first, second, third, combined),
                params,
                6,
                300,
                0,
                0);
        Map<Integer, List<String>> config =
                Map.of(50, List.of("A"));
        return new Fixture(
                input,
                List.of(
                        GroupColumn.create(
                                input, first, config, 2),
                        GroupColumn.create(
                                input, second, config, 2),
                        GroupColumn.create(
                                input, third, config, 2)));
    }

    private static Fixture separatedOrdersFixture() {
        SolverParameters params = params();
        PatternCandidate pattern =
                new PatternCandidate(Map.of(50, 1), 60);
        Input input = new Input(
                List.of(
                        item("A", 2),
                        item("B", 2),
                        item("C", 2)),
                List.of(pattern),
                params,
                6,
                300,
                0,
                0);
        return new Fixture(
                input,
                List.of(
                        GroupColumn.create(
                                input, pattern,
                                Map.of(50, List.of("A")), 2),
                        GroupColumn.create(
                                input, pattern,
                                Map.of(50, List.of("B")), 2),
                        GroupColumn.create(
                                input, pattern,
                                Map.of(50, List.of("C")), 2)));
    }

    private static SolverParameters params() {
        SolverParameters params =
                SolverParameters.createDefault();
        params.setMinRollWidth(50);
        params.setMaxRollWidth(100);
        params.setStepSize(10);
        params.setTotalWidth(100);
        params.setTotalOverCap(0);
        params.setMaxDistinctWidths(4);
        params.sanitize();
        return params;
    }

    private static SolverOrderItem item(
            String message,
            int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setMessageText(message);
        item.setWidth(50);
        item.setDemand(demand);
        item.setLength(1000);
        item.setSurfaceTreatment("TEST");
        item.setGroupKey("1000m+TEST");
        return item;
    }

    private record Fixture(
            Input input,
            List<GroupColumn> incumbent) {
    }
}
