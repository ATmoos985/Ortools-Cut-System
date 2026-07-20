package test.demo.apsmodule.service;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.solver.UnifiedPatternSolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CuttingOptimizationServiceTest {

    @Test
    void rejectsEmptyInstructionResultForNonEmptyOrders() {
        OrderNormalizer normalizer = mock(OrderNormalizer.class);
        UnifiedPatternSolver solver = mock(UnifiedPatternSolver.class);
        CuttingOptimizationService service = new CuttingOptimizationService(normalizer, solver);
        ProductionOrder order = new ProductionOrder();
        SolverOrderItem solverItem = new SolverOrderItem();
        SolverConfig config = new SolverConfig();

        when(normalizer.normalize(List.of(order))).thenReturn(List.of(solverItem));
        when(solver.solve(anyList(), same(config), anyList())).thenReturn(List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.optimizeUnified(List.of(order), config));

        assertEquals("No usable cutting plan was produced for the submitted orders",
                error.getMessage());
    }
}
