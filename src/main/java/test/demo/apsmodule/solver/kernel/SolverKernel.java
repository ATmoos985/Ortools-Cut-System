package test.demo.apsmodule.solver.kernel;

/**
 * Stable boundary for replacing the complete cutting solver kernel.
 */
public interface SolverKernel {

    String id();

    SolverKernelCapabilities capabilities();

    boolean supports(SolverKernelSettings settings);

    SolverKernelResult solve(SolverKernelRequest request);
}
