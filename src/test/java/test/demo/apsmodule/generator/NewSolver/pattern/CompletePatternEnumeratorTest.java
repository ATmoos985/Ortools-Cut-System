package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletePatternEnumeratorTest {

    @Test
    void enumeratesTheCompleteDjx188FeasiblePatternSpace() throws Exception {
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        List<PatternCandidate> patterns = new CompletePatternEnumerator(
                Djx188ManualBaselineFixture.parameters(), 5).generate(
                        Djx188ManualBaselineFixture.demands(items));
        List<Map<Integer, Integer>> shapes = patterns.stream()
                .map(PatternCandidate::getPattern)
                .toList();

        assertEquals(7_717, patterns.size());
        for (Djx188ManualBaselineFixture.ManualPattern manual :
                Djx188ManualBaselineFixture.loadManualPatterns()) {
            assertTrue(shapes.contains(manual.candidate().getPattern()), manual.id());
        }
        for (PatternCandidate pattern : patterns) {
            int stations = pattern.getPattern().values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(pattern.getPatternWidth() >= 4300, pattern::toString);
            assertTrue(pattern.getPatternWidth() <= 4400, pattern::toString);
            assertTrue(pattern.getWidthCount() <= 5, pattern::toString);
            assertTrue(stations <= 6, pattern::toString);
        }
    }
}
