package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolverExperimentColumnSelectorTest {

    @Test
    void keepsIncumbentAndSelectsStableBoundedPool() {
        Column incumbent = column("A", "B");
        List<Column> pool = List.of(
                column("C", "C"), column("A", "A"), incumbent,
                column("B", "B"), column("A", "C"));
        Map<String, Integer> demand = Map.of(
                "1000|A", 20, "1000|B", 10, "1000|C", 4);

        List<Column> first = SolverExperimentColumnSelector.select(
                pool, List.of(incumbent), demand, 3, 1);
        List<Column> second = SolverExperimentColumnSelector.select(
                List.of(pool.get(4), pool.get(3), pool.get(2), pool.get(1), pool.get(0)),
                List.of(incumbent), demand, 3, 1);

        assertEquals(3, first.size());
        assertTrue(first.stream().anyMatch(column -> column.signature().equals(incumbent.signature())));
        assertEquals(first.stream().map(Column::signature).toList(),
                second.stream().map(Column::signature).toList());
    }

    private static Column column(String first, String second) {
        return Column.of(Map.of(1000, 2), Map.of(1000, List.of(first, second)));
    }
}
