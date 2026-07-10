package test.demo.apsmodule.generator.NewSolver.output;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Request-owned executor for independent single-threaded solver tasks.
 * A process-wide semaphore prevents concurrent requests from oversubscribing the host.
 */
final class BoundedSolverExecutor implements AutoCloseable {

    private static final int AVAILABLE_PROCESSORS =
            Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_GLOBAL_PARALLELISM =
            Math.max(1, AVAILABLE_PROCESSORS - 1);
    private static final int GLOBAL_PARALLELISM = normalizeGlobalParallelism(
            Integer.getInteger("cutting.parallel.globalThreads", DEFAULT_GLOBAL_PARALLELISM));
    private static final Semaphore GLOBAL_SLOTS = new Semaphore(GLOBAL_PARALLELISM, true);
    private static final AtomicInteger POOL_IDS = new AtomicInteger();

    private final String scope;
    private final int configuredParallelism;
    private final Semaphore slots;
    private ExecutorService executor;
    private boolean closed;

    static BoundedSolverExecutor create(String scope, int configuredParallelism) {
        return new BoundedSolverExecutor(scope, configuredParallelism, GLOBAL_SLOTS);
    }

    static BoundedSolverExecutor forTesting(String scope, int configuredParallelism,
            Semaphore slots) {
        return new BoundedSolverExecutor(scope, configuredParallelism, slots);
    }

    static int globalParallelism() {
        return GLOBAL_PARALLELISM;
    }

    private BoundedSolverExecutor(String scope, int configuredParallelism, Semaphore slots) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.configuredParallelism = Math.max(1,
                Math.min(configuredParallelism, AVAILABLE_PROCESSORS));
        this.slots = Objects.requireNonNull(slots, "slots");
    }

    int parallelism(int taskCount) {
        return Math.max(1, Math.min(configuredParallelism, Math.max(1, taskCount)));
    }

    int configuredParallelism() {
        return configuredParallelism;
    }

    <T, R> List<R> mapOrdered(List<T> inputs, Function<T, R> mapper) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(mapper, "mapper");
        if (closed) {
            throw new IllegalStateException("executor is closed");
        }
        if (inputs.isEmpty()) {
            return List.of();
        }
        int workerCount = parallelism(inputs.size());
        if (workerCount == 1) {
            List<R> results = new ArrayList<>(inputs.size());
            for (T input : inputs) {
                results.add(withSlot(() -> mapper.apply(input)));
            }
            return results;
        }

        ensureExecutor();
        List<Callable<R>> tasks = new ArrayList<>(inputs.size());
        for (T input : inputs) {
            tasks.add(() -> withSlot(() -> mapper.apply(input)));
        }
        try {
            List<Future<R>> futures = executor.invokeAll(tasks);
            List<R> results = new ArrayList<>(futures.size());
            for (Future<R> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(scope + " solver tasks interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(scope + " solver task failed", cause);
        }
    }

    private synchronized void ensureExecutor() {
        if (executor == null) {
            int poolId = POOL_IDS.incrementAndGet();
            AtomicInteger threadIds = new AtomicInteger();
            ThreadFactory threadFactory = runnable -> {
                Thread thread = new Thread(runnable,
                        "cutting-" + scope + "-" + poolId + "-" + threadIds.incrementAndGet());
                thread.setDaemon(false);
                return thread;
            };
            executor = Executors.newFixedThreadPool(configuredParallelism, threadFactory);
        }
    }

    private <R> R withSlot(Callable<R> task) {
        boolean acquired = false;
        try {
            slots.acquire();
            acquired = true;
            return task.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(scope + " solver slot interrupted", e);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(scope + " solver task failed", e);
        } finally {
            if (acquired) {
                slots.release();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    synchronized boolean isTerminated() {
        return closed && (executor == null || executor.isTerminated());
    }

    private static int normalizeGlobalParallelism(int configured) {
        return Math.max(1, Math.min(configured, AVAILABLE_PROCESSORS));
    }
}
