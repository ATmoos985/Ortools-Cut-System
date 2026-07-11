package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SolverExperimentGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsConcurrentExperimentInSameWorkspace() throws Exception {
        Path lockPath = tempDir.resolve("solver.lock");
        try (SolverExperimentGuard ignored = SolverExperimentGuard.acquire(lockPath)) {
            assertThrows(IOException.class, () -> SolverExperimentGuard.acquire(lockPath));
        }
        try (SolverExperimentGuard ignored = SolverExperimentGuard.acquire(lockPath)) {
            // The lease is available again after close.
        }
    }
}
