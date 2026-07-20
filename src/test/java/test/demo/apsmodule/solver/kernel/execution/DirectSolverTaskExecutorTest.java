package test.demo.apsmodule.solver.kernel.execution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectSolverTaskExecutorTest {

    @Test
    void preservesNullResultsForCallersThatFilterOptionalTasks() {
        DirectSolverTaskExecutor executor = new DirectSolverTaskExecutor();

        List<String> results = executor.mapOrdered(List.of(1, 2, 3), value ->
                value == 2 ? null : "result-" + value);

        assertEquals(Arrays.asList("result-1", null, "result-3"), results);
    }
}
