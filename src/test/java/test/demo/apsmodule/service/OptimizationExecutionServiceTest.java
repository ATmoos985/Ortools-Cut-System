package test.demo.apsmodule.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import test.demo.apsmodule.generator.NewSolver.CuttingSolver;
import test.demo.apsmodule.generator.NewSolver.output.LocalNeighborhoodSequenceOptimizer;
import test.demo.rest.dto.OptimizationRequest;

import java.util.List;

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
}
