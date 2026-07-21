package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionNeutralMoveSearchTest {

    @Test
    void platformIdentityRequiresImprovementInfeasibleAndCurrentFeasible() {
        assertEquals(
                ProductionNeutralMoveSearch.Classification.PLATFORM,
                classify(
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE,
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE));
        assertEquals(
                ProductionNeutralMoveSearch.Classification.WORSE,
                classify(
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE,
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE));
        assertEquals(
                ProductionNeutralMoveSearch.Classification.UNKNOWN,
                classify(
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.UNKNOWN,
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE));
        assertEquals(
                ProductionNeutralMoveSearch.Classification.UNKNOWN,
                classify(
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE,
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.UNKNOWN));
        assertEquals(
                ProductionNeutralMoveSearch.Classification
                        .IMPROVEMENT_PENDING_CONFIRMATION,
                classify(
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE,
                        OrderCompatibilityKernelAnalyzer.ThresholdStatus.UNKNOWN));
    }

    private static ProductionNeutralMoveSearch.Classification classify(
            OrderCompatibilityKernelAnalyzer.ThresholdStatus improvement,
            OrderCompatibilityKernelAnalyzer.ThresholdStatus current) {
        return ProductionNeutralMoveSearch.classifyPlatformIdentity(
                improvement, current);
    }
}
