package test.demo.apsmodule.service;

import java.util.List;
import java.util.Map;

public final class CuttingStatistics {

    private CuttingStatistics() {
    }

    public static Summary summarizeInstructions(List<CuttingInstruction> instructions, int totalWidth) {
        long totalRollsUsed = 0;
        long totalWaste = 0;
        long totalMaterial = 0;
        long totalEffectiveMaterial = 0;
        long totalPatternArea = 0;

        if (instructions != null) {
            for (CuttingInstruction instruction : instructions) {
                int usageCount = instruction.getUsageCount();
                int length = instruction.getLength();
                int patternWidth = instruction.getPatternWidth() > 0
                        ? instruction.getPatternWidth()
                        : calculatePatternWidth(instruction.getSubRolls());

                totalRollsUsed += usageCount;
                totalWaste += (long) Math.max(totalWidth - patternWidth, 0) * length * usageCount;
                totalMaterial += (long) totalWidth * length * usageCount;
                totalEffectiveMaterial += (long) effectiveRollWidth(
                        instruction.getRollWidth(), patternWidth, totalWidth) * length * usageCount;
                totalPatternArea += (long) patternWidth * length * usageCount;
            }
        }

        return buildSummary(totalRollsUsed, totalWaste, totalMaterial, totalEffectiveMaterial, totalPatternArea);
    }

    public static Summary summarizeLegacyInstructions(
            List<CuttingOptimizationResult.CuttingInstruction> instructions, int totalWidth) {
        long totalRollsUsed = 0;
        long totalWaste = 0;
        long totalMaterial = 0;
        long totalEffectiveMaterial = 0;
        long totalPatternArea = 0;

        if (instructions != null) {
            for (CuttingOptimizationResult.CuttingInstruction instruction : instructions) {
                int usageCount = instruction.getUsageCount();
                int length = instruction.getLength() != null ? instruction.getLength() : 0;
                int patternWidth = calculatePatternWidth(instruction.getSubRolls());

                totalRollsUsed += usageCount;
                totalWaste += (long) Math.max(totalWidth - patternWidth, 0) * length * usageCount;
                totalMaterial += (long) totalWidth * length * usageCount;
                totalEffectiveMaterial += (long) effectiveRollWidth(
                        instruction.getRollWidth(), patternWidth, totalWidth) * length * usageCount;
                totalPatternArea += (long) patternWidth * length * usageCount;
            }
        }

        return buildSummary(totalRollsUsed, totalWaste, totalMaterial, totalEffectiveMaterial, totalPatternArea);
    }

    public static int calculatePatternWidth(Map<Integer, Integer> subRolls) {
        if (subRolls == null || subRolls.isEmpty()) {
            return 0;
        }
        return subRolls.entrySet().stream()
                .mapToInt(entry -> entry.getKey() * entry.getValue())
                .sum();
    }

    private static int effectiveRollWidth(int rollWidth, int patternWidth, int totalWidth) {
        int configuredWidth = rollWidth > 0 ? rollWidth : totalWidth;
        return Math.max(configuredWidth, patternWidth);
    }

    private static Summary buildSummary(
            long totalRollsUsed,
            long totalWaste,
            long totalMaterial,
            long totalEffectiveMaterial,
            long totalPatternArea) {
        double utilizationRate = totalMaterial > 0
                ? (double) totalPatternArea / totalMaterial * 100
                : 0.0;
        double effectiveUtilizationRate = totalEffectiveMaterial > 0
                ? (double) totalPatternArea / totalEffectiveMaterial * 100
                : 0.0;

        return new Summary(
                (int) Math.min(totalRollsUsed, Integer.MAX_VALUE),
                (int) Math.min(totalWaste, Integer.MAX_VALUE),
                utilizationRate,
                effectiveUtilizationRate);
    }

    public record Summary(
            int totalRollsUsed,
            int totalWaste,
            double utilizationRate,
            double effectiveUtilizationRate) {
    }
}
