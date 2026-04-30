package test.demo.apsmodule.generator.NewSolver.util;

import com.google.ortools.linearsolver.MPSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralizes solver settings that make repeated runs easier to reproduce.
 */
public final class SolverDeterminism {

    private static final Logger log = LoggerFactory.getLogger(SolverDeterminism.class);
    private static final int RANDOM_SEED = 42;

    private SolverDeterminism() {
    }

    public static void configure(MPSolver solver) {
        if (solver == null) {
            return;
        }

        try {
            boolean accepted = solver.setNumThreads(1);
            if (!accepted) {
                log.debug("MPSolver did not accept single-thread setting for {}", solver.solverVersion());
            }
        } catch (RuntimeException e) {
            log.debug("MPSolver single-thread setting failed", e);
        }

        setBackendSeed(solver);
    }

    private static void setBackendSeed(MPSolver solver) {
        String version;
        try {
            version = solver.solverVersion();
        } catch (RuntimeException e) {
            log.debug("Could not read MPSolver version", e);
            return;
        }

        String parameters = null;
        if (version != null && version.toUpperCase().contains("SCIP")) {
            parameters = "randomization/randomseedshift = " + RANDOM_SEED + "\n"
                    + "parallel/maxnthreads = 1";
        } else if (version != null && version.toUpperCase().contains("CBC")) {
            parameters = "randomSeed=" + RANDOM_SEED;
        }

        if (parameters == null) {
            return;
        }

        try {
            boolean accepted = solver.setSolverSpecificParametersAsString(parameters);
            if (!accepted) {
                log.debug("MPSolver did not accept deterministic backend parameters for {}: {}",
                        version, parameters.replace('\n', ';'));
            }
        } catch (RuntimeException e) {
            log.debug("MPSolver deterministic backend parameters failed for {}", version, e);
        }
    }

    public static double tinyTieBreak(int index) {
        return (index + 1) * 1.0e-9;
    }
}
