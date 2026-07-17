package test.demo.apsmodule.solver.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.solver.kernel.execution.SolverExecutionContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SolverKernelRequestTest {

    @Test
    void snapshotsOrderAndSettingsAcrossTheWholeKernelBoundary() {
        SolverOrderItem item = new SolverOrderItem();
        item.setMessageText("M-1");
        item.setWidth(1230);
        item.setDemand(7);
        item.setLength(1000);
        item.setSurfaceTreatment("NONE");
        item.setGroupKey("G-1");
        item.setSalesperson("S-1");
        item.setThickness(2);
        item.setDescription("sample");
        List<SolverOrderItem> source = new ArrayList<>(List.of(item));
        SolverConfig config = new SolverConfig();
        config.setMode("variable");
        config.setUseNewSolver(true);
        config.setMinWidth(4300);
        config.setMaxWidth(4410);
        config.setStepSize(10);
        config.setForceAllowOverWidths(Set.of(1230));

        SolverKernelRequest request = SolverKernelRequest.from(
                source, config, SolverExecutionContext.empty());
        source.clear();
        SolverOrderItem restored = request.toSolverOrderItems().get(0);

        assertEquals("M-1", restored.getMessageText());
        assertEquals(1230, restored.getWidth());
        assertEquals(7, restored.getDemand());
        assertEquals("G-1", restored.getGroupKey());
        assertEquals(4410, request.settings().maxWidth());
        assertEquals(Set.of(1230), request.settings().forceAllowOverWidths());
        assertThrows(UnsupportedOperationException.class,
                () -> request.items().add(request.items().get(0)));
    }
}
