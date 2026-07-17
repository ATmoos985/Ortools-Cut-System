package test.demo.apsmodule.solver.kernel.execution;

import java.util.List;
import java.util.function.Function;

/**
 * Executes independent solver tasks and always returns results in input order.
 */
public interface SolverTaskExecutor extends AutoCloseable {

    <T, R> List<R> mapOrdered(List<T> inputs, Function<T, R> mapper);

    int parallelism(int taskCount);

    @Override
    void close();
}
