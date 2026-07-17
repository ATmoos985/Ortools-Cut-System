package test.demo.apsmodule.solver.kernel;

/**
 * Capabilities advertised by a complete solver kernel implementation.
 */
public record SolverKernelCapabilities(
        boolean parallelExecution,
        boolean cancellation,
        boolean warmStart,
        boolean optimalityProof) {

    public static SolverKernelCapabilities currentNewSolver() {
        return new SolverKernelCapabilities(true, false, false, false);
    }
}
