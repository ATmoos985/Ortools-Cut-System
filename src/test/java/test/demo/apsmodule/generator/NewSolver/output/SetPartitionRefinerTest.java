package test.demo.apsmodule.generator.NewSolver.output;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Result;

import java.util.List;
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
                "pool", "demand", 1, 0, 4600, 20_000L, "warm");
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
                    "pool", "demand", 1, 0, 4600, 20_000L, "warm");
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
}
