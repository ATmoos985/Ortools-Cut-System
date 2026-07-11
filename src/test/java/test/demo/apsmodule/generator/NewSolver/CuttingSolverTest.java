package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.model.SolverResult;
import test.demo.apsmodule.service.SolverConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuttingSolverTest {

    @Test
    void paritySweepDefaultsToOffAndExpandsInQualityMode() {
        String prevQuality = System.getProperty("cutting.quality");
        String prevList = System.getProperty("cutting.aLayerParityPenalties");
        try {
            System.clearProperty("cutting.quality");
            System.clearProperty("cutting.aLayerParityPenalties");
            // 快路径：不扫描，与现状完全一致
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new double[] {0.0}, CuttingSolver.aLayerParityPenalties());
            // 质量模式：默认展开为 {0, 0.1}
            System.setProperty("cutting.quality", "true");
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new double[] {0.0, 0.1}, CuttingSolver.aLayerParityPenalties());
            // 显式列表覆盖质量默认，去重且过滤非法项
            System.setProperty("cutting.aLayerParityPenalties", "0, 0.2, 0.2, x, -1");
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new double[] {0.0, 0.2}, CuttingSolver.aLayerParityPenalties());
        } finally {
            restore("cutting.quality", prevQuality);
            restore("cutting.aLayerParityPenalties", prevList);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    @Test
    void mergeFromCopiesForcedAllowOverWidths() {
        SolverConfig config = new SolverConfig();
        config.setStepSize(10);
        config.setForceAllowOverWidths(Set.of(3200, 3350));

        SolverParameters params = SolverParameters.createDefault();
        params.mergeFrom(config);

        assertEquals(Set.of(3200, 3350), params.getForceAllowOverWidths());
    }

    @Test
    void buildAllowOverSetIncludesForcedWidthsOutsideTopK() throws Exception {
        SolverConfig config = new SolverConfig();
        config.setStepSize(10);
        config.setNewSolverTopK(1);
        config.setForceAllowOverWidths(Set.of(3200));

        SolverParameters params = SolverParameters.createDefault();
        params.mergeFrom(config);

        Map<Integer, Integer> demands = new LinkedHashMap<>();
        demands.put(3300, 10);
        demands.put(3400, 9);
        demands.put(3200, 0);

        CuttingSolver solver = new CuttingSolver(params);
        Method method = CuttingSolver.class.getDeclaredMethod("buildAllowOverSet", Map.class, SolverParameters.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<Integer> allowOverSet = (Set<Integer>) method.invoke(solver, demands, params);

        assertTrue(allowOverSet.contains(3300));
        assertTrue(allowOverSet.contains(3200));
        assertEquals(2, allowOverSet.size());
    }

    @Test
    void isBetterPlanPrefersFewerSequenceGroupsBeforeWaste() throws Exception {
        CuttingSolver solver = new CuttingSolver(SolverParameters.createDefault());
        Object worseWasteButFewerGroups = groupSolvePlan("min-pattern", solverResult(2, 300, 0, 10), 101, 0);
        Object lowerWasteButMoreGroups = groupSolvePlan("best-waste", solverResult(1, 200, 0, 10), 110, 1);

        Method method = betterPlanMethod();
        boolean result = (boolean) method.invoke(solver, worseWasteButFewerGroups, lowerWasteButMoreGroups);

        assertTrue(result);
    }

    @Test
    void isBetterPlanBreaksTiesWithFewerPatterns() throws Exception {
        CuttingSolver solver = new CuttingSolver(SolverParameters.createDefault());
        Object fewerPatterns = groupSolvePlan("min-pattern", solverResult(1, 300, 0, 10), 110, 0);
        Object morePatternsLowerWaste = groupSolvePlan("best-waste", solverResult(2, 200, 0, 10), 110, 1);

        Method method = betterPlanMethod();
        boolean result = (boolean) method.invoke(solver, fewerPatterns, morePatternsLowerWaste);
        boolean reverse = (boolean) method.invoke(solver, morePatternsLowerWaste, fewerPatterns);

        assertTrue(result);
        assertFalse(reverse);
    }

    @Test
    void qualityModeAddsOneNestedWidthCandidateByDefault() {
        String previousQuality = System.getProperty("cutting.quality");
        String previousSteps = System.getProperty("cutting.nestedWidthCandidateSteps");
        try {
            SolverParameters params = SolverParameters.createDefault();
            params.setMinRollWidth(4300);
            params.setMaxRollWidth(4410);
            params.setStepSize(10);

            System.clearProperty("cutting.quality");
            System.clearProperty("cutting.nestedWidthCandidateSteps");
            assertEquals(-1, CuttingSolver.nestedWidthCandidateCap(params));

            System.setProperty("cutting.quality", "true");
            assertEquals(4400, CuttingSolver.nestedWidthCandidateCap(params));

            params.setMaxRollWidth(4400);
            assertEquals(-1, CuttingSolver.nestedWidthCandidateCap(params));

            params.setMaxRollWidth(4410);
            System.setProperty("cutting.nestedWidthCandidateSteps", "0");
            assertEquals(-1, CuttingSolver.nestedWidthCandidateCap(params));
        } finally {
            restore("cutting.quality", previousQuality);
            restore("cutting.nestedWidthCandidateSteps", previousSteps);
        }
    }

    @Test
    void isBetterPlanRejectsOverproductionBeforeSequenceGroupGain() throws Exception {
        CuttingSolver solver = new CuttingSolver(SolverParameters.createDefault());
        Object exact = groupSolvePlan("exact", solverResult(2, 300, 0, 10), 11, 0, 0);
        Object overproduced = groupSolvePlan(
                "over", solverResult(1, 200, 1, 10), 10, 0, 0);

        Method method = betterPlanMethod();
        assertTrue((boolean) method.invoke(solver, exact, overproduced));
        assertFalse((boolean) method.invoke(solver, overproduced, exact));
    }

    @Test
    void isBetterPlanPrefersZeroSingleCarGroupsBeforeSequenceGroupGain() throws Exception {
        CuttingSolver solver = new CuttingSolver(SolverParameters.createDefault());
        Object zeroSingle = groupSolvePlan(
                "zero-single", solverResult(2, 300, 0, 10), 11, 0, 0);
        Object singleCar = groupSolvePlan(
                "single", solverResult(2, 300, 0, 10), 10, 1, 1);

        Method method = betterPlanMethod();
        assertTrue((boolean) method.invoke(solver, zeroSingle, singleCar));
        assertFalse((boolean) method.invoke(solver, singleCar, zeroSingle));
    }

    private static Method betterPlanMethod() throws Exception {
        Class<?> planClass = Class.forName("test.demo.apsmodule.generator.NewSolver.CuttingSolver$GroupSolvePlan");
        Method method = CuttingSolver.class.getDeclaredMethod("isBetterPlan", planClass, planClass);
        method.setAccessible(true);
        return method;
    }

    private static Object groupSolvePlan(String name, SolverResult result, int groups, int order) throws Exception {
        return groupSolvePlan(name, result, groups, 0, 0, order);
    }

    private static Object groupSolvePlan(String name, SolverResult result, int groups,
            int oddGroups, int oneCarGroups) throws Exception {
        return groupSolvePlan(name, result, groups, oddGroups, oneCarGroups, 0);
    }

    private static Object groupSolvePlan(String name, SolverResult result, int groups,
            int oddGroups, int oneCarGroups, int order) throws Exception {
        Class<?> planClass = Class.forName("test.demo.apsmodule.generator.NewSolver.CuttingSolver$GroupSolvePlan");
        Constructor<?> constructor = planClass.getDeclaredConstructor(
                String.class, SolverResult.class, List.class, int.class, int.class,
                int.class, int.class, String.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                name, result, List.of(), groups, oddGroups, oneCarGroups, 0, name, order);
    }

    private static SolverResult solverResult(int patternCount, int totalWaste, int totalOver, int totalRolls) {
        Map<PatternCandidate, Integer> solution = new LinkedHashMap<>();
        for (int i = 0; i < patternCount; i++) {
            Map<Integer, Integer> pattern = new LinkedHashMap<>();
            pattern.put(1000 + i, 1);
            solution.put(new PatternCandidate(pattern, 2000 + i), totalRolls / Math.max(1, patternCount));
        }
        return new SolverResult(solution, totalRolls, totalWaste, totalOver, 0L);
    }
}
