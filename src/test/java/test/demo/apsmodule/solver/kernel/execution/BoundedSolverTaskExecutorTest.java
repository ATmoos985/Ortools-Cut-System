package test.demo.apsmodule.solver.kernel.execution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedSolverTaskExecutorTest {

    @Test
    void mapsInSubmissionOrderWhileRespectingGlobalConcurrencyLimit() {
        Semaphore globalSlots = new Semaphore(2, true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstPairStarted = new CountDownLatch(2);
        BoundedSolverTaskExecutor executor = BoundedSolverTaskExecutor.forTesting(
                "ordered", 4, globalSlots);

        try (executor) {
            List<Integer> results = executor.mapOrdered(List.of(3, 1, 4, 2), value -> {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                firstPairStarted.countDown();
                await(firstPairStarted);
                try {
                    Thread.sleep((5L - value) * 5L);
                    return value * value;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    active.decrementAndGet();
                }
            });

            assertEquals(List.of(9, 1, 16, 4), results);
            assertEquals(2, maxActive.get());
        }

        assertTrue(executor.isTerminated());
    }

    @Test
    void propagatesImmutableSolverExecutionContextToWorkers() {
        SolverExecutionContext context = SolverExecutionContext.of(Map.of("mode", "quality"));
        try (BoundedSolverTaskExecutor executor = BoundedSolverTaskExecutor.forTesting(
                "context", 2, new Semaphore(2))) {
            List<String> modes = SolverExecutionContext.callWith(context,
                    () -> executor.mapOrdered(List.of(1, 2), ignored ->
                            SolverExecutionContext.current().get("mode")));

            assertEquals(List.of("quality", "quality"), modes);
            assertTrue(SolverExecutionContext.current().isEmpty());
        }
    }

    @Test
    void normalizesParallelismToTaskCountAndAtLeastOneThread() {
        try (BoundedSolverTaskExecutor oneTask = BoundedSolverTaskExecutor.forTesting(
                "one", 8, new Semaphore(8));
             BoundedSolverTaskExecutor zeroConfigured = BoundedSolverTaskExecutor.forTesting(
                     "zero", 0, new Semaphore(1))) {
            assertEquals(1, oneTask.parallelism(1));
            assertEquals(1, zeroConfigured.parallelism(5));
        }
    }

    @Test
    void concurrentRequestsShareTheSameGlobalLimit() {
        Semaphore globalSlots = new Semaphore(2, true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        try (BoundedSolverTaskExecutor first = BoundedSolverTaskExecutor.forTesting(
                     "first", 4, globalSlots);
             BoundedSolverTaskExecutor second = BoundedSolverTaskExecutor.forTesting(
                     "second", 4, globalSlots)) {
            CompletableFuture<List<Integer>> firstResult = CompletableFuture.supplyAsync(
                    () -> first.mapOrdered(List.of(1, 2, 3, 4),
                            value -> measuredWork(value, active, maxActive)));
            CompletableFuture<List<Integer>> secondResult = CompletableFuture.supplyAsync(
                    () -> second.mapOrdered(List.of(5, 6, 7, 8),
                            value -> measuredWork(value, active, maxActive)));

            assertEquals(List.of(1, 2, 3, 4), firstResult.join());
            assertEquals(List.of(5, 6, 7, 8), secondResult.join());
            assertEquals(2, maxActive.get());
        }
    }

    @Test
    void laterLargerBatchCanUseConfiguredParallelism() {
        Semaphore globalSlots = new Semaphore(4);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        try (BoundedSolverTaskExecutor executor = BoundedSolverTaskExecutor.forTesting(
                "growing", 4, globalSlots)) {
            executor.mapOrdered(List.of(1, 2),
                    value -> measuredWork(value, active, maxActive));
            maxActive.set(0);

            executor.mapOrdered(List.of(1, 2, 3, 4),
                    value -> measuredWork(value, active, maxActive));

            assertEquals(4, maxActive.get());
        }
    }

    private static int measuredWork(int value, AtomicInteger active, AtomicInteger maxActive) {
        int now = active.incrementAndGet();
        maxActive.accumulateAndGet(now, Math::max);
        try {
            Thread.sleep(20L);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            active.decrementAndGet();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
