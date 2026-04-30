package test.demo.apsmodule.generator.NewSolver.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatternCandidateTest {

    @Test
    void constructorCanonicalizesWidthOrder() {
        Map<Integer, Integer> unsortedPattern = new LinkedHashMap<>();
        unsortedPattern.put(2000, 1);
        unsortedPattern.put(1000, 2);
        unsortedPattern.put(1280, 1);

        PatternCandidate candidate = new PatternCandidate(unsortedPattern, 4400);

        assertEquals(List.of(1000, 1280, 2000), List.copyOf(candidate.getPattern().keySet()));
        assertEquals("4400|1000x2,1280x1,2000x1", candidate.signature());
    }
}
