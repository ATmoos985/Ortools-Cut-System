package test.demo.apsmodule.generator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternSolverFourTest {

    @Test
    void patternCandidateCostWithTotalWidthPrefersWiderPatterns() {
        int totalWidth = 3580;
        PatternSolverFour.PatternCandidate narrower = new PatternSolverFour.PatternCandidate(Map.of(3300, 1), 3300);
        PatternSolverFour.PatternCandidate wider = new PatternSolverFour.PatternCandidate(Map.of(3380, 1), 3380);

        assertTrue(narrower.getCost(totalWidth) > wider.getCost(totalWidth));
    }
}
