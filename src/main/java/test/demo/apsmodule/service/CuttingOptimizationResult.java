package test.demo.apsmodule.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CuttingOptimizationResult {

    private int totalRollsUsed;
    private int totalWaste;
    private double utilizationRate;
    private List<CuttingInstruction> cuttingInstructions;
    private Map<Integer, Integer> demandFulfillment;
    private long executionTimeMs;
    private int iterations;
    private int totalWidth;

    public static class CuttingInstruction {
        private int rollNumber;
        private Map<Integer, Integer> subRolls;
        private int waste;
        private int totalWidth;
        private int rollWidth;
        private String groupKey;
        private boolean newGroup;
        private Map<Integer, Integer> widthToMessageNumber;
        private Map<Integer, String> widthToSalesperson;
        private Map<Integer, String> widthToMessageText;
        private Map<Integer, Integer> widthToLength;
        private Map<Integer, List<ProductionOrder>> widthToAllOrderItems;
        private Integer thickness;
        private Integer length;
        private String surfaceTreatment;
        private int usageCount;
        private List<StationAssignment> stationAssignments;

        public int getRollNumber() {
            return rollNumber;
        }

        public void setRollNumber(int rollNumber) {
            this.rollNumber = rollNumber;
        }

        public Map<Integer, Integer> getSubRolls() {
            return subRolls;
        }

        public void setSubRolls(Map<Integer, Integer> subRolls) {
            this.subRolls = subRolls;
        }

        public int getWaste() {
            return waste;
        }

        public void setWaste(int waste) {
            this.waste = waste;
        }

        public int getTotalWidth() {
            return totalWidth;
        }

        public void setTotalWidth(int totalWidth) {
            this.totalWidth = totalWidth;
        }

        public int getRollWidth() {
            return rollWidth;
        }

        public void setRollWidth(int rollWidth) {
            this.rollWidth = rollWidth;
        }

        public String getGroupKey() {
            return groupKey;
        }

        public void setGroupKey(String groupKey) {
            this.groupKey = groupKey;
        }

        public boolean isNewGroup() {
            return newGroup;
        }

        public void setNewGroup(boolean newGroup) {
            this.newGroup = newGroup;
        }

        public Map<Integer, Integer> getWidthToMessageNumber() {
            return widthToMessageNumber;
        }

        public void setWidthToMessageNumber(Map<Integer, Integer> widthToMessageNumber) {
            this.widthToMessageNumber = widthToMessageNumber;
        }

        public Map<Integer, String> getWidthToSalesperson() {
            return widthToSalesperson;
        }

        public void setWidthToSalesperson(Map<Integer, String> widthToSalesperson) {
            this.widthToSalesperson = widthToSalesperson;
        }

        public Map<Integer, String> getWidthToMessageText() {
            return widthToMessageText;
        }

        public void setWidthToMessageText(Map<Integer, String> widthToMessageText) {
            this.widthToMessageText = widthToMessageText;
        }

        public Map<Integer, Integer> getWidthToLength() {
            return widthToLength;
        }

        public void setWidthToLength(Map<Integer, Integer> widthToLength) {
            this.widthToLength = widthToLength;
        }

        public Map<Integer, List<ProductionOrder>> getWidthToAllOrderItems() {
            return widthToAllOrderItems;
        }

        public void setWidthToAllOrderItems(Map<Integer, List<ProductionOrder>> widthToAllOrderItems) {
            this.widthToAllOrderItems = widthToAllOrderItems;
        }

        public Integer getThickness() {
            return thickness;
        }

        public void setThickness(Integer thickness) {
            this.thickness = thickness;
        }

        public Integer getLength() {
            return length;
        }

        public void setLength(Integer length) {
            this.length = length;
        }

        public String getSurfaceTreatment() {
            return surfaceTreatment;
        }

        public void setSurfaceTreatment(String surfaceTreatment) {
            this.surfaceTreatment = surfaceTreatment;
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
            this.stationAssignments = stationAssignments;
        }

        @Override
        public String toString() {
            return String.format("Roll %d: %s, waste: %d", rollNumber, subRolls, waste);
        }
    }

    public static class StationAssignment {
        private int stationIndex;
        private int width;
        private String messageText;
        private ProductionOrder orderItem;
        private boolean deleted;

        public StationAssignment() {
        }

        public StationAssignment(int stationIndex, int width, ProductionOrder orderItem) {
            this.stationIndex = stationIndex;
            this.width = width;
            this.orderItem = orderItem;
            this.messageText = orderItem != null ? orderItem.getMessageText() : null;
        }

        public int getStationIndex() {
            return stationIndex;
        }

        public void setStationIndex(int stationIndex) {
            this.stationIndex = stationIndex;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public String getMessageText() {
            return messageText;
        }

        public void setMessageText(String messageText) {
            this.messageText = messageText;
        }

        public ProductionOrder getOrderItem() {
            return orderItem;
        }

        public void setOrderItem(ProductionOrder orderItem) {
            this.orderItem = orderItem;
            if (orderItem != null && this.messageText == null) {
                this.messageText = orderItem.getMessageText();
            }
        }

        public boolean isDeleted() {
            return deleted;
        }

        public void setDeleted(boolean deleted) {
            this.deleted = deleted;
        }
    }

    public int getTotalRollsUsed() {
        return totalRollsUsed;
    }

    public void setTotalRollsUsed(int totalRollsUsed) {
        this.totalRollsUsed = totalRollsUsed;
    }

    public int getTotalWaste() {
        return totalWaste;
    }

    public void setTotalWaste(int totalWaste) {
        this.totalWaste = totalWaste;
    }

    public double getUtilizationRate() {
        return utilizationRate;
    }

    public void setUtilizationRate(double utilizationRate) {
        this.utilizationRate = utilizationRate;
    }

    public List<CuttingInstruction> getCuttingInstructions() {
        return cuttingInstructions;
    }

    public void setCuttingInstructions(List<CuttingInstruction> cuttingInstructions) {
        this.cuttingInstructions = cuttingInstructions;
    }

    public Map<Integer, Integer> getDemandFulfillment() {
        return demandFulfillment;
    }

    public void setDemandFulfillment(Map<Integer, Integer> demandFulfillment) {
        this.demandFulfillment = demandFulfillment;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public int getIterations() {
        return iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = iterations;
    }

    public int getTotalWidth() {
        return totalWidth;
    }

    public void setTotalWidth(int totalWidth) {
        this.totalWidth = totalWidth;
    }

    public void printSummary() {
        if (cuttingInstructions != null && !cuttingInstructions.isEmpty()) {
            Map<String, Integer> patternUsageMap = new HashMap<>();
            for (CuttingInstruction instruction : cuttingInstructions) {
                String patternKey = instruction.getSubRolls().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "x" + entry.getValue())
                        .collect(java.util.stream.Collectors.joining(","));
                patternUsageMap.put(patternKey, patternUsageMap.getOrDefault(patternKey, 0) + 1);
            }

            patternUsageMap.entrySet().stream()
                    .sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()))
                    .forEach(entry -> {
                    });
        }

        if (demandFulfillment != null && !demandFulfillment.isEmpty()) {
            demandFulfillment.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        int width = entry.getKey();
                        int excess = entry.getValue();
                    });
        }
    }

    public CuttingOptimizationResult() {
        this.demandFulfillment = new HashMap<>();
    }

    public CuttingOptimizationResult(int totalRollsUsed, int totalWaste, double utilizationRate,
            List<CuttingInstruction> cuttingInstructions,
            Map<Integer, Integer> demandFulfillment,
            long executionTimeMs, int iterations) {
        this.totalRollsUsed = totalRollsUsed;
        this.totalWaste = totalWaste;
        this.utilizationRate = utilizationRate;
        this.cuttingInstructions = cuttingInstructions;
        this.demandFulfillment = demandFulfillment != null ? demandFulfillment : new HashMap<>();
        this.executionTimeMs = executionTimeMs;
        this.iterations = iterations;
    }
}
