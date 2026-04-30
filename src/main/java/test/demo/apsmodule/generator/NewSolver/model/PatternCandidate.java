package test.demo.apsmodule.generator.NewSolver.model;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 切割模式候选
 * 
 * 表示一种切割方案：在特定卷宽上，各宽度的切割次数
 */
public class PatternCandidate {

    private static final double EPS_WASTE = 0.001;

    /** 模式定义：width -> count */
    private final Map<Integer, Integer> pattern;
    /** 卷宽 (mm) */
    private final int rollWidth;
    /** 模式总宽度 (mm) */
    private final int patternWidth;

    public PatternCandidate(Map<Integer, Integer> pattern, int rollWidth) {
        this.pattern = pattern.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
        this.rollWidth = rollWidth;
        this.patternWidth = this.pattern.entrySet().stream()
                .mapToInt(e -> e.getKey() * e.getValue()).sum();
    }

    // ==================== 计算方法 ====================

    /**
     * 计算利用率
     */
    public double getUtilization() {
        return (double) patternWidth / rollWidth;
    }

    /**
     * 计算基于卷宽的废边
     */
    public int getWaste() {
        return rollWidth - patternWidth;
    }

    /**
     * 计算基于母卷总宽度的实际废边
     * 
     * @param totalWidth 母卷总宽度
     * @return 实际废边 = totalWidth - patternWidth
     */
    public int getRealWaste(int totalWidth) {
        return totalWidth - patternWidth;
    }

    /**
     * 计算模式成本（用于列生成定价）
     */
    public double getCost(double epsWaste) {
        return 1.0 + epsWaste * getWaste();
    }

    public double getCost() {
        return 1.0 + EPS_WASTE * getWaste();
    }

    public double getCost(double epsWaste, int totalWidth) {
        return 1.0 + epsWaste * getRealWaste(totalWidth);
    }

    public double getCost(int totalWidth) {
        return 1.0 + EPS_WASTE * getRealWaste(totalWidth);
    }

    /**
     * 规范化签名，用于去重
     */
    public String signature() {
        return rollWidth + "|" + pattern.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "x" + e.getValue())
                .collect(Collectors.joining(","));
    }

    // ==================== Getters ====================

    public Map<Integer, Integer> getPattern() {
        return Collections.unmodifiableMap(pattern);
    }

    public int getRollWidth() {
        return rollWidth;
    }

    public int getPatternWidth() {
        return patternWidth;
    }

    /**
     * 获取模式中的宽度种类数
     */
    public int getWidthCount() {
        return pattern.size();
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        PatternCandidate that = (PatternCandidate) o;
        return rollWidth == that.rollWidth && pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern, rollWidth);
    }

    @Override
    public String toString() {
        return pattern + " @" + rollWidth + "mm (pw=" + patternWidth +
                ", util=" + String.format("%.1f%%", getUtilization() * 100) + ")";
    }
}
