package test.demo.apsmodule.generator.NewSolver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.service.SolverConfig;

import java.util.HashSet;
import java.util.Set;

public class SolverParameters {

    private static final Logger log = LoggerFactory.getLogger(SolverParameters.class);

    private int minRollWidth = 3200;
    private int maxRollWidth = 3500;
    private int stepSize = 10;
    private int totalWidth = 3580;

    private int totalOverCap = 50;
    private int topK = 3;
    private Set<Integer> forceAllowOverWidths = new HashSet<>();
    private double underPenalty = 1e6;

    private int maxIterations = 300;
    private int maxPatterns = 800;
    private int maxDistinctWidths = 4;

    private long timeoutMs = 120000;
    private long stage4TimeLimit = 30000;

    /**
     * A-layer (pattern-selection MIP) SCIP randomization seed. The seed
     * deterministically steers which 花型集 the selection MIP lands on among
     * tie-degenerate optima; sweeping it is the multi-start dimension that finds a
     * lower sequence-group 花型集 (e.g. 79→73 on T9EST188). Default 0 = legacy.
     */
    private int aLayerScipSeed = 0;
    private double aLayerAlignmentLambda = 0.0;

    private double seqGroupAlpha = 1.0;
    private double seqGroupBeta = 0.0;

    private boolean useOptimizedAssignment = true;

    public int getMinRollWidth() {
        return minRollWidth;
    }

    public void setMinRollWidth(int minRollWidth) {
        this.minRollWidth = minRollWidth;
    }

    public int getMaxRollWidth() {
        return maxRollWidth;
    }

    public void setMaxRollWidth(int maxRollWidth) {
        this.maxRollWidth = maxRollWidth;
    }

    public int getStepSize() {
        return stepSize;
    }

    public void setStepSize(int stepSize) {
        this.stepSize = stepSize;
    }

    public int getTotalWidth() {
        return totalWidth;
    }

    public void setTotalWidth(int totalWidth) {
        this.totalWidth = totalWidth;
    }

    public int getTotalOverCap() {
        return totalOverCap;
    }

