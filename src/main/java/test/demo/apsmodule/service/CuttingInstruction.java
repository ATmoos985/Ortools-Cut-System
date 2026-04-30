package test.demo.apsmodule.service;

import java.util.*;

/**
 * 切割指令模型
 * 统一的切割模式输出结构，支持固定和可变两种模式
 */
public class CuttingInstruction {

    private String groupKey;
    private int rollWidth; // 固定模式=固定宽度，可变模式=求解器选择宽度
    private int length;
    private String surfaceTreatment;
    private int thickness;

    // width → stationCount
    private Map<Integer, Integer> subRolls = new LinkedHashMap<>();

    private int usageCount; // 这个模式使用多少车

    private List<StationAssignment> stationAssignments = new ArrayList<>();

    // 🔥 新增：废边计算相关字段
    private int patternWidth; // 模式组合的总宽度（所有子卷宽度之和）
    private int waste; // 废边 = totalWidth（母卷总宽度）- patternWidth

    // Getters and Setters
    public String getGroupKey() {
        return groupKey;
    }

    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }

    public int getRollWidth() {
        return rollWidth;
    }

    public void setRollWidth(int rollWidth) {
        this.rollWidth = rollWidth;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public String getSurfaceTreatment() {
        return surfaceTreatment;
    }

    public void setSurfaceTreatment(String surfaceTreatment) {
        this.surfaceTreatment = surfaceTreatment;
    }

    public int getThickness() {
        return thickness;
    }

    public void setThickness(int thickness) {
        this.thickness = thickness;
    }

    public Map<Integer, Integer> getSubRolls() {
        return subRolls;
    }

    public void setSubRolls(Map<Integer, Integer> subRolls) {
        this.subRolls = subRolls != null ? subRolls : new LinkedHashMap<>();
    }

    public int getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(int usageCount) {
        this.usageCount = usageCount;
    }

    public List<StationAssignment> getStationAssignments() {
        return stationAssignments;
    }

    public void setStationAssignments(List<StationAssignment> stationAssignments) {
        this.stationAssignments = stationAssignments != null ? stationAssignments : new ArrayList<>();
    }

    // 🔥 新增：废边相关 getter/setter
    public int getPatternWidth() {
        return patternWidth;
    }

    public void setPatternWidth(int patternWidth) {
        this.patternWidth = patternWidth;
    }

    public int getWaste() {
        return waste;
    }

    public void setWaste(int waste) {
        this.waste = waste;
    }
}
