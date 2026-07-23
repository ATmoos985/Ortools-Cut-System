package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ColumnPool;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ColumnRole;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DualVector;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Metrics;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Phase;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Result;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.TerminationStatus;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderGroupColumnPricingPrototypeTest {

    @Test
    void canonicalConfigTreatsStationMessagesAsMultisets() {
        Map<Integer, List<String>> left = new LinkedHashMap<>();
        left.put(50, List.of("B", "A", "A"));
        Map<Integer, List<String>> right = new LinkedHashMap<>();
        right.put(50, List.of("A", "B", "A"));

        assertEquals(
                OrderGroupColumnPricingPrototype.canonicalConfigSignature(left),
                OrderGroupColumnPricingPrototype.canonicalConfigSignature(right));
    }

    @Test
    void samePatternAndConfigWithDifferentCarsHaveDifferentSignatures() {
        Input input = input(
                List.of(item("A", 50, 8), item("B", 50, 8)),
                List.of(pattern(100, 50, 2)),
                8,
                0,
                0,
                0);
        PatternCandidate pattern = input.universe().get(0);
        GroupColumn twoCars = GroupColumn.create(
                input, pattern, Map.of(50, List.of("A", "B")), 2);
        GroupColumn fourCars = GroupColumn.create(
                input, pattern, Map.of(50, List.of("A", "B")), 4);

        assertNotEquals(twoCars.signature(), fourCars.signature());
        assertEquals(twoCars.familySignature(), fourCars.familySignature());
    }

    @Test
    void dominanceRequiresTheCompleteResourceVectorToMatch() {
        PatternCandidate first = pattern(100, 50, 2);
        PatternCandidate second = pattern(110, 50, 2);
        Input input = input(
                List.of(item("A", 50, 4), item("B", 50, 4)),
                List.of(first, second),
                2,
                0,
                0,
                0);
        GroupColumn firstColumn = GroupColumn.create(
                input, first, Map.of(50, List.of("A", "B")), 2);
        GroupColumn secondColumn = GroupColumn.create(
                input, second, Map.of(50, List.of("A", "B")), 2);
        GroupColumn differentCars = GroupColumn.create(
                input, first, Map.of(50, List.of("A", "B")), 3);

        assertTrue(OrderGroupColumnPricingPrototype.sameResourceVector(
                firstColumn, secondColumn));
        assertFalse(OrderGroupColumnPricingPrototype.sameResourceVector(
                firstColumn, differentCars));

        ColumnPool pool = new ColumnPool(10);
        assertTrue(pool.add(secondColumn));
        assertTrue(pool.add(firstColumn));
        assertEquals(1, pool.size());
        assertEquals(
                Set.of(firstColumn.signature()),
                pool.columns().stream().map(GroupColumn::signature).collect(
                        java.util.stream.Collectors.toSet()));
    }

    @Test
    void prunedWorkingSetMayRegenerateTheSameColumnLater() {
        Input input = tinyInput(0);
        GroupColumn column = GroupColumn.create(
                input,
                input.universe().get(0),
                Map.of(50, List.of("A", "B")),
                2);
        ColumnPool pool = new ColumnPool(10);

        assertTrue(pool.add(column));
        pool.retain(Set.of());
        assertEquals(0, pool.size());
        assertTrue(pool.add(column));
        assertEquals(1, pool.size());
    }

    @Test
    void smoothedDualMayRankButRawDualStillGatesAdmission() {
        Input input = tinyInput(0);
        GroupColumn column = GroupColumn.create(
                input,
                input.universe().get(0),
                Map.of(50, List.of("A", "B")),
                2);
        DualVector raw = new DualVector(Map.of(), 0, 0, 0, 0, Map.of());
        DualVector stable = new DualVector(
                Map.of(new DemandKey(50, "A"), 1.0, new DemandKey(50, "B"), 1.0),
                0,
                0,
                0,
                0,
                Map.of());

        assertTrue(OrderGroupColumnPricingPrototype.rawReducedCost(
                column, raw, Phase.OPTIMIZATION) > 0);
        assertTrue(OrderGroupColumnPricingPrototype.rawReducedCost(
                column, stable, Phase.OPTIMIZATION) < 0);
    }

    @Test
    void rawReducedCostPredictsTheRestrictedMasterObjectiveImprovement() {
        Loader.loadNativeLibraries();
        PatternCandidate pattern = pattern(100, 50, 2);
        Input input = inputWithTotalWidth(
                List.of(item("A", 50, 4), item("B", 50, 4)),
                List.of(pattern),
                4,
                80,
                0,
                0,
                120);
        GroupColumn onlyA = GroupColumn.create(
                input, pattern, Map.of(50, List.of("A", "A")), 2);
        GroupColumn onlyB = GroupColumn.create(
                input, pattern, Map.of(50, List.of("B", "B")), 2);
        GroupColumn combined = GroupColumn.create(
                input, pattern, Map.of(50, List.of("A", "B")), 4);
        Options options = Options.unitTestDefaults();

        OrderGroupRestrictedMaster.LpResult initial =
                OrderGroupRestrictedMaster.solveLp(
                        input, options, List.of(onlyA, onlyB), Phase.OPTIMIZATION);
        double reducedCost = OrderGroupColumnPricingPrototype.rawReducedCost(
                combined, initial.dual(), Phase.OPTIMIZATION);
        OrderGroupRestrictedMaster.LpResult expanded =
                OrderGroupRestrictedMaster.solveLp(
                        input,
                        options,
                        List.of(onlyA, onlyB, combined),
                        Phase.OPTIMIZATION);

        assertTrue(reducedCost < -0.9, () -> "reducedCost=" + reducedCost);
        assertTrue(
                expanded.objectiveValue() < initial.objectiveValue() - 0.9,
                () -> "initial=" + initial.objectiveValue()
                        + ", expanded=" + expanded.objectiveValue());
    }

    @Test
    void singletonFamiliesAlreadyExposeTheirPricingRows() {
        Loader.loadNativeLibraries();
        Input input = tinyInput(0);
        GroupColumn column = GroupColumn.create(
                input,
                input.universe().get(0),
                Map.of(50, List.of("A", "B")),
                2);

        OrderGroupRestrictedMaster.LpResult result =
                OrderGroupRestrictedMaster.solveLp(
                        input,
                        Options.unitTestDefaults(),
                        List.of(column),
                        Phase.OPTIMIZATION);

        assertTrue(result.dual().family().containsKey(column.familySignature()));
    }

    @Test
    void oracleDoesNotOfferOneCarWhenTargetForbidsIt() {
        Input input = tinyInput(0);
        OrderGroupColumnPricingOracle oracle =
                new OrderGroupColumnPricingOracle(input, Options.unitTestDefaults());

        assertFalse(oracle.candidateCars(input.universe().get(0)).contains(1));
    }

    @Test
    void tinyMasterSatisfiesAllExactRowsWithOneCompleteGroupColumn() {
        Result result = OrderGroupColumnPricingPrototype.solve(
                tinyInput(0), Options.unitTestDefaults());

        assertTrue(result.feasible(), () -> result.status().name());
        assertEquals(TerminationStatus.INTEGER_MASTER_OPTIMAL, result.status());
        assertEquals(new Metrics(1, 0, 0, 1, 2, 0), result.directMetrics());
        assertEquals(result.directMetrics(), result.displayedMetrics());
        assertTrue(result.semanticConsistent());
        assertEquals(1, result.selectedColumns().size());
        assertEquals(0.0, result.maxArtificial(), 1e-8);
    }

    @Test
    void artificialSlackPreventsAnInvalidIntegerResult() {
        Result result = OrderGroupColumnPricingPrototype.solve(
                tinyInput(1), Options.unitTestDefaults());

        assertFalse(result.feasible());
        assertEquals(TerminationStatus.ARTIFICIAL_SLACK_REMAINS, result.status());
        assertTrue(result.maxArtificial() > 0.5);
        assertTrue(result.selectedColumns().isEmpty());
    }

    @Test
    void patternUsageParityIsNotConfigurationGroupParity() {
        PatternCandidate pattern = pattern(50, 50, 1);
        Input input = input(
                List.of(item("A", 50, 1), item("B", 50, 1)),
                List.of(pattern),
                2,
                100,
                2,
                2);
        GroupColumn first = GroupColumn.create(
                input, pattern, Map.of(50, List.of("A")), 1);
        GroupColumn second = GroupColumn.create(
                input, pattern, Map.of(50, List.of("B")), 1);

        Metrics metrics = Metrics.fromColumns(List.of(first, second));
        assertEquals(0, metrics.cars() % 2);
        assertEquals(2, metrics.oddGroups());
        assertEquals(2, metrics.groups());
    }

    @Test
    void roleBucketsKeepDifferentColumnRolesVisible() {
        PatternCandidate first = pattern(100, 50, 1);
        PatternCandidate second = pattern(110, 50, 1);
        PatternCandidate third = pattern(120, 50, 1);
        Input input = input(
                List.of(item("A", 50, 10), item("B", 50, 20)),
                List.of(first, second, third),
                19,
                950,
                1,
                0);
        GroupColumn residual = GroupColumn.create(
                input, first, Map.of(50, List.of("A")), 10);
        GroupColumn odd = GroupColumn.create(
                input, second, Map.of(50, List.of("B")), 3);
        GroupColumn even = GroupColumn.create(
                input, third, Map.of(50, List.of("B")), 6);
        Map<String, Double> reducedCosts = Map.of(
                residual.signature(), -3.0,
                odd.signature(), -2.0,
                even.signature(), -1.0);

        List<GroupColumn> selected =
                OrderGroupColumnPricingPrototype.roleDiverseSelection(
                        List.of(residual, odd, even), 3, 1, reducedCosts);

        assertEquals(3, selected.size());
        assertEquals(
                Set.of(
                        ColumnRole.RESIDUAL_CLOSURE,
                        ColumnRole.ODD_REQUIRED,
                        ColumnRole.EVEN_CORE),
                selected.stream().map(GroupColumn::role).collect(
                        java.util.stream.Collectors.toSet()));
    }

    @Test
    void repeatedSolveIsDeterministic() {
        Result first = OrderGroupColumnPricingPrototype.solve(
                tinyInput(0), Options.unitTestDefaults());
        Result second = OrderGroupColumnPricingPrototype.solve(
                tinyInput(0), Options.unitTestDefaults());

        assertEquals(first.status(), second.status());
        assertEquals(
                first.selectedColumns().stream().map(GroupColumn::signature).toList(),
                second.selectedColumns().stream().map(GroupColumn::signature).toList());
        assertEquals(first.directMetrics(), second.directMetrics());
    }

    private static Input tinyInput(int exactWaste) {
        return input(
                List.of(item("A", 50, 2), item("B", 50, 2)),
                List.of(pattern(100, 50, 2)),
                2,
                exactWaste,
                0,
                0);
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
        params.setMaxRollWidth(120);
        params.setStepSize(10);
        params.setTotalWidth(100);
        params.setTotalOverCap(0);
        params.setMaxDistinctWidths(4);
        params.sanitize();
        return new Input(items, universe, params, cars, waste, odd, one);
    }

    private static Input inputWithTotalWidth(
            List<SolverOrderItem> items,
            List<PatternCandidate> universe,
            int cars,
            int waste,
            int odd,
            int one,
            int totalWidth) {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(50);
        params.setMaxRollWidth(120);
        params.setStepSize(10);
        params.setTotalWidth(totalWidth);
        params.setTotalOverCap(0);
        params.setMaxDistinctWidths(4);
        params.sanitize();
        return new Input(items, universe, params, cars, waste, odd, one);
    }

    private static PatternCandidate pattern(
            int rollWidth, int width, int stationCount) {
        return new PatternCandidate(Map.of(width, stationCount), rollWidth);
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
}
