package test.demo.apsmodule.solver.kernel;

import java.util.Set;

import test.demo.apsmodule.service.SolverConfig;

/**
 * Immutable solver settings at the complete-kernel boundary.
 */
public record SolverKernelSettings(
        String mode,
        int minWidth,
        int maxWidth,
        int stepSize,
        int totalWidth,
        int totalOverCap,
        int maxIterations,
        long timeoutMs,
        boolean useNewSolver,
        int newSolverTopK,
        int newSolverMaxPatterns,
        int newSolverMaxDistinctWidths,
        long newSolverStage4TimeLimit,
        double newSolverSeqGroupAlpha,
        double newSolverSeqGroupBeta,
        boolean newSolverUseOptimizedAssignment,
        double newSolverUnderPenalty,
        Set<Integer> forceAllowOverWidths) {

    public SolverKernelSettings {
        forceAllowOverWidths = forceAllowOverWidths == null
                ? Set.of()
                : Set.copyOf(forceAllowOverWidths);
    }

    public static SolverKernelSettings from(SolverConfig config) {
        return new SolverKernelSettings(
                config.getMode(),
                config.getMinWidth(),
                config.getMaxWidth(),
                config.getStepSize(),
                config.getTotalWidth(),
                config.getTotalOverCap(),
                config.getMaxIterations(),
                config.getTimeoutMs(),
                config.isUseNewSolver(),
                config.getNewSolverTopK(),
                config.getNewSolverMaxPatterns(),
                config.getNewSolverMaxDistinctWidths(),
                config.getNewSolverStage4TimeLimit(),
                config.getNewSolverSeqGroupAlpha(),
                config.getNewSolverSeqGroupBeta(),
                config.isNewSolverUseOptimizedAssignment(),
                config.getNewSolverUnderPenalty(),
                config.getForceAllowOverWidths());
    }

    public SolverConfig toSolverConfig() {
        SolverConfig config = new SolverConfig();
        config.setMode(mode);
        config.setMinWidth(minWidth);
        config.setMaxWidth(maxWidth);
        config.setStepSize(stepSize);
        config.setTotalWidth(totalWidth);
        config.setTotalOverCap(totalOverCap);
        config.setMaxIterations(maxIterations);
        config.setTimeoutMs(timeoutMs);
        config.setUseNewSolver(useNewSolver);
        config.setNewSolverTopK(newSolverTopK);
        config.setNewSolverMaxPatterns(newSolverMaxPatterns);
        config.setNewSolverMaxDistinctWidths(newSolverMaxDistinctWidths);
        config.setNewSolverStage4TimeLimit(newSolverStage4TimeLimit);
        config.setNewSolverSeqGroupAlpha(newSolverSeqGroupAlpha);
        config.setNewSolverSeqGroupBeta(newSolverSeqGroupBeta);
        config.setNewSolverUseOptimizedAssignment(newSolverUseOptimizedAssignment);
        config.setNewSolverUnderPenalty(newSolverUnderPenalty);
        config.setForceAllowOverWidths(forceAllowOverWidths);
        return config;
    }
}
