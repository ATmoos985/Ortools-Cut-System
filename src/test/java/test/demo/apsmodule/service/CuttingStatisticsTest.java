package test.demo.apsmodule.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CuttingStatisticsTest {

    @Test
    void summarizeInstructionsUsesMotherRollWidthAndLengthWeighting() {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setUsageCount(2);
        instruction.setLength(10);
        instruction.setRollWidth(3300);
        instruction.setSubRolls(Map.of(1100, 3));
        instruction.setPatternWidth(3300);
        instruction.setWaste(280);

        CuttingStatistics.Summary summary = CuttingStatistics.summarizeInstructions(List.of(instruction), 3580);

        assertEquals(2, summary.totalRollsUsed());
        assertEquals(5600, summary.totalWaste());
        assertEquals(92.17877094972067, summary.utilizationRate(), 1e-9);
    }

    @Test
    void summarizeLegacyInstructionsUsesMotherRollWidthInsteadOfRollWidth() {
        CuttingOptimizationResult.CuttingInstruction instruction = new CuttingOptimizationResult.CuttingInstruction();
        instruction.setUsageCount(1);
        instruction.setLength(20);
        instruction.setRollWidth(3300);
        instruction.setWaste(280);

        Map<Integer, Integer> subRolls = new LinkedHashMap<>();
        subRolls.put(1650, 2);
        instruction.setSubRolls(subRolls);

        CuttingStatistics.Summary summary = CuttingStatistics.summarizeLegacyInstructions(List.of(instruction), 3580);

        assertEquals(1, summary.totalRollsUsed());
        assertEquals(5600, summary.totalWaste());
        assertEquals(92.17877094972067, summary.utilizationRate(), 1e-9);
    }
}
