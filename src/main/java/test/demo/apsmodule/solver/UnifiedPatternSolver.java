package test.demo.apsmodule.solver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.PatternSolver;
import test.demo.apsmodule.generator.PatternSolverFour;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.ProductionOrder;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;
import test.demo.apsmodule.solver.kernel.SolverKernel;
import test.demo.apsmodule.solver.kernel.SolverKernelRequest;
import test.demo.apsmodule.solver.kernel.SolverKernelResult;
import test.demo.apsmodule.solver.kernel.SolverKernelStatus;

import java.util.List;
import java.util.Objects;

@Component
public class UnifiedPatternSolver {

    private static final Logger log = LoggerFactory.getLogger(UnifiedPatternSolver.class);

    private final SolverKernel newSolverKernel;
    private final CuttingSolverAlgorithm fixedWidthSolver;
    private final CuttingSolverAlgorithm flexibleWidthSolver;

    @Autowired
    public UnifiedPatternSolver(
            @Qualifier("newSolverKernel") SolverKernel newSolverKernel,
            List<CuttingSolverAlgorithm> solvers) {
        this(
                newSolverKernel,
                findRequiredSolver(solvers, PatternSolver.class),
                findRequiredSolver(solvers, PatternSolverFour.class));
    }

    UnifiedPatternSolver(SolverKernel newSolverKernel,
            CuttingSolverAlgorithm fixedWidthSolver,
            CuttingSolverAlgorithm flexibleWidthSolver) {
        this.newSolverKernel = Objects.requireNonNull(newSolverKernel, "newSolverKernel");
        this.fixedWidthSolver = Objects.requireNonNull(fixedWidthSolver, "fixedWidthSolver");
        this.flexibleWidthSolver = Objects.requireNonNull(flexibleWidthSolver, "flexibleWidthSolver");
    }

    public List<CuttingInstruction> solve(List<SolverOrderItem> items,
            SolverConfig config,
            List<ProductionOrder> originalOrderItems) {

        log.info("========== Unified solver ==========");
        log.info("Mode: {}", config.isFixedMode() ? "fixed" : "variable");
        log.info("Input items: {}", items.size());

        List<CuttingInstruction> instructions;
        if (config.isUseNewSolver()) {
            SolverKernelRequest request = SolverKernelRequest.from(
                    items, config, SolverRuntimeProperties.captureContext());
            if (!newSolverKernel.supports(request.settings())) {
                throw new IllegalStateException(
                        "Solver kernel does not support request: " + newSolverKernel.id());
            }
            log.info("Selected solver kernel: {}", newSolverKernel.id());
            SolverKernelResult result = newSolverKernel.solve(request);
            if (result.status() != SolverKernelStatus.SUCCEEDED
                    && result.status() != SolverKernelStatus.FEASIBLE
                    && result.status() != SolverKernelStatus.OPTIMAL) {
                throw new IllegalStateException(
                        "Solver kernel failed: " + result.kernelId() + " / " + result.status());
            }
            instructions = result.instructions();
        } else {
            CuttingSolverAlgorithm selectedSolver = selectLegacySolver(config);
            log.info("Selected legacy solver: {}", selectedSolver.getClass().getSimpleName());
            instructions = selectedSolver.solve(items, config);
        }
        log.info("Solve done, generated {} instructions", instructions.size());

        int filledCount = 0;
        int totalAssignments = 0;
        for (CuttingInstruction instruction : instructions) {
            String groupKey = instruction.getGroupKey();
            for (StationAssignment assignment : instruction.getStationAssignments()) {
                totalAssignments++;
                if (assignment.getOrderItem() == null && assignment.getMessageText() != null) {
                    boolean found = false;
                    for (ProductionOrder orderItem : originalOrderItems) {
                        if (orderItem.getMessageText() != null
                                && orderItem.getMessageText().equals(assignment.getMessageText())
                                && orderItem.getWidth() == assignment.getWidth()) {
                            if (groupKey == null || groupKey.equals(orderItem.getGroupKey())) {
                                assignment.setOrderItem(orderItem);
                                found = true;
                                filledCount++;
                                break;
                            }
                        }
                    }

                    if (!found) {
                        for (ProductionOrder orderItem : originalOrderItems) {
                            if (orderItem.getMessageText() != null
                                    && orderItem.getMessageText().equals(assignment.getMessageText())) {
                                assignment.setOrderItem(orderItem);
                                found = true;
                                filledCount++;
                                log.info(
                                        "Fallback order-item match for messageText={}, assignmentWidth={}, orderItemWidth={}",
                                        assignment.getMessageText(),
                                        assignment.getWidth(),
                                        orderItem.getWidth());
                                break;
                            }
                        }
                    }

                    if (!found) {
                        log.error(
                                "OrderItem match not found: messageText={}, width={}, groupKey={}",
                                assignment.getMessageText(),
                                assignment.getWidth(),
                                groupKey);
                    }
                } else if (assignment.getOrderItem() != null) {
                    filledCount++;
                }
            }
        }

        log.info("StationAssignment fill completed: {}/{}", filledCount, totalAssignments);
        return instructions;
    }

    private CuttingSolverAlgorithm selectLegacySolver(SolverConfig config) {
        if (config.isFixedMode()) {
            return fixedWidthSolver;
        }
        return flexibleWidthSolver;
    }

    private static <T extends CuttingSolverAlgorithm> T findRequiredSolver(
            List<CuttingSolverAlgorithm> solvers, Class<T> solverType) {
        return solvers.stream()
                .filter(solverType::isInstance)
                .map(solverType::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Required solver bean not found: " + solverType.getSimpleName()));
    }
}
