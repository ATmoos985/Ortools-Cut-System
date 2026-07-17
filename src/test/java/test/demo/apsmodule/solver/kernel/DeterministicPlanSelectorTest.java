package test.demo.apsmodule.solver.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicPlanSelectorTest {

    private final DeterministicPlanSelector selector = new DeterministicPlanSelector();

    @Test
    void followsProductionLexicographicQualityContract() {
        PlanQuality baseline = quality(1, 10, 20, 3, 2, 4, 8, 100, 2);

        assertBetter(quality(0, 99, 99, 99, 99, 99, 99, 999, 99), baseline);
        assertBetter(quality(1, 9, 99, 99, 99, 99, 99, 999, 99), baseline);
        assertBetter(quality(1, 10, 19, 99, 99, 99, 99, 999, 99), baseline);
        assertBetter(quality(1, 10, 20, 2, 99, 99, 99, 999, 99), baseline);
        assertBetter(quality(1, 10, 20, 3, 1, 99, 99, 999, 99), baseline);
        assertBetter(quality(1, 10, 20, 3, 2, 3, 99, 999, 99), baseline);
        assertBetter(quality(1, 10, 20, 3, 2, 4, 7, 999, 99), baseline);
        assertBetter(quality(1, 10, 20, 3, 2, 4, 8, 99, 99), baseline);
        assertBetter(quality(1, 10, 20, 3, 2, 4, 8, 100, 1), baseline);
    }

    @Test
    void reductionIsStableEvenWhenTasksFinishOutOfOrder() {
        SolverCandidate<String> later = new SolverCandidate<>(
                "later", "later", quality(0, 10, 20, 0, 0, 0, 5, 100, 1));
        SolverCandidate<String> earlier = new SolverCandidate<>(
                "earlier", "earlier", quality(0, 10, 20, 0, 0, 0, 5, 100, 0));

        SolverCandidate<String> selected = selector.selectBest(List.of(later, earlier)).orElseThrow();

        assertEquals("earlier", selected.value());
    }

    private void assertBetter(PlanQuality candidate, PlanQuality baseline) {
        assertTrue(selector.isBetter(candidate, baseline));
    }

    private static PlanQuality quality(int over, int rolls, int groups, int odd,
            int one, int small, int patterns, int waste, int order) {
        return new PlanQuality(over, rolls, groups, odd, one, small, patterns, waste, order);
    }
}
