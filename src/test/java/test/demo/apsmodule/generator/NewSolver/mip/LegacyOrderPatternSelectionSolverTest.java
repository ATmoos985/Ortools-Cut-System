package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyOrderPatternSelectionSolverTest {

    @Test
    void qualityModeUsesDeterministicStage4NodeLimitByDefault() {
        String previousQuality = System.getProperty("cutting.quality");
        String previousNodes = System.getProperty("cutting.aLayerStage4NodeLimit");
        try {
            System.clearProperty("cutting.aLayerStage4NodeLimit");
            System.setProperty("cutting.quality", "false");
            assertEquals(0L, LegacyOrderPatternSelectionSolver.stage4NodeLimit());

            System.setProperty("cutting.quality", "true");
            assertEquals(10L, LegacyOrderPatternSelectionSolver.stage4NodeLimit());
            assertEquals(3000L, LegacyOrderPatternSelectionSolver.effectiveStage4NodeLimit(0.0, 0.0));
            assertEquals(10L, LegacyOrderPatternSelectionSolver.effectiveStage4NodeLimit(0.1, 0.0));
        } finally {
            restore("cutting.quality", previousQuality);
            restore("cutting.aLayerStage4NodeLimit", previousNodes);
        }
    }

    @Test
    void stage4NodeLimitIsRequestScopedAndWallClockBecomesSafetyCap() {
        String previousNodes = System.getProperty("cutting.aLayerStage4NodeLimit");
        String previousSafety = System.getProperty("cutting.aLayerStage4SafetyTimeLimitMs");
        try {
            System.setProperty("cutting.aLayerStage4NodeLimit", "17");
            System.setProperty("cutting.aLayerStage4SafetyTimeLimitMs", "90000");

            assertEquals(17L, LegacyOrderPatternSelectionSolver.stage4NodeLimit());
            assertEquals(90_000L, LegacyOrderPatternSelectionSolver.stage4WallLimitMs(120_000L));
        } finally {
            restore("cutting.aLayerStage4NodeLimit", previousNodes);
            restore("cutting.aLayerStage4SafetyTimeLimitMs", previousSafety);
        }
    }

    @Test
    void primaryStage4NodeLimitHasAnIndependentExperimentSwitch() {
        String previousPrimaryNodes = System.getProperty("cutting.aLayerPrimaryStage4NodeLimit");
        String previousQuality = System.getProperty("cutting.quality");
        try {
            System.setProperty("cutting.quality", "true");
            System.setProperty("cutting.aLayerPrimaryStage4NodeLimit", "3000");

            assertEquals(3000L,
                    LegacyOrderPatternSelectionSolver.effectiveStage4NodeLimit(0.0, 0.0));
            assertEquals(10L,
                    LegacyOrderPatternSelectionSolver.effectiveStage4NodeLimit(0.1, 0.0));
        } finally {
            restore("cutting.quality", previousQuality);
            restore("cutting.aLayerPrimaryStage4NodeLimit", previousPrimaryNodes);
        }
    }

    private void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
