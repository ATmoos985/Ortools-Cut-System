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

    /**
     * 诊断:把人工的 1350m 花型 + 人工车数(=均衡分布,固定不再重选)直接喂我的装配,
     * 看落几组。隔离"花型分布"效应(规避注入实验里 A 层重选车数的漏洞)。
     * 我的花型 1350m pre-LNS=54。-Dcutting.lns.enabled=true 可叠加 LNS。
     */
    @Test
    void runHumanFixedDistribution() throws Exception {
        com.google.ortools.Loader.loadNativeLibraries();
        SolverConfig config = buildConfig();
        test.demo.apsmodule.generator.NewSolver.config.SolverParameters params =
                test.demo.apsmodule.generator.NewSolver.config.SolverParameters.createDefault();
        params.mergeFrom(config);
        params.sanitize();

        List<SolverOrderItem> items1350 = new ArrayList<>();
        for (SolverOrderItem it : loadItems()) {
            if (it.getLength() == 1350) items1350.add(it);
        }
        java.util.Map<Integer, Integer> demands = new java.util.TreeMap<>();
        for (SolverOrderItem it : items1350) demands.merge(it.getWidth(), it.getDemand(), Integer::sum);

        java.util.Map<test.demo.apsmodule.generator.NewSolver.model.PatternCandidate, Integer> solution =
                new java.util.LinkedHashMap<>();
        var carsPat = java.util.regex.Pattern.compile("(\\d+)车");
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("target/human_patterns.txt"))) {
            int lb = line.indexOf('['), rb = line.indexOf(']');
            if (lb < 0 || rb < 0 || !line.contains("车")) continue;
            java.util.Map<Integer, Integer> pat = new java.util.LinkedHashMap<>();
            int sum = 0;
            boolean is1000m = false;
            for (String p : line.substring(lb + 1, rb).split(",")) {
                int w = Integer.parseInt(p.trim());
                if (w == 380) is1000m = true;
                pat.merge(w, 1, Integer::sum);
                sum += w;
            }
            if (is1000m) continue;
            var m = carsPat.matcher(line);
            if (!m.find()) continue;
            int cars = Integer.parseInt(m.group(1));
            solution.put(new test.demo.apsmodule.generator.NewSolver.model.PatternCandidate(pat, sum), cars);
        }

        String groupKey = items1350.get(0).getGroupKey();
        test.demo.apsmodule.generator.NewSolver.output.InstructionConverter converter =
                new test.demo.apsmodule.generator.NewSolver.output.InstructionConverter(params);
        var conv = converter.convertWithDetails(solution, groupKey, items1350, demands);
        SequenceGroupPostProcessor.GroupStats stats =
                SequenceGroupPostProcessor.computeGroupStats(conv.instructions());
        // 防伪解硬校验:最终指令的逐(宽度|消息)产量必须 == 需求,车数/废边守恒
        java.util.Map<String, Integer> producedDemand = new java.util.TreeMap<>();
        int prodCars = 0, prodWaste = 0;
        for (CuttingInstruction ci : conv.instructions()) {
            prodCars += ci.getUsageCount();
            prodWaste += ci.getWaste() * ci.getUsageCount();
            for (var sa : ci.getStationAssignments()) {
                producedDemand.merge(sa.getWidth() + "|" + sa.getMessageText(), 1, Integer::sum);
            }
        }
        // 需求(按 宽度|消息)= 注入解的产量(=订单需求,over=0)
        java.util.Map<String, Integer> wantDemand = new java.util.TreeMap<>();
        int wantCars = 0, wantWaste = 0;
        for (var e : solution.entrySet()) {
            wantCars += e.getValue();
            wantWaste += (params.getTotalWidth() - e.getKey().getPatternWidth()) * e.getValue();
        }
        for (SolverOrderItem it : items1350) wantDemand.merge(it.getWidth() + "|" + it.getMessageText(), it.getDemand(), Integer::sum);
        boolean demandOk = producedDemand.equals(wantDemand);
        boolean carsOk = prodCars == wantCars;
        boolean wasteOk = prodWaste == wantWaste;

        System.out.println("\n##### 人工固定分布注入(1350m) #####");
        System.out.println("花型=" + solution.size() + " 车=" + wantCars
                + " groups=" + stats.groups() + " winner=" + conv.selectedName()
                + "   (我的花型1350m pre-LNS=54, 人工最终=46)");
        System.out.println("VALID demandOk=" + demandOk + " carsOk=" + carsOk + "(" + prodCars + "/" + wantCars
                + ") wasteOk=" + wasteOk + "(" + prodWaste + "/" + wantWaste + ")");
        System.out.println("#########################\n");
        org.junit.jupiter.api.Assertions.assertTrue(demandOk && carsOk && wasteOk, "伪解! demand/cars/waste 不守恒");
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
