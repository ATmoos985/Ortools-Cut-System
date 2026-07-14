package test.demo.apsmodule.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import test.demo.apsmodule.generator.NewSolver.CuttingSolver;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.output.LocalNeighborhoodSequenceOptimizer;
import test.demo.apsmodule.generator.NewSolver.output.SetPartitionRefiner;
import test.demo.rest.dto.OptimizationRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptimizationExecutionServiceTest {

    @Test
    void appliesFastProfileOnlyDuringOptimization() {
        String previous = System.getProperty("cutting.lns.enabled");
        String previousQuality = System.getProperty("cutting.quality");
        System.clearProperty("cutting.lns.enabled");
        System.clearProperty("cutting.quality");
        try {
            CuttingOptimizationService optimizationService = mock(CuttingOptimizationService.class);
            SolverConfigFactory solverConfigFactory = mock(SolverConfigFactory.class);
            OptimizationExecutionService executionService =
                    new OptimizationExecutionService(optimizationService, solverConfigFactory);

            OptimizationRequest request = new OptimizationRequest();
            request.setUseNewSolver(true);
            request.setSolverProfile(OptimizationRequest.SolverProfile.FAST);
            request.setLnsEnabled(false);
            request.setOrderItems(List.of());
            SolverConfig config = new SolverConfig();
            CuttingOptimizationResult expected = new CuttingOptimizationResult();

            assertFalse(request.isLnsEnabled());
            assertFalse(request.isLnsEnrichPatterns());
            assertFalse(request.isQualityMode());

            when(solverConfigFactory.fromOptimizationRequest(request)).thenReturn(config);
            when(optimizationService.optimizeUnified(anyList(), same(config))).thenAnswer(invocation -> {
                assertTrue(LocalNeighborhoodSequenceOptimizer.isEnabled());
                assertFalse(CuttingSolver.qualityMode());
                assertTrue(SolverRuntimeProperties.getBoolean("cutting.demandPeak.enabled", false));
                assertTrue(SolverRuntimeProperties.getBoolean(
                        "cutting.demandPeak.smallPolish.enabled", false));
                assertFalse(SolverRuntimeProperties.getBoolean("cutting.spr.enabled", true));
                assertFalse(SolverRuntimeProperties.getBoolean("cutting.lns.enrichPatterns", true));
                return expected;
            });

            OptimizationExecutionService.ExecutionResult actual = executionService.execute(request);

            assertSame(expected, actual.result());
            assertFalse(LocalNeighborhoodSequenceOptimizer.isEnabled());
            assertFalse(CuttingSolver.qualityMode());
        } finally {
            if (previous == null) {
                System.clearProperty("cutting.lns.enabled");
            } else {
                System.setProperty("cutting.lns.enabled", previous);
            }
            if (previousQuality == null) {
                System.clearProperty("cutting.quality");
            } else {
                System.setProperty("cutting.quality", previousQuality);
            }
        }
    }

    @Test
    void legacyNewSolverRequestKeepsPublishedQualityProfile() {
        String previousQuality = System.getProperty("cutting.quality");
        System.clearProperty("cutting.quality");
        try {
            CuttingOptimizationService optimizationService = mock(CuttingOptimizationService.class);
            SolverConfigFactory solverConfigFactory = mock(SolverConfigFactory.class);
            OptimizationExecutionService executionService =
                    new OptimizationExecutionService(optimizationService, solverConfigFactory);

            OptimizationRequest request = new OptimizationRequest();
            request.setUseNewSolver(true);
            request.setOrderItems(List.of());
            SolverConfig config = new SolverConfig();
            CuttingOptimizationResult expected = new CuttingOptimizationResult();

            when(solverConfigFactory.fromOptimizationRequest(request)).thenReturn(config);
            when(optimizationService.optimizeUnified(anyList(), same(config))).thenAnswer(invocation -> {
                assertTrue(CuttingSolver.qualityMode());
                assertTrue(SolverRuntimeProperties.getBoolean("cutting.spr.enabled", false));
                assertFalse(SolverRuntimeProperties.getBoolean("cutting.demandPeak.enabled", true));
                return expected;
            });

            OptimizationExecutionService.ExecutionResult actual = executionService.execute(request);

            assertSame(expected, actual.result());
            assertFalse(CuttingSolver.qualityMode());
        } finally {
            if (previousQuality == null) {
                System.clearProperty("cutting.quality");
            } else {
                System.setProperty("cutting.quality", previousQuality);
            }
        }
    }

    @Test
    void appliesQualityModeToWholeNewSolverOnlyDuringOptimization() {
        String previousQuality = System.getProperty("cutting.quality");
        String previousLns = System.getProperty("cutting.lns.enabled");
        System.clearProperty("cutting.quality");
        System.clearProperty("cutting.lns.enabled");
        try {
            CuttingOptimizationService optimizationService = mock(CuttingOptimizationService.class);
            SolverConfigFactory solverConfigFactory = mock(SolverConfigFactory.class);
            OptimizationExecutionService executionService =
                    new OptimizationExecutionService(optimizationService, solverConfigFactory);

            OptimizationRequest request = new OptimizationRequest();
            request.setUseNewSolver(true);
            request.setSolverProfile(OptimizationRequest.SolverProfile.QUALITY);
            request.setOrderItems(List.of());
            SolverConfig config = new SolverConfig();
            CuttingOptimizationResult expected = new CuttingOptimizationResult();

            when(solverConfigFactory.fromOptimizationRequest(request)).thenReturn(config);
            when(optimizationService.optimizeUnified(anyList(), same(config))).thenAnswer(invocation -> {
                assertTrue(LocalNeighborhoodSequenceOptimizer.isEnabled());
                assertTrue(CuttingSolver.qualityMode());
                assertFalse(SolverRuntimeProperties.getBoolean("cutting.demandPeak.enabled", true));
                assertTrue(SolverRuntimeProperties.getBoolean("cutting.spr.enabled", false));
                assertTrue(SolverRuntimeProperties.getBoolean(
                        "cutting.spr.reverseTiePass", false));
                assertEquals(1, SolverRuntimeProperties.getInt(
                        "cutting.spr.reverseTieMaxIterations", -1));
                assertFalse(SolverRuntimeProperties.getBoolean(
                        "cutting.spr.portfolioPass", true));
                return expected;
            });

            OptimizationExecutionService.ExecutionResult actual = executionService.execute(request);

            assertSame(expected, actual.result());
            assertFalse(LocalNeighborhoodSequenceOptimizer.isEnabled());
            assertFalse(CuttingSolver.qualityMode());
        } finally {
            if (previousQuality == null) {
                System.clearProperty("cutting.quality");
            } else {
                System.setProperty("cutting.quality", previousQuality);
            }
            if (previousLns == null) {
                System.clearProperty("cutting.lns.enabled");
            } else {
                System.setProperty("cutting.lns.enabled", previousLns);
            }
        }
    }

    @Test
    void setPartitionRefinerUsesTighterDefaultSkipGapAndAllowsOverride() {
        String previous = System.getProperty("cutting.spr.skipGapThreshold");
        try {
            System.clearProperty("cutting.spr.skipGapThreshold");
            org.junit.jupiter.api.Assertions.assertEquals(8, SetPartitionRefiner.skipGapThreshold());

            System.setProperty("cutting.spr.skipGapThreshold", "15");
            org.junit.jupiter.api.Assertions.assertEquals(15, SetPartitionRefiner.skipGapThreshold());
        } finally {
            if (previous == null) {
                System.clearProperty("cutting.spr.skipGapThreshold");
            } else {
                System.setProperty("cutting.spr.skipGapThreshold", previous);
            }
        }
    }
}
