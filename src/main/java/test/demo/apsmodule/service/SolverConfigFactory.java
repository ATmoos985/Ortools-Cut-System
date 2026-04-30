package test.demo.apsmodule.service;

import org.springframework.stereotype.Component;
import test.demo.rest.dto.OptimizationRequest;

@Component
public class SolverConfigFactory {

    public SolverConfig fromOptimizationRequest(OptimizationRequest request) {
        SolverConfig config = new SolverConfig();
        config.setMode(request.isFlexibleWidth() ? "variable" : "fixed");

        if (request.isFlexibleWidth()) {
            config.setMinWidth(request.getMinWidth() > 0 ? request.getMinWidth() : 4300);
            config.setMaxWidth(request.getMaxWidth() > 0 ? request.getMaxWidth() : 4600);
            config.setStepSize(request.getStepSize() > 0 ? request.getStepSize() : 10);
            config.setTotalWidth(request.getTotalWidth() > 0 ? request.getTotalWidth() : 4600);
        } else {
            int fixedWidth = request.getFixedWidth() > 0 ? request.getFixedWidth() : 4600;
            config.setMinWidth(fixedWidth);
            config.setMaxWidth(fixedWidth);
            config.setStepSize(0);
            config.setTotalWidth(fixedWidth);
        }

        config.setTotalOverCap(request.getTotalOverCap() > 0 ? request.getTotalOverCap() : 30);
        config.setMaxIterations(request.getMaxIterations() > 0 ? request.getMaxIterations() : 300);
        config.setTimeoutMs(request.getTimeoutMs() > 0 ? request.getTimeoutMs() : 120000);
        config.setUseNewSolver(request.isUseNewSolver());
        config.setNewSolverTopK(request.getNewSolverTopK() > 0 ? request.getNewSolverTopK() : 3);
        config.setNewSolverMaxPatterns(request.getNewSolverMaxPatterns());
        config.setNewSolverMaxDistinctWidths(request.getNewSolverMaxDistinctWidths());
        config.setNewSolverStage4TimeLimit(request.getNewSolverStage4TimeLimit());
        config.setNewSolverSeqGroupAlpha(request.getNewSolverSeqGroupAlpha());
        config.setNewSolverSeqGroupBeta(request.getNewSolverSeqGroupBeta());
        config.setNewSolverUseOptimizedAssignment(request.isNewSolverUseOptimizedAssignment());
        config.setNewSolverUnderPenalty(request.getNewSolverUnderPenalty());

        return config;
    }
}
