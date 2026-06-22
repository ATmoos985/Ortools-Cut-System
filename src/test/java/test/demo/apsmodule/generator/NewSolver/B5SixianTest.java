package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Real production case "四线T9EST188" (83 orders, ~1825 rolls). Human planners achieve
 * 47 sequence groups; the production system 55 — at identical efficiency. This harness
 * runs the NewSolver to see where it lands, as the baseline for sequence-group
 * consolidation (Approach 3 / LNS).
 */
class B5SixianTest {

    @Test
    void runSixian() throws Exception {
        List<SolverOrderItem> items = loadItems();
        SolverConfig config = buildConfig();

        long t0 = System.currentTimeMillis();
        CuttingSolver solver = new CuttingSolver();
        List<CuttingInstruction> instructions = solver.solve(items, config);
        long elapsed = System.currentTimeMillis() - t0;

        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(instructions);

        System.out.println("\n##### SIXIAN RESULT #####");
        System.out.println("items=" + items.size() + " instructions=" + instructions.size());
        System.out.println("groups=" + stats.groups()
                + " oddCarGroups=" + stats.oddCarGroups()
                + " smallCarGroups=" + stats.smallCarGroups());
        System.out.println("elapsedMs=" + elapsed + "   (human=47, system=55)");
        System.out.println("#########################\n");

        dumpPatterns(instructions);
    }

    /** Dump my solver's 花型(搭切组合) distribution + per-order group spread, to compare with 人工. */
    private void dumpPatterns(List<CuttingInstruction> ins) {
        // 花型 = sorted multiset of subRoll widths; aggregate blocks + cars per 花型
        java.util.Map<String, int[]> byPat = new java.util.TreeMap<>();   // key -> [blocks, cars]
        java.util.Map<String, Integer> msgGroups = new java.util.TreeMap<>(); // 编号 -> distinct (花型) count
        java.util.Map<String, java.util.Set<String>> msgPats = new java.util.HashMap<>();
        for (CuttingInstruction ci : ins) {
            java.util.List<Integer> ws = new java.util.ArrayList<>();
            for (var e : ci.getSubRolls().entrySet()) {
                for (int k = 0; k < e.getValue(); k++) ws.add(e.getKey());
            }
            java.util.Collections.sort(ws);
            String key = ws.toString();
            int[] agg = byPat.computeIfAbsent(key, k -> new int[2]);
            agg[0] += 1;
            agg[1] += ci.getUsageCount();
            for (var sa : ci.getStationAssignments()) {
                if (sa.getMessageText() == null) continue;
                msgPats.computeIfAbsent(sa.getMessageText(), k -> new java.util.HashSet<>()).add(key);
            }
        }
        for (var en : msgPats.entrySet()) msgGroups.put(en.getKey(), en.getValue().size());

        StringBuilder sb = new StringBuilder();
        sb.append("===== MY SOLVER 花型分布 (花型种类=").append(byPat.size())
          .append(", 指令块=").append(ins.size()).append(") =====\n");
        byPat.entrySet().stream()
                .sorted((a, b) -> b.getValue()[1] - a.getValue()[1])
                .forEach(e -> sb.append("  ").append(e.getKey()).append("  ->  ")
                        .append(e.getValue()[0]).append("块 | ").append(e.getValue()[1]).append("车\n"));
        sb.append("===== 每个编号跨几个花型 (>=3 的列出) =====\n");
        msgGroups.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append("  编号").append(e.getKey()).append(": ")
                        .append(e.getValue()).append(" 花型\n"));
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/my_patterns.txt"), sb.toString());
        } catch (Exception ex) { ex.printStackTrace(); }
        System.out.println(sb);
    }

    private List<SolverOrderItem> loadItems() throws IOException {
        List<SolverOrderItem> items = new ArrayList<>();
        InputStream in = getClass().getResourceAsStream("/sixian.csv");
        if (in == null) {
            throw new IllegalStateException("sixian.csv not found on test classpath");
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split(",", -1);
                SolverOrderItem item = new SolverOrderItem();
                item.setMessageText(p[0].trim());
                item.setWidth(Integer.parseInt(p[1].trim()));
                item.setDemand(Integer.parseInt(p[2].trim()));
                item.setLength(Integer.parseInt(p[3].trim()));
                item.setSurfaceTreatment(p[4].trim());
                item.setGroupKey(p[3].trim() + "m+" + p[4].trim());
                items.add(item);
            }
        }
        return items;
    }

    private SolverConfig buildConfig() {
        SolverConfig c = new SolverConfig();
        c.setMode("variable");
        c.setMinWidth(4300);
        c.setMaxWidth(4400);
        c.setStepSize(10);
        c.setTotalWidth(4600);
        c.setTotalOverCap(30);
        c.setMaxIterations(300);
        c.setTimeoutMs(120000L);
        c.setUseNewSolver(true);
        c.setNewSolverTopK(3);
        c.setNewSolverMaxPatterns(800);
        c.setNewSolverMaxDistinctWidths(5);
        c.setNewSolverStage4TimeLimit(30000L);
        c.setNewSolverSeqGroupAlpha(1.0);
        c.setNewSolverSeqGroupBeta(0.0);
        c.setNewSolverUseOptimizedAssignment(true);
        c.setNewSolverUnderPenalty(1e6);
        return c;
    }
}
