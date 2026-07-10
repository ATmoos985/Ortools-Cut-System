package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.LexicographicResult;

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
        return Column.of(
                Map.of(WIDTH, 2),
                Map.of(WIDTH, List.of(first, second)));
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
        assertEquals(3, result.phases().size());
        assertTrue(result.phases().stream().allMatch(phase -> "OPTIMAL".equals(phase.status())));
    }
}
