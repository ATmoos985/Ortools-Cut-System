package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.output.InstructionConverter;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Djx188ManualBaselineTest {

    @Test
    void manualPatternsAreACompleteZeroOverBaseline() throws Exception {
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        List<Djx188ManualBaselineFixture.ManualPattern> patterns =
                Djx188ManualBaselineFixture.loadManualPatterns();
        Map<Integer, Integer> production = new LinkedHashMap<>();
        int cars = 0;
        int waste = 0;
        int oddGroups = 0;
        int oneCarGroups = 0;

        for (Djx188ManualBaselineFixture.ManualPattern pattern : patterns) {
            PatternCandidate candidate = pattern.candidate();
            assertEquals(pattern.expectedPatternWidth(), candidate.getPatternWidth(), pattern.id());
            cars += pattern.usage();
            waste += (4600 - candidate.getPatternWidth()) * pattern.usage();
            oddGroups += pattern.usage() % 2;
            oneCarGroups += pattern.usage() == 1 ? 1 : 0;
            candidate.getPattern().forEach((width, count) ->
                    production.merge(width, count * pattern.usage(), Integer::sum));
        }

        assertEquals(53, items.size());
        assertEquals(26, patterns.size());
        assertEquals(169, cars);
        assertEquals(36_870, waste);
        assertEquals(1, oddGroups);
        assertEquals(0, oneCarGroups);
        assertEquals(Djx188ManualBaselineFixture.demands(items), production);
    }

    @Test
    void currentAssignmentPipelineCanReachTheManualLevel() throws Exception {
        Loader.loadNativeLibraries();
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        Map<PatternCandidate, Integer> solution = Djx188ManualBaselineFixture.solution(
                Djx188ManualBaselineFixture.loadManualPatterns());

        InstructionConverter.ConversionResult result = SolverRuntimeProperties.withOverrides(
                Map.ofEntries(
                        Map.entry("cutting.quality", "true"),
                        Map.entry("cutting.lns.enabled", "true"),
                        Map.entry("cutting.lns.enrichPatterns", "false"),
                        Map.entry("cutting.phase2.enabled", "false"),
                        Map.entry("cutting.spr.enabled", "false")),
                () -> new InstructionConverter(Djx188ManualBaselineFixture.parameters())
                        .convertWithoutSetPartition(solution, "djx188", items, demands));

        List<CuttingInstruction> instructions = result.instructions();
        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);
        assertFalse(instructions.isEmpty());
        assertEquals(169, instructions.stream().mapToInt(CuttingInstruction::getUsageCount).sum());
        assertTrue(stats.groups() <= 26, stats::toString);
        assertTrue(stats.oddCarGroups() <= 1, stats::toString);
        assertEquals(0, stats.oneCarGroups(), stats::toString);
    }
}
