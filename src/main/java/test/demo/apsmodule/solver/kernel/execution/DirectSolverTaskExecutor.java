package test.demo.apsmodule.solver.kernel.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Serial baseline and deterministic fallback for solver task execution.
 */
public final class DirectSolverTaskExecutor implements SolverTaskExecutor {

    @Override
    public <T, R> List<R> mapOrdered(List<T> inputs, Function<T, R> mapper) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(mapper, "mapper");
        List<R> results = new ArrayList<>(inputs.size());
        for (T input : inputs) {
            results.add(mapper.apply(input));
        }
        return List.copyOf(results);
    }

    @Override
    public int parallelism(int taskCount) {
        return 1;
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
