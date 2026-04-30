package test.demo.apsmodule.generator.NewSolver.colgen;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnGenerationSolverTest {

    @Test
    void determineSoftPatternLimitAllowsIterationsWhenInitialPoolAlreadyExceedsConfiguredLimit() {
        assertEquals(1051, ColumnGenerationSolver.determineSoftPatternLimit(851, 800));
    }

    @Test
    void pricingKeepsMoreThanThreeNegativeReducedCostCandidates() throws Exception {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(1);
        params.setMaxRollWidth(12);
        params.setStepSize(1);
        params.setMaxDistinctWidths(4);
        params.setTotalWidth(12);

        ColumnGenerationSolver solver = new ColumnGenerationSolver(params);
        Method method = ColumnGenerationSolver.class.getDeclaredMethod(
                "solvePricingSubproblem", Map.class, Set.class, Map.class, Set.class);
        method.setAccessible(true);

        Map<Integer, Double> dualPrices = new LinkedHashMap<>();
        Map<Integer, Integer> demands = new LinkedHashMap<>();
        for (int width = 1; width <= 6; width++) {
            dualPrices.put(width, 2.0);
            demands.put(width, 10);
        }

        @SuppressWarnings("unchecked")
        List<PatternCandidate> results = (List<PatternCandidate>) method.invoke(
                solver, dualPrices, demands.keySet(), demands, Set.of());

        assertTrue(results.size() > 3);
        assertTrue(results.size() <= 12);
    }

    @Test
    void pricingCapsSinglePatternWidthCountToAllowedProduction() throws Exception {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(1);
        params.setMaxRollWidth(9);
        params.setStepSize(1);
        params.setMaxDistinctWidths(1);
        params.setTotalWidth(9);
        params.setTotalOverCap(0);

        ColumnGenerationSolver solver = new ColumnGenerationSolver(params);
        Method method = ColumnGenerationSolver.class.getDeclaredMethod(
                "solveDPForRollWidth", int.class, List.class, Map.class, Map.class, Set.class);
        method.setAccessible(true);

        Map<Integer, Double> dualPrices = new LinkedHashMap<>();
        dualPrices.put(2, 1.0);
        dualPrices.put(3, 10.0);

        Map<Integer, Integer> demands = new LinkedHashMap<>();
        demands.put(2, 10);
        demands.put(3, 1);

        PatternCandidate result = (PatternCandidate) method.invoke(
                solver, 9, List.of(2, 3), dualPrices, demands, Set.of());

        assertEquals(1, result.getPattern().getOrDefault(3, 0));
    }
}
