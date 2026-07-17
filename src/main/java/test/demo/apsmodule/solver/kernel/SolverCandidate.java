package test.demo.apsmodule.solver.kernel;

import java.util.Objects;

/**
 * A named candidate and its backend-neutral quality vector.
 *
 * @param <T> candidate payload type owned by the current kernel adapter
 */
public record SolverCandidate<T>(String name, T value, PlanQuality quality) {

    public SolverCandidate {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(quality, "quality");
    }
}
