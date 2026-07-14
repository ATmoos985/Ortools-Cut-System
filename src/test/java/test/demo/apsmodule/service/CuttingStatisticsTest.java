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
        assertEquals(100.0, summary.effectiveUtilizationRate(), 1e-9);
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
        assertEquals(100.0, summary.effectiveUtilizationRate(), 1e-9);
    }

    @Test
    void effectiveUtilizationFallsBackToMotherWidthAndNeverExceedsOneHundredPercent() {
        CuttingOptimizationResult.CuttingInstruction missingRollWidth = legacyInstruction(1, 10, 0, 3300);
        CuttingOptimizationResult.CuttingInstruction narrowerRollWidth = legacyInstruction(1, 20, 3200, 3300);

        CuttingStatistics.Summary summary = CuttingStatistics.summarizeLegacyInstructions(
                List.of(missingRollWidth, narrowerRollWidth), 3580);

        double expected = (3300.0 * 10 + 3300.0 * 20) / (3580.0 * 10 + 3300.0 * 20) * 100;
        assertEquals(expected, summary.effectiveUtilizationRate(), 1e-9);
    }

    private CuttingOptimizationResult.CuttingInstruction legacyInstruction(
            int usageCount, int length, int rollWidth, int patternWidth) {
        CuttingOptimizationResult.CuttingInstruction instruction = new CuttingOptimizationResult.CuttingInstruction();
        instruction.setUsageCount(usageCount);
        instruction.setLength(length);
        instruction.setRollWidth(rollWidth);
        instruction.setSubRolls(Map.of(patternWidth, 1));
        return instruction;
    }
}
