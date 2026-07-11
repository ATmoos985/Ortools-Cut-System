package test.demo.apsmodule.generator.NewSolver.output;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Result;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SetPartitionRefinerTest {

    @Test
    void identicalSubproblemIsSolvedOncePerRefineRequest() {
        Column column = Column.of(
                Map.of(1000, 4),
                Map.of(1000, List.of("A", "A", "A", "A")));
        List<Column> pool = List.of(column);
        Map<String, Integer> demand = Map.of("1000|A", 8);
        List<ColumnUse> warmStart = List.of(new ColumnUse(column, 2));
        SetPartitionRefiner.SubproblemKey key = SetPartitionRefiner.subproblemKey(
                pool, demand, 2, 1200, 4600, 20_000L, warmStart);
        ConcurrentMap<SetPartitionRefiner.SubproblemKey, Result> cache = new ConcurrentHashMap<>();
        SetPartitionRefiner.RefineStats stats = new SetPartitionRefiner.RefineStats();
        AtomicInteger solveCalls = new AtomicInteger();
        Result expected = new Result(warmStart, 1, 0, 1, 2, 1200, "OPTIMAL");

        Result first = SetPartitionRefiner.solveCached(key, cache, stats, () -> {
            solveCalls.incrementAndGet();
            return expected;
        });
        Result second = SetPartitionRefiner.solveCached(key, cache, stats, () -> {
            solveCalls.incrementAndGet();
            return expected;
        });

        assertSame(expected, first);
        assertSame(expected, second);
        assertEquals(1, solveCalls.get());
        assertEquals(2, stats.subproblemRequests());
        assertEquals(1, stats.solverCalls());
        assertEquals(1, stats.cacheHits());
    }

    @Test
    void timeLimitedFeasibleResultIsRetriedInsteadOfCached() {
        ConcurrentMap<SetPartitionRefiner.SubproblemKey, Result> cache = new ConcurrentHashMap<>();
        SetPartitionRefiner.RefineStats stats = new SetPartitionRefiner.RefineStats();
        AtomicInteger solveCalls = new AtomicInteger();
        SetPartitionRefiner.SubproblemKey key = new SetPartitionRefiner.SubproblemKey(
                "pool", "demand", 1, 0, 4600, 20_000L, "warm", "GROUPS", -1);
        Result feasible = new Result(List.of(), 1, 0, 1, 1, 0, "FEASIBLE");

        SetPartitionRefiner.solveCached(key, cache, stats, () -> {
            solveCalls.incrementAndGet();
            return feasible;
        });
        SetPartitionRefiner.solveCached(key, cache, stats, () -> {
            solveCalls.incrementAndGet();
            return feasible;
        });

        assertEquals(2, solveCalls.get());
        assertEquals(2, stats.solverCalls());
        assertEquals(0, stats.cacheHits());
        assertEquals(0, cache.size());
    }

    @Test
    void solverInputsThatAffectSearchProduceDifferentKeys() {
        Column left = Column.of(
                Map.of(1000, 4),
                Map.of(1000, List.of("A", "A", "A", "A")));
        Column right = Column.of(
                Map.of(1000, 3, 1200, 1),
                Map.of(1000, List.of("A", "A", "A"), 1200, List.of("B")));
        Map<String, Integer> demand = Map.of("1000|A", 7, "1200|B", 1);

        SetPartitionRefiner.SubproblemKey first = SetPartitionRefiner.subproblemKey(
                List.of(left, right), demand, 2, 800, 4600, 20_000L,
                List.of(new ColumnUse(left, 1), new ColumnUse(right, 1)));
        SetPartitionRefiner.SubproblemKey differentPoolOrder = SetPartitionRefiner.subproblemKey(
                List.of(right, left), demand, 2, 800, 4600, 20_000L,
                List.of(new ColumnUse(left, 1), new ColumnUse(right, 1)));
        SetPartitionRefiner.SubproblemKey differentBudget = SetPartitionRefiner.subproblemKey(
                List.of(left, right), demand, 2, 800, 4600, 40_000L,
                List.of(new ColumnUse(left, 1), new ColumnUse(right, 1)));

        org.junit.jupiter.api.Assertions.assertNotEquals(first, differentPoolOrder);
        org.junit.jupiter.api.Assertions.assertNotEquals(first, differentBudget);
    }

    @Test
    void cacheCanBeDisabledForControlledComparison() {
        String previous = System.getProperty("cutting.spr.cache");
        try {
            System.setProperty("cutting.spr.cache", "false");
            ConcurrentMap<SetPartitionRefiner.SubproblemKey, Result> cache = new ConcurrentHashMap<>();
            SetPartitionRefiner.RefineStats stats = new SetPartitionRefiner.RefineStats();
            AtomicInteger solveCalls = new AtomicInteger();
            SetPartitionRefiner.SubproblemKey key = new SetPartitionRefiner.SubproblemKey(
                    "pool", "demand", 1, 0, 4600, 20_000L, "warm", "GROUPS", -1);
            Result expected = new Result(List.of(), 1, 0, 1, 1, 0, "OPTIMAL");

            SetPartitionRefiner.solveCached(key, cache, stats, () -> {
                solveCalls.incrementAndGet();
                return expected;
            });
            SetPartitionRefiner.solveCached(key, cache, stats, () -> {
                solveCalls.incrementAndGet();
                return expected;
            });

            assertEquals(2, solveCalls.get());
            assertEquals(2, stats.solverCalls());
            assertEquals(0, stats.cacheHits());
        } finally {
            if (previous == null) {
                System.clearProperty("cutting.spr.cache");
            } else {
                System.setProperty("cutting.spr.cache", previous);
            }
        }
    }

    @Test
    void groupCapProbeOnlyShortCircuitsOnAValidLowerGroupWitness() {
        Result witness = new Result(List.of(), 3, 1, 1, 10, 100, "FEASIBLE");
        Result infeasible = new Result(List.of(), 0, 0, 0, 0, 0, "INFEASIBLE");
        Result inconclusive = new Result(List.of(), 0, 0, 0, 0, 0, "NOT_SOLVED");

        assertEquals(SetPartitionRefiner.ProbeDecision.ACCEPT_GROUP_WITNESS,
                SetPartitionRefiner.probeDecision(witness, 3));
        assertEquals(SetPartitionRefiner.ProbeDecision.FULL_LEXICAL,
                SetPartitionRefiner.probeDecision(witness, 2));
        assertEquals(SetPartitionRefiner.ProbeDecision.ODD_ONLY,
                SetPartitionRefiner.probeDecision(infeasible, 3));
        assertEquals(SetPartitionRefiner.ProbeDecision.FULL_LEXICAL,
                SetPartitionRefiner.probeDecision(inconclusive, 3));
        assertEquals(SetPartitionRefiner.ProbeDecision.FULL_LEXICAL,
                SetPartitionRefiner.probeDecision(null, 3));
    }

    @Test
    void groupCapProbeAvoidsOddSymmetryInLargeNeighborhoods() {
        String previousBudget = System.getProperty("cutting.spr.groupCapProbeMs");
        String previousOdd = System.getProperty("cutting.spr.groupCapProbeMaxGlobalOdd");
        try {
            System.setProperty("cutting.spr.groupCapProbeMs", "2000");
            System.setProperty("cutting.spr.groupCapProbeMaxGlobalOdd", "3");
            Column column = Column.of(Map.of(1000, 1), Map.of(1000, List.of("A")));
            List<ColumnUse> threeOdd = List.of(
                    new ColumnUse(column, 1), new ColumnUse(column, 3),
                    new ColumnUse(column, 5), new ColumnUse(column, 2));
            List<ColumnUse> fourOdd = List.of(
                    new ColumnUse(column, 1), new ColumnUse(column, 3),
                    new ColumnUse(column, 5), new ColumnUse(column, 7));

            org.junit.jupiter.api.Assertions.assertTrue(
                    SetPartitionRefiner.shouldProbeGroupCap(3, fourOdd));
            org.junit.jupiter.api.Assertions.assertTrue(
                    SetPartitionRefiner.shouldProbeGroupCap(5, threeOdd));
            org.junit.jupiter.api.Assertions.assertFalse(
                    SetPartitionRefiner.shouldProbeGroupCap(5, fourOdd));
        } finally {
            restoreProperty("cutting.spr.groupCapProbeMs", previousBudget);
            restoreProperty("cutting.spr.groupCapProbeMaxGlobalOdd", previousOdd);
        }
    }

    @Test
    void reverseTieIterationLimitIsConfigurableAndPositive() {
        String previous = System.getProperty("cutting.spr.reverseTieMaxIterations");
        try {
            System.setProperty("cutting.spr.reverseTieMaxIterations", "1");
            assertEquals(1, SetPartitionRefiner.reverseTieMaxIterations());
            System.setProperty("cutting.spr.reverseTieMaxIterations", "3");
            assertEquals(3, SetPartitionRefiner.reverseTieMaxIterations());
            System.setProperty("cutting.spr.reverseTieMaxIterations", "0");
            assertEquals(1, SetPartitionRefiner.reverseTieMaxIterations());
        } finally {
            restoreProperty("cutting.spr.reverseTieMaxIterations", previous);
        }
    }

    @Test
    void donorVariantsExploreStableAlternativeRankings() {
        Column targetColumn = Column.of(
                Map.of(1000, 1, 1200, 1),
                Map.of(1000, List.of("A"), 1200, List.of("B")));
        Column keyFirstColumn = Column.of(
                Map.of(1000, 1, 1300, 1),
                Map.of(1000, List.of("A"), 1300, List.of("X")));
        Column widthFirstColumn = Column.of(
                Map.of(1000, 1, 1200, 1),
                Map.of(1000, List.of("X"), 1200, List.of("Y")));
        ColumnUse target = new ColumnUse(targetColumn, 1);
        ColumnUse keyFirst = new ColumnUse(keyFirstColumn, 2);
        ColumnUse widthFirst = new ColumnUse(widthFirstColumn, 2);

        assertEquals(keyFirst,
                SetPartitionRefiner.rankDonors(target, List.of(keyFirst, widthFirst),
                        SetPartitionRefiner.DonorVariant.DEFAULT).get(0));
        assertEquals(widthFirst,
                SetPartitionRefiner.rankDonors(target, List.of(keyFirst, widthFirst),
                        SetPartitionRefiner.DonorVariant.WIDTH_FIRST).get(0));
    }

    @Test
    void oneCarPolishPoolReductionPreservesIncumbentAndDemandCoverage() {
        Column incumbent = Column.of(Map.of(1000, 1), Map.of(1000, List.of("A")));
        Column coversA = Column.of(Map.of(1000, 2), Map.of(1000, List.of("A", "A")));
        Column coversB = Column.of(Map.of(1200, 1), Map.of(1200, List.of("B")));
        Column filler = Column.of(Map.of(1300, 1), Map.of(1300, List.of("C")));

        List<Column> selected = SetPartitionRefiner.selectPolishColumns(
                List.of(filler, coversB, coversA, incumbent), List.of(incumbent),
                Map.of("1000|A", 10, "1200|B", 8), 3, 1);

        org.junit.jupiter.api.Assertions.assertEquals(3, selected.size());
        org.junit.jupiter.api.Assertions.assertTrue(selected.contains(incumbent));
        org.junit.jupiter.api.Assertions.assertTrue(selected.stream()
                .anyMatch(column -> column.demandUse().containsKey("1000|A")));
        org.junit.jupiter.api.Assertions.assertTrue(selected.stream()
                .anyMatch(column -> column.demandUse().containsKey("1200|B")));
    }

    @Test
    void oneCarPolishPrioritizesTargetDemandKeys() {
        Column incumbent = Column.of(Map.of(1000, 1), Map.of(1000, List.of("A")));
        Column targetAlternative = Column.of(
                Map.of(1000, 1, 1200, 1),
                Map.of(1000, List.of("A"), 1200, List.of("B")));
        Column general = Column.of(Map.of(1300, 1), Map.of(1300, List.of("C")));

        List<Column> selected = SetPartitionRefiner.selectPolishColumns(
                List.of(general, targetAlternative, incumbent), List.of(incumbent),
                Map.of("1000|A", 1, "1200|B", 8, "1300|C", 9), Set.of("1000|A"),
                2, 0, 1);

        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(incumbent, targetAlternative).stream()
                        .map(Column::signature).collect(java.util.stream.Collectors.toSet()),
                selected.stream().map(Column::signature)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void moveAcceptanceIncludesSmallBlocksAsThirdLexicalObjective() {
        org.junit.jupiter.api.Assertions.assertTrue(
                SetPartitionRefiner.lexBetter(5, 2, 9, 6, 0, 0));
        org.junit.jupiter.api.Assertions.assertTrue(
                SetPartitionRefiner.lexBetter(6, 1, 9, 6, 2, 0));
        org.junit.jupiter.api.Assertions.assertTrue(
                SetPartitionRefiner.lexBetter(6, 2, 3, 6, 2, 4));
        org.junit.jupiter.api.Assertions.assertFalse(
                SetPartitionRefiner.lexBetter(6, 2, 4, 6, 2, 4));
        org.junit.jupiter.api.Assertions.assertFalse(
                SetPartitionRefiner.lexBetter(6, 2, 5, 6, 2, 4));
    }

    @Test
    void moveAcceptanceLeavesSingleCarToTheFixedCapPolish() {
        org.junit.jupiter.api.Assertions.assertFalse(
                SetPartitionRefiner.qualityBetter(0, 6, 2, 5, 1, 5, 1, 3));
        org.junit.jupiter.api.Assertions.assertTrue(
                SetPartitionRefiner.qualityBetter(1, 5, 1, 3, 0, 6, 2, 5));
        org.junit.jupiter.api.Assertions.assertFalse(
                SetPartitionRefiner.qualityBetter(0, 5, 1, 5, 1, 5, 1, 3));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
