package test.demo.apsmodule.solver.kernel;

/**
 * Backend-neutral solve status exposed by a complete solver kernel.
 */
public enum SolverKernelStatus {
    SUCCEEDED,
    FEASIBLE,
    OPTIMAL,
    TIMED_OUT,
    FAILED
}
