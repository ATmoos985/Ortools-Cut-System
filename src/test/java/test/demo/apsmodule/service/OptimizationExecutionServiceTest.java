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
    void defaultsEnableLnsWithoutEnrichedPatternsOnlyDuringOptimization() {
        String previous = System.getProperty("cutting.lns.enabled");
        System.clearProperty("cutting.lns.enabled");
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

            assertTrue(request.isLnsEnabled());
            assertFalse(request.isLnsEnrichPatterns());

            when(solverConfigFactory.fromOptimizationRequest(request)).thenReturn(config);
            when(optimizationService.optimizeUnified(anyList(), same(config))).thenAnswer(invocation -> {
                assertTrue(LocalNeighborhoodSequenceOptimizer.isEnabled());
                return expected;
            });

            OptimizationExecutionService.ExecutionResult actual = executionService.execute(request);

            assertSame(expected, actual.result());
            assertFalse(LocalNeighborhoodSequenceOptimizer.isEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty("cutting.lns.enabled");
            } else {
                System.setProperty("cutting.lns.enabled", previous);
            }
        }
    }

    @Test
    void appliesLnsRequestOverrideOnlyDuringOptimization() {
        String previous = System.getProperty("cutting.lns.enabled");
        System.clearProperty("cutting.lns.enabled");
        try {
            CuttingOptimizationService optimizationService = mock(CuttingOptimizationService.class);
            SolverConfigFactory solverConfigFactory = mock(SolverConfigFactory.class);
            OptimizationExecutionService executionService =
                    new OptimizationExecutionService(optimizationService, solverConfigFactory);

            OptimizationRequest request = new OptimizationRequest();
            request.setUseNewSolver(true);
            request.setLnsEnabled(true);
            request.setLnsEnrichPatterns(true);
            request.setOrderItems(List.of());
            SolverConfig config = new SolverConfig();
            CuttingOptimizationResult expected = new CuttingOptimizationResult();

            when(solverConfigFactory.fromOptimizationRequest(request)).thenReturn(config);
            when(optimizationService.optimizeUnified(anyList(), same(config))).thenAnswer(invocation -> {
                assertTrue(LocalNeighborhoodSequenceOptimizer.isEnabled());
                return expected;
            });

            OptimizationExecutionService.ExecutionResult actual = executionService.execute(request);

            assertSame(expected, actual.result());
            assertFalse(LocalNeighborhoodSequenceOptimizer.isEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty("cutting.lns.enabled");
            } else {
                System.setProperty("cutting.lns.enabled", previous);
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
            request.setQualityMode(true);
            request.setOrderItems(List.of());
            SolverConfig config = new SolverConfig();
            CuttingOptimizationResult expected = new CuttingOptimizationResult();

            when(solverConfigFactory.fromOptimizationRequest(request)).thenReturn(config);
            when(optimizationService.optimizeUnified(anyList(), same(config))).thenAnswer(invocation -> {
                assertTrue(LocalNeighborhoodSequenceOptimizer.isEnabled());
                assertTrue(CuttingSolver.qualityMode());
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
