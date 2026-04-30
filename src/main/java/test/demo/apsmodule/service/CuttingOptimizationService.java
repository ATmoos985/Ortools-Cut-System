package test.demo.apsmodule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import test.demo.apsmodule.solver.UnifiedPatternSolver;

import java.util.ArrayList;
import java.util.List;

@Service
public class CuttingOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(CuttingOptimizationService.class);

    private final OrderNormalizer normalizer;
    private final UnifiedPatternSolver unifiedSolver;

    @Autowired
    public CuttingOptimizationService(OrderNormalizer normalizer, UnifiedPatternSolver unifiedSolver) {
        this.normalizer = normalizer;
        this.unifiedSolver = unifiedSolver;
    }

    public CuttingOptimizationResult optimizeUnified(List<ProductionOrder> orderItems,
            SolverConfig config) {
        log.info("========== CuttingOptimizationService unified optimize ==========");
        log.info("Order items: {}", orderItems.size());
        log.info("Mode: {}", config.isFixedMode() ? "fixed" : "variable");
        long startTime = System.currentTimeMillis();

        List<SolverOrderItem> solverItems = normalizer.normalize(orderItems);
        List<CuttingInstruction> instructions = unifiedSolver.solve(solverItems, config, orderItems);

        CuttingOptimizationResult result = new CuttingOptimizationResult();
        result.setCuttingInstructions(convertToLegacyInstructions(instructions));
        result.setTotalWidth(config.getTotalWidth());

        CuttingStatistics.Summary summary = CuttingStatistics.summarizeInstructions(instructions, config.getTotalWidth());
        result.setTotalRollsUsed(summary.totalRollsUsed());
        result.setTotalWaste(summary.totalWaste());
        result.setUtilizationRate(summary.utilizationRate());
        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);

        log.info("========== optimize done ==========");
        log.info("Elapsed: {}ms", result.getExecutionTimeMs());
        log.info("Total rolls: {}", result.getTotalRollsUsed());
        log.info("Utilization: {}%", String.format("%.2f", result.getUtilizationRate()));

        return result;
    }

    public CuttingOptimizationResult optimizeSimple(List<ProductionOrder> orderItems,
            SolverConfig config) {
        return optimizeUnified(orderItems, config);
    }

    private List<CuttingOptimizationResult.CuttingInstruction> convertToLegacyInstructions(
            List<CuttingInstruction> newInstructions) {
        List<CuttingOptimizationResult.CuttingInstruction> legacyInstructions = new ArrayList<>();

        int rollNumber = 1;
        for (CuttingInstruction newInst : newInstructions) {
            CuttingOptimizationResult.CuttingInstruction legacyInst = new CuttingOptimizationResult.CuttingInstruction();

            legacyInst.setRollNumber(rollNumber++);
            legacyInst.setSubRolls(newInst.getSubRolls());
            legacyInst.setUsageCount(newInst.getUsageCount());
            legacyInst.setGroupKey(newInst.getGroupKey());
            legacyInst.setRollWidth(newInst.getRollWidth());
            legacyInst.setLength(newInst.getLength());
            legacyInst.setSurfaceTreatment(newInst.getSurfaceTreatment());
            legacyInst.setThickness(newInst.getThickness());

            int patternWidth = CuttingStatistics.calculatePatternWidth(newInst.getSubRolls());
            legacyInst.setTotalWidth(patternWidth);
            legacyInst.setWaste(newInst.getWaste());

            List<CuttingOptimizationResult.StationAssignment> legacyAssignments = new ArrayList<>();
            if (newInst.getStationAssignments() != null) {
                int stationIndex = 0;
                for (StationAssignment newAssign : newInst.getStationAssignments()) {
                    CuttingOptimizationResult.StationAssignment legacyAssign =
                            new CuttingOptimizationResult.StationAssignment(
                                    stationIndex++,
                                    newAssign.getWidth(),
                                    newAssign.getOrderItem());
                    legacyAssignments.add(legacyAssign);
                }
            }
            legacyInst.setStationAssignments(legacyAssignments);

            legacyInstructions.add(legacyInst);
        }

        return legacyInstructions;
    }
}
