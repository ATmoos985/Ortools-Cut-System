package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.GroupCapResult;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.LexicographicResult;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.MetricCapResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedSetPartitionLexicographicTest {

    private static final int WIDTH = 1000;
    private static final int TOTAL_WIDTH = 2000;

    @BeforeAll
    static void loadOrTools() {
        com.google.ortools.Loader.loadNativeLibraries();
    }

    @Test
    void minimizesOddBlocksAfterProvingMinimumGroups() {
        LexicographicResult result = solve(Map.of(
                key("A"), 10,
                key("B"), 2), 6);

        assertProven(result);
        assertEquals(2, result.result().groups());
        assertEquals(0, result.result().oddBlocks());
        assertEquals(List.of(2, 4), sortedCounts(result));
    }

    @Test
    void minimizesSmallBlocksAfterGroupsAndOddAreFixed() {
        LexicographicResult result = solve(Map.of(
                key("A"), 8,
                key("B"), 20), 14);

        assertProven(result);
        assertEquals(2, result.result().groups());
        assertEquals(0, result.result().oddBlocks());
        assertEquals(0, result.result().smallBlocks());
        assertEquals(List.of(6, 8), sortedCounts(result));
    }

    @Test
    void minimizesSingleCarBlocksAfterGroupsAndOddAreFixed() {
        List<Column> columns = List.of(
                column(List.of("A", "A", "A")),
                column(List.of("B", "B", "B")),
                column(List.of("A", "B", "B")));

        LexicographicResult result = new UnifiedSetPartitionSolver().solveLexicographic(
                columns, Map.of(key("A"), 3, key("B"), 12),
                5, 0, 3000, 10_000L, List.of());

        assertProven(result);
        assertEquals(2, result.result().groups());
        assertEquals(1, result.result().oddBlocks());
        assertEquals(0, result.result().oneCarBlocks());
        assertEquals(List.of(2, 3), sortedCounts(result));
    }

    @Test
    void repeatedSolveKeepsEveryPhaseAndFinalSignatureStable() {
        Map<String, Integer> demand = Map.of(
                key("A"), 8,
                key("B"), 20);

        LexicographicResult first = solve(demand, 14);
        LexicographicResult second = solve(demand, 14);

        assertProven(first);
        assertProven(second);
        assertEquals(first.result().signature(), second.result().signature());
        assertEquals(
                first.phases().stream().map(UnifiedSetPartitionSolver.Result::signature).toList(),
                second.phases().stream().map(UnifiedSetPartitionSolver.Result::signature).toList());
        assertTrue(first.phases().stream().allMatch(phase -> phase.relativeGap() == 0.0));
        assertTrue(second.phases().stream().allMatch(phase -> phase.relativeGap() == 0.0));
    }

    @Test
    void inputColumnOrderDoesNotChangeStrictResultSignature() {
        Map<String, Integer> demand = Map.of(
                key("A"), 8,
                key("B"), 20);
        List<Column> forward = pool();
        List<Column> reversed = List.of(forward.get(2), forward.get(1), forward.get(0));

        LexicographicResult first = solve(demand, 14, forward);
        LexicographicResult second = solve(demand, 14, reversed);

        assertProven(first);
        assertProven(second);
        assertEquals(first.result().signature(), second.result().signature());
    }

    @Test
    void groupCapFindsWitnessAtKnownMinimum() {
        List<Column> columns = pool();
        GroupCapResult result = new UnifiedSetPartitionSolver().checkGroupCap(
                columns,
                Map.of(key("A"), 8, key("B"), 20),
                14, 0, TOTAL_WIDTH, 2, 10_000L,
                List.of(new ColumnUse(columns.get(0), 4),
                        new ColumnUse(columns.get(2), 10)));

        assertTrue(result.proven());
        assertTrue(result.feasible());
        assertNotNull(result.result());
        assertEquals(2, result.result().groups());
    }

    @Test
    void groupCapProvesOneGroupInfeasible() {
        GroupCapResult result = new UnifiedSetPartitionSolver().checkGroupCap(
                pool(),
                Map.of(key("A"), 8, key("B"), 20),
                14, 0, TOTAL_WIDTH, 1, 10_000L);

        assertTrue(result.proven());
        assertTrue(!result.feasible());
        assertNotNull(result.result());
        assertEquals("INFEASIBLE", result.result().status());
    }

    @Test
    void smallPolishRespectsGroupAndOddCaps() {
        List<Column> columns = pool();
        UnifiedSetPartitionSolver.Result result =
                new UnifiedSetPartitionSolver().minimizeSmallAtCaps(
                        columns,
                        Map.of(key("A"), 8, key("B"), 20),
                        14, 0, TOTAL_WIDTH, 2, 0, 10_000L,
                        List.of(new ColumnUse(columns.get(0), 4),
                                new ColumnUse(columns.get(2), 10)));

        assertNotNull(result);
        assertEquals("OPTIMAL", result.status());
        assertEquals(2, result.groups());
        assertEquals(0, result.oddBlocks());
        assertEquals(0, result.smallBlocks());
    }

    @Test
    void metricCapsFindKnownFeasibleWitness() {
        List<Column> columns = pool();
        MetricCapResult result = new UnifiedSetPartitionSolver().checkMetricCaps(
                columns,
                Map.of(key("A"), 8, key("B"), 20),
                14, 0, TOTAL_WIDTH, 2, 0, 0, 0, 10_000L,
                List.of(new ColumnUse(columns.get(0), 4),
                        new ColumnUse(columns.get(2), 10)));

        assertTrue(result.proven());
        assertTrue(result.feasible());
        assertNotNull(result.result());
        assertEquals(2, result.result().groups());
        assertEquals(0, result.result().oddBlocks());
        assertEquals(0, result.result().oneCarBlocks());
        assertEquals(0, result.result().smallBlocks());
    }

    @Test
    void deterministicNodeBudgetChecksCoreCapsWithoutSmallCap() {
        List<Column> columns = pool();
        MetricCapResult result =
                new UnifiedSetPartitionSolver().checkCoreMetricCapsByNodes(
                        columns,
                        Map.of(key("A"), 8, key("B"), 20),
                        14, 0, TOTAL_WIDTH, 2, 0, 0,
                        10, 10_000L,
                        List.of(new ColumnUse(columns.get(0), 4),
                                new ColumnUse(columns.get(2), 10)));

        assertTrue(result.feasible());
        assertTrue(result.proven());
        assertEquals(0, result.result().oneCarBlocks());
        assertTrue(result.result().nodes() <= 10);
    }

    @Test
    void metricCapsProveImpossibleSmallBoundary() {
        MetricCapResult result = new UnifiedSetPartitionSolver().checkMetricCaps(
                pool(),
                Map.of(key("A"), 10, key("B"), 2),
                6, 0, TOTAL_WIDTH, 2, 2, 0, 1, 10_000L, List.of());

        assertTrue(result.proven());
        assertTrue(!result.feasible());
        assertNotNull(result.result());
        assertEquals("INFEASIBLE", result.result().status());
    }

    private LexicographicResult solve(Map<String, Integer> demand, int cars) {
        return solve(demand, cars, pool());
    }

    private LexicographicResult solve(
            Map<String, Integer> demand, int cars, List<Column> columns) {
        return new UnifiedSetPartitionSolver().solveLexicographic(
                columns, demand, cars, 0, TOTAL_WIDTH, 10_000L, List.of());
    }

    private List<Column> pool() {
        return List.of(
                column("A", "A"),
                column("A", "B"),
                column("B", "B"));
    }

    private Column column(String first, String second) {
        return column(List.of(first, second));
    }

    private Column column(List<String> messages) {
        return Column.of(Map.of(WIDTH, messages.size()), Map.of(WIDTH, messages));
    }

    private static String key(String message) {
        return WIDTH + "|" + message;
    }

    private static List<Integer> sortedCounts(LexicographicResult result) {
        return result.result().uses().stream()
                .map(UnifiedSetPartitionSolver.ColumnUse::count)
                .sorted()
                .toList();
    }

    private static void assertProven(LexicographicResult result) {
        assertNotNull(result);
        assertTrue(result.provenOptimal());
        assertNotNull(result.result());
        assertEquals(4, result.phases().size());
        assertTrue(result.phases().stream().allMatch(phase -> "OPTIMAL".equals(phase.status())));
    }
}