    public void setTotalOverCap(int totalOverCap) {
        this.totalOverCap = totalOverCap;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public Set<Integer> getForceAllowOverWidths() {
        return forceAllowOverWidths;
    }

    public void setForceAllowOverWidths(Set<Integer> forceAllowOverWidths) {
        this.forceAllowOverWidths = forceAllowOverWidths != null
                ? new HashSet<>(forceAllowOverWidths)
                : new HashSet<>();
    }

    public double getUnderPenalty() {
        return underPenalty;
    }

    public void setUnderPenalty(double underPenalty) {
        this.underPenalty = underPenalty;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getMaxPatterns() {
        return maxPatterns;
    }

    public void setMaxPatterns(int maxPatterns) {
        this.maxPatterns = maxPatterns;
    }

    public int getMaxDistinctWidths() {
        return maxDistinctWidths;
    }

    public void setMaxDistinctWidths(int maxDistinctWidths) {
        this.maxDistinctWidths = maxDistinctWidths;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getStage4TimeLimit() {
        return stage4TimeLimit;
    }

    public int getALayerScipSeed() {
        return aLayerScipSeed;
    }

    public void setALayerScipSeed(int aLayerScipSeed) {
        this.aLayerScipSeed = aLayerScipSeed;
    }

    public double getALayerAlignmentLambda() {
        return aLayerAlignmentLambda;
    }

    public void setALayerAlignmentLambda(double aLayerAlignmentLambda) {
        this.aLayerAlignmentLambda = aLayerAlignmentLambda;
    }

    public void setStage4TimeLimit(long stage4TimeLimit) {
        this.stage4TimeLimit = stage4TimeLimit;
    }

    public double getSeqGroupAlpha() {
        return seqGroupAlpha;
    }

    public void setSeqGroupAlpha(double seqGroupAlpha) {
        this.seqGroupAlpha = seqGroupAlpha;
    }

    public double getSeqGroupBeta() {
        return seqGroupBeta;
    }

    public void setSeqGroupBeta(double seqGroupBeta) {
        this.seqGroupBeta = seqGroupBeta;
    }

    public boolean isUseOptimizedAssignment() {
        return useOptimizedAssignment;
    }

    public void setUseOptimizedAssignment(boolean useOptimizedAssignment) {
        this.useOptimizedAssignment = useOptimizedAssignment;
    }

    public void mergeFrom(SolverConfig config) {
        if (config == null) {
            sanitize();
            return;
        }

        this.minRollWidth = config.getMinWidth();
        this.maxRollWidth = config.getMaxWidth();
        this.stepSize = config.getStepSize();
        this.totalWidth = config.getTotalWidth();
        this.totalOverCap = config.getTotalOverCap();
        this.topK = config.getNewSolverTopK();
        this.forceAllowOverWidths = new HashSet<>(config.getForceAllowOverWidths());
        this.underPenalty = config.getNewSolverUnderPenalty();
        this.maxPatterns = config.getNewSolverMaxPatterns();
        this.maxDistinctWidths = config.getNewSolverMaxDistinctWidths();
        this.stage4TimeLimit = config.getNewSolverStage4TimeLimit();
        this.seqGroupAlpha = config.getNewSolverSeqGroupAlpha();
        this.seqGroupBeta = config.getNewSolverSeqGroupBeta();
        this.useOptimizedAssignment = config.isNewSolverUseOptimizedAssignment();

        if (config.getMaxIterations() > 0) {
            this.maxIterations = config.getMaxIterations();
        }
        if (config.getTimeoutMs() > 0) {
            this.timeoutMs = config.getTimeoutMs();
        }

        sanitize();
    }

    public static SolverParameters createDefault() {
        return new SolverParameters();
    }

    public SolverParameters copy() {
        SolverParameters copy = new SolverParameters();
        copy.minRollWidth = this.minRollWidth;
        copy.maxRollWidth = this.maxRollWidth;
        copy.stepSize = this.stepSize;
        copy.totalWidth = this.totalWidth;
        copy.totalOverCap = this.totalOverCap;
        copy.topK = this.topK;
        copy.forceAllowOverWidths = new HashSet<>(this.forceAllowOverWidths);
        copy.underPenalty = this.underPenalty;
        copy.maxIterations = this.maxIterations;
        copy.maxPatterns = this.maxPatterns;
        copy.maxDistinctWidths = this.maxDistinctWidths;
        copy.timeoutMs = this.timeoutMs;
        copy.stage4TimeLimit = this.stage4TimeLimit;
        copy.aLayerScipSeed = this.aLayerScipSeed;
        copy.aLayerAlignmentLambda = this.aLayerAlignmentLambda;
        copy.seqGroupAlpha = this.seqGroupAlpha;
        copy.seqGroupBeta = this.seqGroupBeta;
        copy.useOptimizedAssignment = this.useOptimizedAssignment;
        copy.sanitize();
        return copy;
    }

    public void sanitize() {
        if (stepSize <= 0) {
            log.error("WARNING: Invalid stepSize <= 0, fallback to 10.");
            stepSize = 10;
        }
        if (forceAllowOverWidths == null) {
            forceAllowOverWidths = new HashSet<>();
        }
        if (aLayerAlignmentLambda < 0.0 || Double.isNaN(aLayerAlignmentLambda)) {
            aLayerAlignmentLambda = 0.0;
        }
    }

    @Override
    public String toString() {
        return "SolverParameters{" +
                "minRollWidth=" + minRollWidth +
                ", maxRollWidth=" + maxRollWidth +
                ", totalOverCap=" + totalOverCap +
                ", topK=" + topK +
                ", forceAllowOverWidths=" + forceAllowOverWidths +
                ", maxPatterns=" + maxPatterns +
                ", maxDistinctWidths=" + maxDistinctWidths +
                ", underPenalty=" + underPenalty +
                ", maxIterations=" + maxIterations +
                ", timeoutMs=" + timeoutMs +
                ", stage4TimeLimit=" + stage4TimeLimit +
                ", aLayerAlignmentLambda=" + aLayerAlignmentLambda +
                ", seqGroupAlpha=" + seqGroupAlpha +
                ", seqGroupBeta=" + seqGroupBeta +
                ", useOptimizedAssignment=" + useOptimizedAssignment +
                '}';
    }
}
