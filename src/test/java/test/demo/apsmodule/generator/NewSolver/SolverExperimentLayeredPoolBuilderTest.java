package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolverExperimentLayeredPoolBuilderTest {

    @Test
    void buildsStableNestedPoolsAroundSingleCarDemandKeys() {
        Column incumbent = column(1000, "A");
        Column candidate = column(1200, "B");
        Column direct = column(Map.of(1000, List.of("A"), 1300, List.of("C")));
        Column neighbor = column(Map.of(1300, List.of("C"), 1400, List.of("D")));
        Column unrelated = column(1500, "E");

        List<SolverExperimentLayeredPoolBuilder.Layer> layers =
                SolverExperimentLayeredPoolBuilder.build(
                        List.of(new ColumnUse(incumbent, 1)),
                        List.of(candidate),
                        List.of(unrelated, neighbor, direct),
                        Map.of("1000|A", 1, "1200|B", 2, "1300|C", 3,
                                "1400|D", 4, "1500|E", 5),
                        3, 4);

        assertEquals(List.of("L0-incumbent", "L1-candidate-union", "L2-related-3",
                        "L2-related-4", "L3-full-archive"),
                layers.stream().map(SolverExperimentLayeredPoolBuilder.Layer::name).toList());
        assertEquals(List.of(1, 2, 3, 4, 5),
                layers.stream().map(layer -> layer.columns().size()).toList());
        assertTrue(layers.get(2).columns().contains(direct));
        assertEquals(layers.get(2).hash(),
                SolverExperimentLayeredPoolBuilder.build(
                        List.of(new ColumnUse(incumbent, 1)), List.of(candidate),
                        List.of(direct, neighbor, unrelated),
                        Map.of("1000|A", 1, "1200|B", 2, "1300|C", 3,
                                "1400|D", 4, "1500|E", 5), 3, 4).get(2).hash());
    }

    private static Column column(int width, String message) {
        return column(Map.of(width, List.of(message)));
    }

    private static Column column(Map<Integer, List<String>> config) {
        Map<Integer, Integer> pattern = new java.util.TreeMap<>();
        config.forEach((width, messages) -> pattern.put(width, messages.size()));
        return Column.of(pattern, config);
    }
}
