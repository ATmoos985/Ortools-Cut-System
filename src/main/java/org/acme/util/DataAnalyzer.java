package org.acme.util;

import java.util.Arrays;
import java.util.List;

/**
 * 数据分析工具类，用于分析子布数据的特征
 */
public class DataAnalyzer {
    
    public static class SubRollData {
        public int id;
        public int width;
        public int demand;
        public String messageText; // 添加消息字段
        
        public SubRollData(int id, int width, int demand) {
            this.id = id;
            this.width = width;
            this.demand = demand;
            this.messageText = "";
        }
        
        public SubRollData(int id, int width, int demand, String messageText) {
            this.id = id;
            this.width = width;
            this.demand = demand;
            this.messageText = messageText != null ? messageText : "";
        }
        
        @Override
        public String toString() {
            return String.format("SubRoll{id=%d, width=%d, demand=%d, message='%s'}", 
                id, width, demand, messageText);
        }
    }
    
    // 真实订单数据
    public static final List<SubRollData> TEST_DATA = Arrays.asList(
        new SubRollData(1, 600, 4),
        new SubRollData(2, 700, 10),
        new SubRollData(3, 710, 53),
        new SubRollData(4, 720, 52),
        new SubRollData(5, 730, 42),
        new SubRollData(6, 740, 40),
        new SubRollData(7, 750, 68),
        new SubRollData(8, 760, 96),
        new SubRollData(9, 820, 10),
        new SubRollData(10, 830, 12),
        new SubRollData(11, 840, 290),
        new SubRollData(12, 850, 120),
        new SubRollData(13, 860, 22),
        new SubRollData(14, 870, 10),
        new SubRollData(15, 880, 8),
        new SubRollData(16, 890, 77),
        new SubRollData(17, 900, 10),
        new SubRollData(18, 910, 6),
        new SubRollData(19, 920, 10),
        new SubRollData(20, 930, 46),
        new SubRollData(21, 940, 40),
        new SubRollData(22, 950, 44),
        new SubRollData(23, 960, 4),
        new SubRollData(24, 970, 260),
        new SubRollData(25, 980, 172),
        new SubRollData(26, 990, 260),
        new SubRollData(27, 1000, 153),
        new SubRollData(28, 1090, 42),
        new SubRollData(29, 1120, 6),
        new SubRollData(30, 1130, 28),
        new SubRollData(31, 1140, 338),
        new SubRollData(32, 1150, 204),
        new SubRollData(33, 1160, 36),
        new SubRollData(34, 1240, 2),
        new SubRollData(35, 1250, 416),
        new SubRollData(36, 1260, 112),
        new SubRollData(37, 1270, 50),
        new SubRollData(38, 1280, 28),
        new SubRollData(39, 1310, 31),
        new SubRollData(40, 1470, 38),
        new SubRollData(41, 1485, 10)
    );
    
    public static final int MOTHER_ROLL_WIDTH = 3380;
    public static final double MAX_WASTE_PERCENTAGE = 10.0;
    
    /**
     * 分析数据特征
     */
    public static void analyzeData() {
        System.out.println("=== 数据分析报告 ===");
        System.out.println("母卷宽度: " + MOTHER_ROLL_WIDTH);
        System.out.println("最大浪费率: " + MAX_WASTE_PERCENTAGE + "%");
        System.out.println();
        
        // 计算总需求
        int totalDemand = TEST_DATA.stream().mapToInt(data -> data.demand).sum();
        System.out.println("子卷种类数: " + TEST_DATA.size());
        System.out.println("总需求数量: " + totalDemand);
        
        // 宽度分析
        int minWidth = TEST_DATA.stream().mapToInt(data -> data.width).min().orElse(0);
        int maxWidth = TEST_DATA.stream().mapToInt(data -> data.width).max().orElse(0);
        double avgWidth = TEST_DATA.stream().mapToInt(data -> data.width).average().orElse(0);
        
        System.out.println("子卷宽度范围: " + minWidth + " - " + maxWidth);
        System.out.println("子卷平均宽度: " + String.format("%.2f", avgWidth));
        
        // 需求分析
        int minDemand = TEST_DATA.stream().mapToInt(data -> data.demand).min().orElse(0);
        int maxDemand = TEST_DATA.stream().mapToInt(data -> data.demand).max().orElse(0);
        double avgDemand = TEST_DATA.stream().mapToInt(data -> data.demand).average().orElse(0);
        
        System.out.println("需求数量范围: " + minDemand + " - " + maxDemand);
        System.out.println("平均需求数量: " + String.format("%.2f", avgDemand));
        
        // 理论最少母卷数（不考虑组合约束）
        long totalWidth = TEST_DATA.stream().mapToLong(data -> (long) data.width * data.demand).sum();
        int theoreticalMinRolls = (int) Math.ceil((double) totalWidth / MOTHER_ROLL_WIDTH);
        
        System.out.println();
        System.out.println("总宽度需求: " + totalWidth);
        System.out.println("理论最少母卷数: " + theoreticalMinRolls);
        System.out.println("理论利用率: " + String.format("%.2f%%", (double) totalWidth / (theoreticalMinRolls * MOTHER_ROLL_WIDTH) * 100));
        
        // 分析可能的组合情况
        System.out.println();
        System.out.println("=== 组合分析 ===");
        
        // 统计不同宽度范围的子卷
        long smallRolls = TEST_DATA.stream().filter(data -> data.width <= 1000).mapToInt(data -> data.demand).sum();
        long mediumRolls = TEST_DATA.stream().filter(data -> data.width > 1000 && data.width <= 1300).mapToInt(data -> data.demand).sum();
        long largeRolls = TEST_DATA.stream().filter(data -> data.width > 1300).mapToInt(data -> data.demand).sum();
        
        System.out.println("小尺寸子卷 (≤1000): " + smallRolls + " 个");
        System.out.println("中尺寸子卷 (1000-1300): " + mediumRolls + " 个");
        System.out.println("大尺寸子卷 (>1300): " + largeRolls + " 个");
        
        // 详细数据列表
        System.out.println();
        System.out.println("=== 详细数据列表 ===");
        TEST_DATA.forEach(data -> 
            System.out.println(String.format("ID:%2d  宽度:%4dmm  需求:%3d件", data.id, data.width, data.demand)));
    }
    
    public static void main(String[] args) {
        analyzeData();
    }
}