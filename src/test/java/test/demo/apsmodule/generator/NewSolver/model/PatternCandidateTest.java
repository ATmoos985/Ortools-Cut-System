package test.demo.apsmodule.generator.NewSolver.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternCandidateTest {

    @Test
    void costWithTotalWidthPrefersWiderPatternsEvenWhenInternalWasteIsZero() {
        int totalWidth = 3580;
        PatternCandidate narrower = new PatternCandidate(Map.of(3300, 1), 3300);
        PatternCandidate wider = new PatternCandidate(Map.of(3380, 1), 3380);

        assertTrue(narrower.getCost(totalWidth) > wider.getCost(totalWidth));
    }
}
