package test.demo.apsmodule.generator.NewSolver.output;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.mip.AssignmentMIPSolver;
import test.demo.apsmodule.generator.NewSolver.mip.Phase2SequenceGroupSolver;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionConverterTest {

    @Test
    void convertChoosesCandidateWithFewestRealSequenceGroups() {
        SolverParameters params = testParams();
        PatternCandidate pattern = new PatternCandidate(linkedPattern(1000, 1, 1200, 1), 2200);
        Map<PatternCandidate, Integer> solution = Map.of(pattern, 3);
        List<SolverOrderItem> items = orderItems();

        InstructionConverter converter = new TestInstructionConverter(params, pattern, null, false, false);
        List<CuttingInstruction> result = converter.convert(solution, "group-a", items, Map.of(1000, 3, 1200, 3));

        assertEquals(2, SequenceGroupPostProcessor.countTotalGroups(result));
        assertEquals(List.of("A", "A", "B"), widthMessages(result.get(0), 1000));
    }

    @Test
    void convertRejectsPostProcessedCandidateWhenDemandCountsChange() {
        SolverParameters params = testParams();
        PatternCandidate pattern = new PatternCandidate(linkedPattern(1000, 1, 1200, 1), 2200);
        Map<PatternCandidate, Integer> solution = Map.of(pattern, 3);
        List<SolverOrderItem> items = orderItems();

        InstructionConverter converter = new TestInstructionConverter(params, pattern, null, true, true);
        List<CuttingInstruction> result = converter.convert(solution, "group-a", items, Map.of(1000, 3, 1200, 3));

        assertEquals(2, SequenceGroupPostProcessor.countTotalGroups(result));
        assertEquals(List.of("A", "A", "B"), widthMessages(result.get(0), 1000));
    }

    @Test
    void convertRepacksInstructionIntoFewerSafeBlocks() {
        SolverParameters params = testParams();
        PatternCandidate pattern = new PatternCandidate(linkedPattern(1000, 1, 1200, 1), 2200);
        Map<PatternCandidate, Integer> solution = Map.of(pattern, 4);
        List<SolverOrderItem> items = List.of(
                item(1000, "A", 2),
                item(1000, "B", 2),
                item(1200, "X", 2),
                item(1200, "Y", 2));

        Map<Integer, String> configAx = new LinkedHashMap<>();
        configAx.put(1000, "A");
        configAx.put(1200, "X");

        Map<Integer, String> configAy = new LinkedHashMap<>();
        configAy.put(1000, "A");
        configAy.put(1200, "Y");

        Map<Integer, String> configBx = new LinkedHashMap<>();
        configBx.put(1000, "B");
        configBx.put(1200, "X");

        Map<Integer, String> configBy = new LinkedHashMap<>();
        configBy.put(1000, "B");
        configBy.put(1200, "Y");

        InstructionConverter converter = new TestInstructionConverter(
                params,
                pattern,
                List.of(
                        new AssignmentMIPSolver.AssignmentBlock(configAx, 1),
                        new AssignmentMIPSolver.AssignmentBlock(configAy, 1),
                        new AssignmentMIPSolver.AssignmentBlock(configBx, 1),
                        new AssignmentMIPSolver.AssignmentBlock(configBy, 1)),
                false,
                false);
        List<CuttingInstruction> result = converter.convert(solution, "group-a", items, Map.of(1000, 4, 1200, 4));

        assertEquals(2, SequenceGroupPostProcessor.countTotalGroups(result));
        assertEquals(Map.of("A", 2L, "B", 2L), messageCounts(result.get(0), 1000));
        assertEquals(Map.of("X", 2L, "Y", 2L), messageCounts(result.get(0), 1200));
    }

    @Test
    void convertCollectsHintRollsForFragmentedInstruction() {
        SolverParameters params = testParams();
        PatternCandidate pattern = new PatternCandidate(linkedPattern(1000, 1, 1200, 1), 2200);
        Map<PatternCandidate, Integer> solution = Map.of(pattern, 8);
        List<SolverOrderItem> items = List.of(
                item(1000, "A", 4),
                item(1000, "B", 4),
                item(1200, "X", 4),
                item(1200, "Y", 4));

        Map<Integer, String> configAx = new LinkedHashMap<>();
        configAx.put(1000, "A");
        configAx.put(1200, "X");

        Map<Integer, String> configAy = new LinkedHashMap<>();
        configAy.put(1000, "A");
        configAy.put(1200, "Y");

        Map<Integer, String> configBx = new LinkedHashMap<>();
        configBx.put(1000, "B");
        configBx.put(1200, "X");

        Map<Integer, String> configBy = new LinkedHashMap<>();
        configBy.put(1000, "B");
        configBy.put(1200, "Y");

        List<List<StationAssignment>> hintRolls = List.of(
                List.of(new StationAssignment(1000, "A"), new StationAssignment(1200, "X")),
                List.of(new StationAssignment(1000, "A"), new StationAssignment(1200, "X")),
                List.of(new StationAssignment(1000, "A"), new StationAssignment(1200, "X")),
                List.of(new StationAssignment(1000, "A"), new StationAssignment(1200, "X")),
                List.of(new StationAssignment(1000, "B"), new StationAssignment(1200, "Y")),
                List.of(new StationAssignment(1000, "B"), new StationAssignment(1200, "Y")),
                List.of(new StationAssignment(1000, "B"), new StationAssignment(1200, "Y")),
                List.of(new StationAssignment(1000, "B"), new StationAssignment(1200, "Y")));

        TestInstructionConverter converter = new TestInstructionConverter(
                params,
                pattern,
                List.of(
                        new AssignmentMIPSolver.AssignmentBlock(configAx, 2),
                        new AssignmentMIPSolver.AssignmentBlock(configAy, 2),
                        new AssignmentMIPSolver.AssignmentBlock(configBx, 2),
                        new AssignmentMIPSolver.AssignmentBlock(configBy, 2)),
                false,
                false,
                hintRolls);
        List<CuttingInstruction> result = converter.convert(solution, "group-a", items, Map.of(1000, 8, 1200, 8));

        assertEquals(true, converter.hintCollected);
        assertEquals(2, SequenceGroupPostProcessor.countTotalGroups(result));
        assertEquals(Map.of("A", 4L, "B", 4L), messageCounts(result.get(0), 1000));
        assertEquals(Map.of("X", 4L, "Y", 4L), messageCounts(result.get(0), 1200));
    }

    @Test
    @SuppressWarnings("unchecked")
    void optimizeInstructionFamiliesSafelyRepacksAcrossInstructions() throws Exception {
        SolverParameters params = testParams();
        InstructionConverter converter = new InstructionConverter(params);

        CuttingInstruction left = instruction(
                2200,
                linkedPattern(1000, 1, 1200, 1),
                List.of(
                        new StationAssignment(1000, "A"),
                        new StationAssignment(1200, "X"),
                        new StationAssignment(1000, "A"),
                        new StationAssignment(1200, "Y")));

        CuttingInstruction right = instruction(
                2200,
                linkedPattern(1000, 1, 1200, 1),
                List.of(
                        new StationAssignment(1000, "B"),
                        new StationAssignment(1200, "X"),
                        new StationAssignment(1000, "B"),
                        new StationAssignment(1200, "Y")));

        Method optimizeFamilies = InstructionConverter.class
                .getDeclaredMethod("optimizeInstructionFamilies", List.class);
        optimizeFamilies.setAccessible(true);

        List<CuttingInstruction> optimized = (List<CuttingInstruction>) optimizeFamilies.invoke(
                converter,
                List.of(left, right));

        assertEquals(2, optimized.size());
        assertEquals(2, SequenceGroupPostProcessor.countTotalGroups(optimized));
        assertEquals(Map.of("A", 2L, "B", 2L), combinedMessageCounts(optimized, 1000));
        assertEquals(Map.of("X", 2L, "Y", 2L), combinedMessageCounts(optimized, 1200));
        assertTrue(optimized.stream().allMatch(instruction -> instruction.getUsageCount() == 2));
    }

    private static SolverParameters testParams() {
        SolverParameters params = SolverParameters.createDefault();
        params.setTotalWidth(3580);
        params.setUseOptimizedAssignment(false);
        return params;
    }

    private static Map<Integer, Integer> linkedPattern(int widthA, int countA, int widthB, int countB) {
        Map<Integer, Integer> pattern = new LinkedHashMap<>();
        pattern.put(widthA, countA);
        pattern.put(widthB, countB);
        return pattern;
    }

    private static List<SolverOrderItem> orderItems() {
        return List.of(
                item(1000, "A", 2),
                item(1000, "B", 1),
                item(1200, "X", 3));
    }

    private static SolverOrderItem item(int width, String messageText, int demand) {
        SolverOrderItem item = new SolverOrderItem();
        item.setWidth(width);
        item.setMessageText(messageText);
        item.setDemand(demand);
        item.setLength(1350);
        item.setSurfaceTreatment("plain");
        item.setThickness(50);
        item.setGroupKey("group-a");
        return item;
    }

    private static List<String> widthMessages(CuttingInstruction instruction, int width) {
        return instruction.getStationAssignments().stream()
                .filter(assignment -> assignment.getWidth() == width)
                .map(StationAssignment::getMessageText)
                .toList();
    }

    private static Map<String, Long> messageCounts(CuttingInstruction instruction, int width) {
        return instruction.getStationAssignments().stream()
                .filter(assignment -> assignment.getWidth() == width)
                .collect(java.util.stream.Collectors.groupingBy(
                        StationAssignment::getMessageText,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
    }

    private static Map<String, Long> combinedMessageCounts(List<CuttingInstruction> instructions, int width) {
        return instructions.stream()
                .flatMap(instruction -> instruction.getStationAssignments().stream())
                .filter(assignment -> assignment.getWidth() == width)
                .collect(java.util.stream.Collectors.groupingBy(
                        StationAssignment::getMessageText,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
    }

    private static CuttingInstruction instruction(
            int rollWidth,
            Map<Integer, Integer> subRolls,
            List<StationAssignment> assignments) {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setGroupKey("group-a");
        instruction.setRollWidth(rollWidth);
        instruction.setLength(1350);
        instruction.setSurfaceTreatment("plain");
        instruction.setThickness(50);
        instruction.setSubRolls(subRolls);
        instruction.setUsageCount(assignments.size() / subRolls.values().stream().mapToInt(Integer::intValue).sum());
        instruction.setPatternWidth(subRolls.entrySet().stream().mapToInt(entry -> entry.getKey() * entry.getValue()).sum());
        instruction.setWaste(paramsTotalWidth() - instruction.getPatternWidth());
        instruction.setStationAssignments(assignments);
        return instruction;
    }

    private static int paramsTotalWidth() {
        return 3580;
    }

    private static final class TestInstructionConverter extends InstructionConverter {

        private final PatternCandidate pattern;
        private final List<AssignmentMIPSolver.AssignmentBlock> mipBlocks;
        private final boolean skipMip;
        private final boolean mutatePostProcess;
        private final List<List<StationAssignment>> hintRolls;
        private boolean hintCollected;

        private TestInstructionConverter(SolverParameters params,
                PatternCandidate pattern,
                List<AssignmentMIPSolver.AssignmentBlock> mipBlocks,
                boolean skipMip,
                boolean mutatePostProcess) {
            this(params, pattern, mipBlocks, skipMip, mutatePostProcess, null);
        }

        private TestInstructionConverter(SolverParameters params,
                PatternCandidate pattern,
                List<AssignmentMIPSolver.AssignmentBlock> mipBlocks,
                boolean skipMip,
                boolean mutatePostProcess,
                List<List<StationAssignment>> hintRolls) {
            super(params);
            this.pattern = pattern;
            this.mipBlocks = mipBlocks;
            this.skipMip = skipMip;
            this.mutatePostProcess = mutatePostProcess;
            this.hintRolls = hintRolls;
        }

        @Override
        protected Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> solveAssignmentWithMip(
                Map<PatternCandidate, Integer> solution,
                List<SolverOrderItem> groupItems) {
            if (skipMip) {
                return null;
            }

            if (mipBlocks != null) {
                return Map.of(pattern, mipBlocks);
            }

            Map<Integer, String> config1 = new LinkedHashMap<>();
            config1.put(1000, "A");
            config1.put(1200, "X");

            Map<Integer, String> config2 = new LinkedHashMap<>();
            config2.put(1000, "B");
            config2.put(1200, "X");

            return Map.of(pattern, List.of(
                    new AssignmentMIPSolver.AssignmentBlock(config1, 1),
                    new AssignmentMIPSolver.AssignmentBlock(config2, 1),
                    new AssignmentMIPSolver.AssignmentBlock(config1, 1)));
        }

        @Override
        protected Phase2SequenceGroupSolver.SolveResult solveSequenceGroupsWithPhase2(
                Map<PatternCandidate, Integer> solution,
                List<SolverOrderItem> groupItems) {
            return null;
        }

        @Override
        protected Phase2SequenceGroupSolver.SolveResult solveSequenceGroupsWithPhase2(
                Map<PatternCandidate, Integer> solution,
                List<SolverOrderItem> groupItems,
                Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> seedAssignments) {
            return null;
        }

        @Override
        protected void optimizeSequenceGroups(List<CuttingInstruction> instructions) {
            if (!mutatePostProcess || instructions.isEmpty()) {
                return;
            }

            List<StationAssignment> assignments = instructions.get(0).getStationAssignments();
            for (int i = 0; i < assignments.size(); i++) {
                StationAssignment assignment = assignments.get(i);
                if (assignment.getWidth() == 1000) {
                    assignments.set(i, new StationAssignment(1000, "A"));
                }
            }
        }

        @Override
        protected List<List<StationAssignment>> collectInstructionRepackHintRolls(
                CuttingInstruction instruction,
                int currentBlockCount) {
            if (hintRolls == null) {
                return super.collectInstructionRepackHintRolls(instruction, currentBlockCount);
            }

            hintCollected = true;
            return hintRolls;
        }
    }
}
