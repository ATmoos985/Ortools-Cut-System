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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidualTargetSolverTest {

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void exactCandidateReturnsFeasibleEvenWhenEnumerationIsIncomplete() {
        Fixture fixture = fixture();

        ResidualTargetSolver.Result result = ResidualTargetSolver.solve(
                request(fixture, List.of(fixture.column()), false));

        assertEquals(ProofState.FEASIBLE, result.state());
        assertEquals(InconclusiveReason.NONE, result.inconclusiveReason());
        assertEquals(List.of(fixture.column().signature()),
                result.selectedColumns().stream()
                        .map(GroupColumn::signature)
                        .toList());
        assertTrue(ResidualTargetSolver.conserves(
                result.selectedColumns(), fixture.column().coverage(),
                fixture.column().cars(), fixture.column().totalWaste(),
                0, 0));
    }

    @Test
    void completeCandidateModelCanProveInfeasible() {
        Fixture fixture = fixture();

        ResidualTargetSolver.Result result = ResidualTargetSolver.solve(
                request(fixture, List.of(), true));

        assertEquals(ProofState.PROVEN_INFEASIBLE, result.state());
        assertEquals(InconclusiveReason.NONE, result.inconclusiveReason());
    }

    @Test
    void incompleteCandidateModelCannotClaimProof() {
        Fixture fixture = fixture();

        ResidualTargetSolver.Result result = ResidualTargetSolver.solve(
                request(fixture, List.of(), false));

        assertEquals(ProofState.INCONCLUSIVE, result.state());
        assertEquals(InconclusiveReason.CANDIDATE_INCOMPLETE,
                result.inconclusiveReason());
    }

    @Test
    void invalidResidualReturnsInconclusiveInsteadOfFalseProof() {
        Fixture fixture = fixture();
        ResidualTargetSolver.Request invalid =
                new ResidualTargetSolver.Request(
                        fixture.column().coverage(),
                        fixture.column().cars(),
                        fixture.column().totalWaste(),
                        2,
                        0,
                        List.of(fixture.column()),
                        1,
                        true,
                        5_000L);

        ResidualTargetSolver.Result result = ResidualTargetSolver.solve(invalid);

        assertEquals(ProofState.INCONCLUSIVE, result.state());
        assertEquals(InconclusiveReason.INVALID_INPUT,
                result.inconclusiveReason());
    }

    private static ResidualTargetSolver.Request request(
            Fixture fixture,
            List<GroupColumn> candidates,
            boolean candidateComplete) {
        return new ResidualTargetSolver.Request(
                fixture.column().coverage(),
                fixture.column().cars(),
                fixture.column().totalWaste(),
                0,
                0,
                candidates,
                1,
                candidateComplete,
                5_000L);
    }

    private static Fixture fixture() {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(50);
        params.setMaxRollWidth(100);
        params.setStepSize(10);
        params.setTotalWidth(100);
        params.setTotalOverCap(0);
        params.setMaxDistinctWidths(4);
        params.sanitize();

        SolverOrderItem order = new SolverOrderItem();
        order.setMessageText("A");
        order.setWidth(50);
        order.setDemand(2);
        order.setLength(1000);
        order.setSurfaceTreatment("TEST");
        order.setGroupKey("1000m+TEST");

        PatternCandidate pattern =
                new PatternCandidate(Map.of(50, 1), 60);
        Input input = new Input(
                List.of(order),
                List.of(pattern),
                params,
                2,
                80,
                0,
                0);
        GroupColumn column = GroupColumn.create(
                input, pattern, Map.of(50, List.of("A")), 2);
        return new Fixture(input, column);
    }

    private record Fixture(Input input, GroupColumn column) {
    }
}
