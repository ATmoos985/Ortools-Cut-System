package test.demo.apsmodule.generator.NewSolver.mip;

import com.google.ortools.Loader;
import com.google.ortools.init.OrToolsVersion;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpSatCompatibilityTest {

    @Test
    void solvesMinimalModelOnWindowsJavaRuntime() {
        Loader.loadNativeLibraries();
        Assumptions.assumeTrue(isAtLeastVersion(9, 15),
                "CP-SAT Windows smoke test requires OR-Tools 9.15+");

        CpModel model = new CpModel();
        BoolVar selected = model.newBoolVar("selected");
        model.maximize(selected);

        CpSolver solver = new CpSolver();
        solver.getParameters()
                .setNumSearchWorkers(1)
                .setMaxDeterministicTime(1.0);

        assertEquals(CpSolverStatus.OPTIMAL, solver.solve(model));
        assertEquals(1L, solver.value(selected));
    }

    private static boolean isAtLeastVersion(int expectedMajor, int expectedMinor) {
        int major = OrToolsVersion.getMajorNumber();
        return major > expectedMajor
                || (major == expectedMajor && OrToolsVersion.getMinorNumber() >= expectedMinor);
    }
}
