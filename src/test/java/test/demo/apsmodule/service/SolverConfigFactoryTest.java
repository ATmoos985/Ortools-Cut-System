package test.demo.apsmodule.service;

import org.junit.jupiter.api.Test;
import test.demo.rest.dto.OptimizationRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolverConfigFactoryTest {

    private final SolverConfigFactory factory = new SolverConfigFactory();

    @Test
    void buildsFixedModeConfigWithDefaults() {
        OptimizationRequest request = new OptimizationRequest();
        request.setFlexibleWidth(false);
        request.setFixedWidth(0);
        request.setTotalOverCap(0);
        request.setMaxIterations(0);
        request.setTimeoutMs(0);

        SolverConfig config = factory.fromOptimizationRequest(request);

        assertEquals("fixed", config.getMode());
        assertFalse(config.isFlexibleWidth());
        assertEquals(4600, config.getMinWidth());
        assertEquals(4600, config.getMaxWidth());
        assertEquals(4600, config.getTotalWidth());
        assertEquals(0, config.getStepSize());
        assertEquals(0, config.getTotalOverCap());
        assertEquals(300, config.getMaxIterations());
        assertEquals(120000, config.getTimeoutMs());
    }

    @Test
    void explicitSolverProfileSelectsNewSolverWithoutLegacyFlag() {
        OptimizationRequest request = new OptimizationRequest();
        request.setSolverProfile(OptimizationRequest.SolverProfile.FAST);

        SolverConfig config = factory.fromOptimizationRequest(request);

        assertTrue(config.isUseNewSolver());
        assertEquals(30, config.getTotalOverCap());
    }

    @Test
    void buildsVariableModeConfigWithExplicitValues() {
        OptimizationRequest request = new OptimizationRequest();
        request.setFlexibleWidth(true);
        request.setMinWidth(4300);
        request.setMaxWidth(4500);
        request.setStepSize(20);
        request.setTotalWidth(4550);
        request.setTotalOverCap(18);
        request.setMaxIterations(500);
        request.setTimeoutMs(90000);
        request.setUseNewSolver(true);
        request.setNewSolverTopK(5);
        request.setNewSolverMaxPatterns(900);
        request.setNewSolverMaxDistinctWidths(6);
        request.setNewSolverStage4TimeLimit(45000);
        request.setNewSolverSeqGroupAlpha(2.5);
        request.setNewSolverSeqGroupBeta(0.7);
        request.setNewSolverUseOptimizedAssignment(false);
        request.setNewSolverUnderPenalty(12345);

        SolverConfig config = factory.fromOptimizationRequest(request);

        assertEquals("variable", config.getMode());
        assertTrue(config.isFlexibleWidth());
        assertEquals(4300, config.getMinWidth());
        assertEquals(4500, config.getMaxWidth());
        assertEquals(20, config.getStepSize());
        assertEquals(4550, config.getTotalWidth());
        assertEquals(18, config.getTotalOverCap());
        assertEquals(500, config.getMaxIterations());
        assertEquals(90000, config.getTimeoutMs());
        assertTrue(config.isUseNewSolver());
        assertEquals(5, config.getNewSolverTopK());
        assertEquals(900, config.getNewSolverMaxPatterns());
        assertEquals(6, config.getNewSolverMaxDistinctWidths());
        assertEquals(45000, config.getNewSolverStage4TimeLimit());
        assertEquals(2.5, config.getNewSolverSeqGroupAlpha());
        assertEquals(0.7, config.getNewSolverSeqGroupBeta());
        assertFalse(config.isNewSolverUseOptimizedAssignment());
        assertEquals(12345, config.getNewSolverUnderPenalty());
    }
}
