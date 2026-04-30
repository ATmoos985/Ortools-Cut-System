package test.demo.apsmodule.generator.NewSolver.util;

import com.google.ortools.linearsolver.MPSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralizes solver settings that make repeated runs easier to reproduce.
 */
public final class SolverDeterminism {

    private static final Logger log = LoggerFactory.getLogger(SolverDeterminism.class);

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
    }
}
