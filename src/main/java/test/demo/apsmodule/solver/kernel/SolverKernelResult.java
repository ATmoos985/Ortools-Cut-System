package test.demo.apsmodule.solver.kernel;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import test.demo.apsmodule.service.CuttingInstruction;

/**
 * Backend-neutral result returned by a complete solver kernel.
 */
public record SolverKernelResult(
        String kernelId,
        SolverKernelStatus status,
        List<CuttingInstruction> instructions,
        PlanQuality quality,
        long elapsedMs,
        Map<String, String> diagnostics) {

    public SolverKernelResult {
        Objects.requireNonNull(kernelId, "kernelId");
        Objects.requireNonNull(status, "status");
        instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions"));
        Objects.requireNonNull(quality, "quality");
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
