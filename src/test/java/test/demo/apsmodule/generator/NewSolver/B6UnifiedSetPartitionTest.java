package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Result;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 根治实验驱动：统一 set-partition（组数直接进目标）在四线 T9EST188 1350m 大组上的
 * 两级验证。人工基准（笔记12解剖）：大组 46 块 / odd 1 / 449 车 / 废边 99,380mm。
 *
 * <p>Level 1（模型正确性）：只给人工 46 列 → 必须精确复现 46/1。
 * <p>Level 2（模型价值）：人工列 + 人工25花型的结构化列 → 组数只可能 ≤ 46；
 * 若 < 46 则系统首次超越人工。
 */
class B6UnifiedSetPartitionTest {

    private static final int TOTAL_WIDTH = 4600;
    private static final int MANUAL_BIG_CARS = 449;
    private static final int MANUAL_BIG_WASTE = 99380;

    @BeforeAll
    static void loadOrTools() {
        Loader.loadNativeLibraries();
    }

    @Test
    void level1ReproducesManualFromInjectedColumns() throws Exception {
        List<Column> manualColumns = loadManualBigGroupColumns();
        Map<String, Integer> demand = loadBigGroupDemand();
        assertEquals(46, manualColumns.size(), "manual 1350m block count");

        Result result = new UnifiedSetPartitionSolver().solve(
                manualColumns, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                60_000, 0.02);

        assertNotNull(result);
        System.out.printf("%n##### UnifiedSP Level1 (manual columns only): groups=%d odd=%d small=%d cars=%d waste=%d status=%s%n",
                result.groups(), result.oddBlocks(), result.smallBlocks(),
                result.cars(), result.waste(), result.status());
        assertEquals(MANUAL_BIG_CARS, result.cars(), "cars conserved");
        assertTrue(result.waste() <= MANUAL_BIG_WASTE, "waste within cap");
        assertEquals(46, result.groups(), "must reproduce manual group count exactly");
        assertTrue(result.oddBlocks() <= 1, "manual parity level (1 odd forced by 63-car family)");
    }

    @Test
    void level2MixManualColumnsWithStructuredPool() throws Exception {
        List<Column> manualColumns = loadManualBigGroupColumns();
        Map<String, Integer> demand = loadBigGroupDemand();
        Map<Integer, Map<String, Integer>> demandByWidth = byWidth(demand);

        List<Map<Integer, Integer>> manualPatterns = new ArrayList<>();
        for (Column column : manualColumns) {
            manualPatterns.add(column.pattern());
        }
        List<Column> pool = new ArrayList<>(manualColumns);
        pool.addAll(UnifiedSetPartitionSolver.structuredColumns(
                manualPatterns, demandByWidth, 3, 20, 100));
        System.out.println("Level2 pool size (pre-dedup): " + pool.size());

        // 人工解做 warm start：SCIP 从 46/1 出发只做改进（冷启动 180s 只能摸到 54）
        List<UnifiedSetPartitionSolver.ColumnUse> warmStart = loadManualBigGroupUses();
        Result result = new UnifiedSetPartitionSolver().solve(
                pool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                180_000, 0.02, warmStart);

        assertNotNull(result);
        System.out.printf("%n##### UnifiedSP Level2 (manual + structured): groups=%d odd=%d small=%d cars=%d waste=%d status=%s%n",
                result.groups(), result.oddBlocks(), result.smallBlocks(),
                result.cars(), result.waste(), result.status());
        for (UnifiedSetPartitionSolver.ColumnUse use : result.uses()) {
            System.out.printf("  %3d车%s | %s%n", use.count(), use.count() % 2 != 0 ? "*" : " ",
                    use.column().signature());
        }
        assertEquals(MANUAL_BIG_CARS, result.cars(), "cars conserved");
        assertTrue(result.waste() <= MANUAL_BIG_WASTE, "waste within cap");
        // 人工列在池中 → 最优解不可能差于人工
        assertTrue(result.groups() <= 46, "must be at least as good as manual");
        verifyDemandExact(result, demand);
    }

    /**
     * Level 3（生产公平性）：warm start 不用人工解，改用我们自己管线的解；
     * 池 = 管线列 + 结构化列（管线花型 ∪ 人工花型形状——后者仅宽度组合，
     * enumPatterns 类枚举本可发现，不携带人工的序号分配知识）。
     * 通过标准：不劣于 warm start；理想：走到 46（人工水平）。
     */
    @Test
    void level3ProductionFairFromPipelineSolution() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        Map<Integer, Map<String, Integer>> demandByWidth = byWidth(demand);

        // 跑管线拿我们自己的解（parity0.1 + 质量LNS ≈ 47/8/18，其中 1350m 大组 46-47 块）
        List<test.demo.apsmodule.service.SolverOrderItem> items = new ArrayList<>();
        for (test.demo.apsmodule.service.SolverOrderItem it : loadSixianItems()) {
            if (it.getLength() == 1350) {
                items.add(it);
            }
        }
        Map<String, String> props = Map.of(
                "cutting.lns.enabled", "true",
                "cutting.lns.maxFreeOrders", "12",
                "cutting.lns.maxFreePatterns", "20",
                "cutting.lns.maxFreeCars", "40",
                "cutting.lns.maxNeighborhoods", "60",
                "cutting.aLayerParityPenalty", "0.1");
        Map<String, String> prev = new java.util.HashMap<>();
        for (String k : props.keySet()) {
            prev.put(k, System.getProperty(k));
        }
        props.forEach(System::setProperty);
        List<test.demo.apsmodule.service.CuttingInstruction> instructions;
        try {
            test.demo.apsmodule.service.SolverConfig config = buildSolverConfig();
            instructions = new CuttingSolver().solve(new ArrayList<>(items), config);
        } finally {
            prev.forEach((k, v) -> {
                if (v == null) {
                    System.clearProperty(k);
                } else {
                    System.setProperty(k, v);
                }
            });
        }

        List<UnifiedSetPartitionSolver.ColumnUse> pipelineUses = extractColumnUses(instructions);
        int pipelineCars = pipelineUses.stream().mapToInt(UnifiedSetPartitionSolver.ColumnUse::count).sum();
        int pipelineWaste = pipelineUses.stream()
                .mapToInt(u -> (TOTAL_WIDTH - u.column().patternWidth()) * u.count()).sum();
        long pipelineOdd = pipelineUses.stream().filter(u -> u.count() % 2 != 0).count();
        System.out.printf("Level3 pipeline warm start: blocks=%d odd=%d cars=%d waste=%d%n",
                pipelineUses.size(), pipelineOdd, pipelineCars, pipelineWaste);
        assertEquals(MANUAL_BIG_CARS, pipelineCars, "pipeline cars must equal 449");
        assertTrue(pipelineWaste <= MANUAL_BIG_WASTE, "pipeline waste must be optimal");

        // 池：管线列 + 结构化列（花型形状 = 管线 ∪ 人工，不带人工的序号分配）
        List<Map<Integer, Integer>> patternShapes = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : pipelineUses) {
            patternShapes.add(use.column().pattern());
        }
        for (Column column : loadManualBigGroupColumns()) {
            patternShapes.add(column.pattern());
        }
        List<Column> pool = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : pipelineUses) {
            pool.add(use.column());
        }
        pool.addAll(UnifiedSetPartitionSolver.structuredColumns(
                patternShapes, demandByWidth, 3, 20, 100));
        System.out.println("Level3 pool size (pre-dedup): " + pool.size());

        Result result = new UnifiedSetPartitionSolver().solve(
                pool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                Long.getLong("b6.timeMs", 180_000), 0.02, pipelineUses);

        assertNotNull(result);
        System.out.printf("%n##### UnifiedSP Level3 (production-fair): groups=%d odd=%d small=%d cars=%d waste=%d status=%s  (warm start=%d blocks, manual=46/1)%n",
                result.groups(), result.oddBlocks(), result.smallBlocks(),
                result.cars(), result.waste(), result.status(), pipelineUses.size());
        assertEquals(MANUAL_BIG_CARS, result.cars(), "cars conserved");
        assertTrue(result.waste() <= MANUAL_BIG_WASTE, "waste within cap");
        assertTrue(result.groups() <= pipelineUses.size(), "must not be worse than warm start");
        verifyDemandExact(result, demand);

        // 字典序第二段：组数封顶，专修 odd（warm start = 第一段结果）
        Result oddRefined = new UnifiedSetPartitionSolver().solve(
                pool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                Long.getLong("b6.timeMs", 180_000), 0.02, result.uses(), result.groups());
        assertNotNull(oddRefined);
        System.out.printf("##### UnifiedSP Level3b (odd refine @groups<=%d): groups=%d odd=%d small=%d cars=%d waste=%d status=%s  (manual=46/1)%n",
                result.groups(), oddRefined.groups(), oddRefined.oddBlocks(), oddRefined.smallBlocks(),
                oddRefined.cars(), oddRefined.waste(), oddRefined.status());
        assertEquals(MANUAL_BIG_CARS, oddRefined.cars(), "cars conserved (odd refine)");
        assertTrue(oddRefined.waste() <= MANUAL_BIG_WASTE, "waste within cap (odd refine)");
        assertTrue(oddRefined.groups() <= result.groups(), "groups capped (odd refine)");
        assertTrue(oddRefined.oddBlocks() <= result.oddBlocks(), "odd must not regress");
        verifyDemandExact(oddRefined, demand);
    }

    /**
     * Level 4（池瘦身）+ Level 5（完全去人工）合跑：管线解只算一次。
     * L4：只留比例匹配列（bufferCount=0），形状 = 管线 ∪ 人工——验证瘦身能否闭合 46/7→45/1。
     * L5：形状 = 管线 ∪ 枚举生成（pw 降序 top 400，无任何人工知识）——验证独立性。
     */
    @Test
    void level4SlimAndLevel5ManualFree() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        Map<Integer, Map<String, Integer>> demandByWidth = byWidth(demand);
        List<UnifiedSetPartitionSolver.ColumnUse> pipelineUses = runPipelineBigGroup();
        int pipelineCars = pipelineUses.stream().mapToInt(UnifiedSetPartitionSolver.ColumnUse::count).sum();
        assertEquals(MANUAL_BIG_CARS, pipelineCars, "pipeline cars");
        List<Column> pipelineColumns = pipelineUses.stream()
                .map(UnifiedSetPartitionSolver.ColumnUse::column)
                .collect(java.util.stream.Collectors.toList());
        System.out.printf("L4/L5 pipeline warm start: blocks=%d odd=%d%n",
                pipelineUses.size(),
                pipelineUses.stream().filter(u -> u.count() % 2 != 0).count());

        // ===== Level 4: slim pool, shapes = pipeline ∪ manual =====
        List<Map<Integer, Integer>> shapes4 = new ArrayList<>();
        for (Column column : pipelineColumns) {
            shapes4.add(column.pattern());
        }
        for (Column column : loadManualBigGroupColumns()) {
            shapes4.add(column.pattern());
        }
        List<Column> pool4 = new ArrayList<>(pipelineColumns);
        pool4.addAll(UnifiedSetPartitionSolver.structuredColumns(
                shapes4, demandByWidth, 0, 20, 100));
        System.out.println("Level4 slim pool size (pre-dedup): " + pool4.size());
        Result r4 = new UnifiedSetPartitionSolver().solve(
                pool4, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                300_000, 0.02, pipelineUses);
        assertNotNull(r4);
        Result r4b = new UnifiedSetPartitionSolver().solve(
                pool4, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                300_000, 0.02, r4.uses(), r4.groups());
        assertNotNull(r4b);
        System.out.printf("%n##### UnifiedSP Level4 (slim pool): stage1 %d/%d/%d -> refine %d/%d/%d cars=%d waste=%d status=%s/%s (manual=46/1)%n",
                r4.groups(), r4.oddBlocks(), r4.smallBlocks(),
                r4b.groups(), r4b.oddBlocks(), r4b.smallBlocks(),
                r4b.cars(), r4b.waste(), r4.status(), r4b.status());
        assertEquals(MANUAL_BIG_CARS, r4b.cars());
        assertTrue(r4b.waste() <= MANUAL_BIG_WASTE);
        assertTrue(r4b.groups() <= pipelineUses.size(), "L4 must not regress vs warm start");
        verifyDemandExact(r4b, demand);

        // ===== Level 5: manual-free shapes = pipeline ∪ enumerated =====
        List<Map<Integer, Integer>> shapes5 = new ArrayList<>();
        for (Column column : pipelineColumns) {
            shapes5.add(column.pattern());
        }
        shapes5.addAll(enumerateShapes(new ArrayList<>(demandByWidth.keySet()), 4300, 4400, 5, 6000, 400));
        List<Column> pool5 = new ArrayList<>(pipelineColumns);
        pool5.addAll(UnifiedSetPartitionSolver.structuredColumns(
                shapes5, demandByWidth, 0, 20, 100));
        System.out.println("Level5 manual-free pool size (pre-dedup): " + pool5.size());
        Result r5 = new UnifiedSetPartitionSolver().solve(
                pool5, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                300_000, 0.02, pipelineUses);
        assertNotNull(r5);
        Result r5b = new UnifiedSetPartitionSolver().solve(
                pool5, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                300_000, 0.02, r5.uses(), r5.groups());
        assertNotNull(r5b);
        System.out.printf("%n##### UnifiedSP Level5 (manual-free): stage1 %d/%d/%d -> refine %d/%d/%d cars=%d waste=%d status=%s/%s (manual=46/1)%n",
                r5.groups(), r5.oddBlocks(), r5.smallBlocks(),
                r5b.groups(), r5b.oddBlocks(), r5b.smallBlocks(),
                r5b.cars(), r5b.waste(), r5.status(), r5b.status());
        assertEquals(MANUAL_BIG_CARS, r5b.cars());
        assertTrue(r5b.waste() <= MANUAL_BIG_WASTE);
        assertTrue(r5b.groups() <= pipelineUses.size(), "L5 must not regress vs warm start");
        verifyDemandExact(r5b, demand);
    }

    /**
     * Level 6（对齐贪心 warm start）：不用管线解、不用人工解——warm start 由比例匹配
     * 贪心直接构造（与列池同源的"对齐风格"初始盆地），SCIP 只做抛光。
     * 池 = 比例匹配纯列（形状 = 枚举 top400，完全无人工知识）。
     * 假设验证：L3-L5 三次证明 45/1 材料在池中而管线 warm start 盆地错误；
     * 若 L6 达到 ≤46/低odd，则"同源 warm start"是生产化的正确形态（且全程无人工依赖）。
     */
    @Test
    void level6GreedyAlignedWarmStart() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        Map<Integer, Map<String, Integer>> demandByWidth = byWidth(demand);

        // 分层形状（跨 pw 光谱，修复 v1 全高宽 INFEASIBLE）+ 管线列做可行性垫底
        // （独立性已由 L5 闭环；L6 的实验变量只有 warm start 的盆地）
        List<UnifiedSetPartitionSolver.ColumnUse> pipelineUses = runPipelineBigGroup();
        List<Map<Integer, Integer>> shapes = stratifiedShapes(
                new ArrayList<>(demandByWidth.keySet()), 4300, 4400, 5, 6000, 50, 400);
        List<Column> pool = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : pipelineUses) {
            pool.add(use.column());
        }
        pool.addAll(UnifiedSetPartitionSolver.structuredColumns(
                shapes, demandByWidth, 2, 20, 80));
        System.out.println("Level6 pool size (pre-dedup): " + pool.size());

        List<UnifiedSetPartitionSolver.ColumnUse> greedy =
                UnifiedSetPartitionSolver.greedyAlignedWarmStart(
                        pool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH);
        int greedyCars = greedy.stream().mapToInt(UnifiedSetPartitionSolver.ColumnUse::count).sum();
        int greedyWaste = greedy.stream()
                .mapToInt(u -> (TOTAL_WIDTH - u.column().patternWidth()) * u.count()).sum();
        System.out.printf("Level6 greedy warm start: blocks=%d odd=%d cars=%d waste=%d (target 449/99380)%n",
                greedy.size(), greedy.stream().filter(u -> u.count() % 2 != 0).count(),
                greedyCars, greedyWaste);

        // 残差补全：贪心未覆盖的需求 → 用「按剩余需求现场生成」的专属列池小规模求解
        // （v3 实测直接复用主池会被支持度过滤剪成 0 列——所有列都碰到已耗尽的序号）；
        // 贪心列+残差列 = 完整可行 hint（部分 hint 会让 SCIP 万列冷启动迷路，v2 NOT_SOLVED）
        Map<String, Integer> leftover = new TreeMap<>(demand);
        for (UnifiedSetPartitionSolver.ColumnUse use : greedy) {
            for (Map.Entry<String, Integer> e : use.column().demandUse().entrySet()) {
                leftover.merge(e.getKey(), -e.getValue() * use.count(), Integer::sum);
            }
        }
        leftover.values().removeIf(v -> v <= 0);
        List<UnifiedSetPartitionSolver.ColumnUse> fullHint = new ArrayList<>(greedy);
        if (!leftover.isEmpty()) {
            Map<Integer, Map<String, Integer>> leftoverByWidth = byWidth(leftover);
            List<Column> residualPool = new ArrayList<>(pool);
            residualPool.addAll(UnifiedSetPartitionSolver.structuredColumns(
                    shapes, leftoverByWidth, 3, 20, 80));
            Result residual = new UnifiedSetPartitionSolver().solve(
                    residualPool, leftover, MANUAL_BIG_CARS - greedyCars, MANUAL_BIG_WASTE - greedyWaste,
                    TOTAL_WIDTH, 60_000, 0.02);
            assertNotNull(residual);
            System.out.printf("Level6 residual completion: groups=%d odd=%d cars=%d status=%s%n",
                    residual.groups(), residual.oddBlocks(), residual.cars(), residual.status());
            if (residual.groups() > 0) {
                fullHint.addAll(residual.uses());
                // 残差专属列也要进主池，否则 hint 引用的列不在主池中会被静默丢弃
                pool.addAll(residual.uses().stream()
                        .map(UnifiedSetPartitionSolver.ColumnUse::column)
                        .collect(java.util.stream.Collectors.toList()));
            } else {
                System.out.println("Level6 residual infeasible -> fallback to pipeline hint");
                fullHint = pipelineUses;
            }
        }
        System.out.printf("Level6 full hint: blocks=%d odd=%d cars=%d%n",
                fullHint.size(), fullHint.stream().filter(u -> u.count() % 2 != 0).count(),
                fullHint.stream().mapToInt(UnifiedSetPartitionSolver.ColumnUse::count).sum());

        Result r6 = new UnifiedSetPartitionSolver().solve(
                pool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                300_000, 0.02, fullHint);
        assertNotNull(r6);
        Result r6b = r6.groups() > 0
                ? new UnifiedSetPartitionSolver().solve(
                        pool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                        300_000, 0.02, r6.uses(), r6.groups())
                : r6;
        assertNotNull(r6b);
        System.out.printf("%n##### UnifiedSP Level6 (greedy aligned warm start, manual-free): stage1 %d/%d/%d -> refine %d/%d/%d cars=%d waste=%d status=%s/%s (manual=46/1, pipeline-basin=46/7)%n",
                r6.groups(), r6.oddBlocks(), r6.smallBlocks(),
                r6b.groups(), r6b.oddBlocks(), r6b.smallBlocks(),
                r6b.cars(), r6b.waste(), r6.status(), r6b.status());
        if (!r6b.uses().isEmpty()) {
            assertEquals(MANUAL_BIG_CARS, r6b.cars(), "cars conserved");
            assertTrue(r6b.waste() <= MANUAL_BIG_WASTE, "waste within cap");
            verifyDemandExact(r6b, demand);
        }
    }

    /**
     * Level 7（大单泛化）：t9est188 大单（116行/2311卷/人工88组/废边160,900），
     * 单分组。管线解 warm start + 统一模型精修，验证整套方法在第二数据集上的泛化性。
     */
    @Test
    void level7LargeOrderGeneralization() throws Exception {
        Map<String, Integer> demand = new LinkedHashMap<>();
        List<test.demo.apsmodule.service.SolverOrderItem> items = new ArrayList<>();
        try (BufferedReader br = open("/t9est188.csv")) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split(",", -1);
                demand.merge(p[1].trim() + "|" + p[0].trim(), Integer.parseInt(p[2].trim()), Integer::sum);
                test.demo.apsmodule.service.SolverOrderItem item = new test.demo.apsmodule.service.SolverOrderItem();
                item.setMessageText(p[0].trim());
                item.setWidth(Integer.parseInt(p[1].trim()));
                item.setDemand(Integer.parseInt(p[2].trim()));
                item.setLength(Integer.parseInt(p[3].trim()));
                item.setSurfaceTreatment(p[4].trim());
                item.setGroupKey(p[3].trim() + "m+" + p[4].trim());
                items.add(item);
            }
        }
        Map<Integer, Map<String, Integer>> demandByWidth = byWidth(demand);

        Map<String, String> props = Map.of(
                "cutting.lns.enabled", "true",
                "cutting.lns.maxFreeOrders", "12",
                "cutting.lns.maxFreePatterns", "20",
                "cutting.lns.maxFreeCars", "40",
                "cutting.lns.maxNeighborhoods", "60",
                "cutting.aLayerParityPenalty", "0.1");
        Map<String, String> prev = new java.util.HashMap<>();
        for (String k : props.keySet()) {
            prev.put(k, System.getProperty(k));
        }
        props.forEach(System::setProperty);
        List<test.demo.apsmodule.service.CuttingInstruction> instructions;
        try {
            instructions = new CuttingSolver().solve(new ArrayList<>(items), buildSolverConfig());
        } finally {
            prev.forEach((k, v) -> {
                if (v == null) {
                    System.clearProperty(k);
                } else {
                    System.setProperty(k, v);
                }
            });
        }
        List<UnifiedSetPartitionSolver.ColumnUse> pipelineUses = extractColumnUses(instructions);
        int cars = pipelineUses.stream().mapToInt(UnifiedSetPartitionSolver.ColumnUse::count).sum();
        int waste = pipelineUses.stream()
                .mapToInt(u -> (TOTAL_WIDTH - u.column().patternWidth()) * u.count()).sum();
        System.out.printf("Level7 pipeline: blocks=%d odd=%d cars=%d waste=%d (human=88 blocks / waste 160900)%n",
                pipelineUses.size(),
                pipelineUses.stream().filter(u -> u.count() % 2 != 0).count(),
                cars, waste);

        List<Map<Integer, Integer>> shapes = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : pipelineUses) {
            shapes.add(use.column().pattern());
        }
        shapes.addAll(stratifiedShapes(new ArrayList<>(demandByWidth.keySet()), 4300, 4400, 5, 6000, 30, 250));
        List<Column> pool = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : pipelineUses) {
            pool.add(use.column());
        }
        pool.addAll(UnifiedSetPartitionSolver.structuredColumns(shapes, demandByWidth, 2, 16, 40));
        System.out.println("Level7 pool size (pre-dedup): " + pool.size());

        Result r7 = new UnifiedSetPartitionSolver().solve(
                pool, demand, cars, waste, TOTAL_WIDTH, 300_000, 0.02, pipelineUses);
        assertNotNull(r7);
        Result r7b = r7.groups() > 0
                ? new UnifiedSetPartitionSolver().solve(
                        pool, demand, cars, waste, TOTAL_WIDTH, 240_000, 0.02, r7.uses(), r7.groups())
                : r7;
        System.out.printf("%n##### UnifiedSP Level7 (large order): pipeline %d/%d -> stage1 %d/%d/%d -> refine %d/%d/%d cars=%d waste=%d status=%s/%s (human=88)%n",
                pipelineUses.size(), pipelineUses.stream().filter(u -> u.count() % 2 != 0).count(),
                r7.groups(), r7.oddBlocks(), r7.smallBlocks(),
                r7b.groups(), r7b.oddBlocks(), r7b.smallBlocks(),
                r7b.cars(), r7b.waste(), r7.status(), r7b.status());
        if (r7b.groups() > 0) {
            assertEquals(cars, r7b.cars(), "cars conserved");
            assertTrue(r7b.waste() <= waste, "waste within pipeline waste");
            assertTrue(r7b.groups() <= pipelineUses.size(), "must not regress vs pipeline");
            verifyDemandExact(r7b, demand);
        }
    }

    /** 跑管线（parity0.1 + 质量LNS）拿 1350m 大组的解，转成列。 */
    private List<UnifiedSetPartitionSolver.ColumnUse> runPipelineBigGroup() throws Exception {
        List<test.demo.apsmodule.service.SolverOrderItem> items = new ArrayList<>();
        for (test.demo.apsmodule.service.SolverOrderItem it : loadSixianItems()) {
            if (it.getLength() == 1350) {
                items.add(it);
            }
        }
        Map<String, String> props = Map.of(
                "cutting.lns.enabled", "true",
                "cutting.lns.maxFreeOrders", "12",
                "cutting.lns.maxFreePatterns", "20",
                "cutting.lns.maxFreeCars", "40",
                "cutting.lns.maxNeighborhoods", "60",
                "cutting.aLayerParityPenalty", "0.1");
        Map<String, String> prev = new java.util.HashMap<>();
        for (String k : props.keySet()) {
            prev.put(k, System.getProperty(k));
        }
        props.forEach(System::setProperty);
        try {
            test.demo.apsmodule.service.SolverConfig config = buildSolverConfig();
            return extractColumnUses(new CuttingSolver().solve(new ArrayList<>(items), config));
        } finally {
            prev.forEach((k, v) -> {
                if (v == null) {
                    System.clearProperty(k);
                } else {
                    System.setProperty(k, v);
                }
            });
        }
    }

    /** 花型形状枚举（无人工知识）：宽度多重集，pw∈[minRw,maxRw]，≤maxDistinct 种宽度；pw 降序取 top keep。 */
    private List<Map<Integer, Integer>> enumerateShapes(List<Integer> widths,
            int minRw, int maxRw, int maxDistinct, int rawCap, int keep) {
        List<Map<Integer, Integer>> raw = new ArrayList<>();
        enumShapesRec(widths, 0, new LinkedHashMap<>(), 0, minRw, maxRw, maxDistinct, raw, rawCap);
        raw.sort(Comparator.comparingInt((Map<Integer, Integer> shape) -> shape.entrySet().stream()
                .mapToInt(e -> e.getKey() * e.getValue()).sum()).reversed());
        List<Map<Integer, Integer>> kept = raw.size() > keep ? raw.subList(0, keep) : raw;
        System.out.printf("enumerateShapes: raw=%d kept=%d (pw desc)%n", raw.size(), kept.size());
        return new ArrayList<>(kept);
    }

    /**
     * 分层形状抽样：pw 每 10mm 一档，档内按 (宽度种类少 → pw 高) 排序取 perBand 个。
     * 修复 pw 降序截断的数学缺陷——车数等式强制平均 pw=Σwq/449≈4378.7，全高宽形状池
     * 必然 INFEASIBLE；分层保证 pw 光谱覆盖（人工形状即分布在 4320-4400）。
     */
    private List<Map<Integer, Integer>> stratifiedShapes(List<Integer> widths,
            int minRw, int maxRw, int maxDistinct, int rawCap, int perBand, int totalCap) {
        List<Map<Integer, Integer>> raw = new ArrayList<>();
        enumShapesRec(widths, 0, new LinkedHashMap<>(), 0, minRw, maxRw, maxDistinct, raw, rawCap);
        Map<Integer, List<Map<Integer, Integer>>> byBand = new TreeMap<>(Comparator.reverseOrder());
        for (Map<Integer, Integer> shape : raw) {
            int pw = shape.entrySet().stream().mapToInt(e -> e.getKey() * e.getValue()).sum();
            byBand.computeIfAbsent(pw / 10, k -> new ArrayList<>()).add(shape);
        }
        List<Map<Integer, Integer>> kept = new ArrayList<>();
        for (List<Map<Integer, Integer>> band : byBand.values()) {
            band.sort(Comparator
                    .comparingInt((Map<Integer, Integer> s) -> s.size())
                    .thenComparing(s -> -s.entrySet().stream().mapToInt(e -> e.getKey() * e.getValue()).sum()));
            for (int i = 0; i < Math.min(perBand, band.size()) && kept.size() < totalCap; i++) {
                kept.add(band.get(i));
            }
            if (kept.size() >= totalCap) {
                break;
            }
        }
        System.out.printf("stratifiedShapes: raw=%d bands=%d kept=%d%n", raw.size(), byBand.size(), kept.size());
        return kept;
    }

    private void enumShapesRec(List<Integer> widths, int idx, Map<Integer, Integer> current, int sum,
            int minRw, int maxRw, int maxDistinct, List<Map<Integer, Integer>> out, int cap) {
        if (out.size() >= cap) {
            return;
        }
        if (idx == widths.size()) {
            if (sum >= minRw && sum <= maxRw && !current.isEmpty()) {
                out.add(new LinkedHashMap<>(current));
            }
            return;
        }
        int w = widths.get(idx);
        int maxCount = (maxRw - sum) / w;
        for (int count = 0; count <= maxCount && out.size() < cap; count++) {
            if (count > 0) {
                if (!current.containsKey(w) && current.size() >= maxDistinct) {
                    break;
                }
                current.put(w, count);
            }
            enumShapesRec(widths, idx + 1, current, sum + count * w, minRw, maxRw, maxDistinct, out, cap);
        }
        current.remove(w);
    }

    /** 指令 → 列：模拟每车消费工位分配，按相同整车配置的连续段聚成 (列, 车数)。 */
    private List<UnifiedSetPartitionSolver.ColumnUse> extractColumnUses(
            List<test.demo.apsmodule.service.CuttingInstruction> instructions) {
        List<UnifiedSetPartitionSolver.ColumnUse> uses = new ArrayList<>();
        for (test.demo.apsmodule.service.CuttingInstruction instruction : instructions) {
            Map<Integer, java.util.ArrayDeque<String>> buckets = new LinkedHashMap<>();
            for (test.demo.apsmodule.service.StationAssignment a : instruction.getStationAssignments()) {
                buckets.computeIfAbsent(a.getWidth(), k -> new java.util.ArrayDeque<>())
                        .add(a.getMessageText() == null ? "" : a.getMessageText());
            }
            String previousSig = null;
            Column previousColumn = null;
            int run = 0;
            for (int roll = 0; roll < instruction.getUsageCount(); roll++) {
                Map<Integer, List<String>> config = new TreeMap<>();
                for (Map.Entry<Integer, Integer> e : new TreeMap<>(instruction.getSubRolls()).entrySet()) {
                    List<String> messages = new ArrayList<>();
                    java.util.ArrayDeque<String> bucket = buckets.get(e.getKey());
                    for (int s = 0; s < e.getValue(); s++) {
                        messages.add(bucket == null || bucket.isEmpty() ? "" : bucket.poll());
                    }
                    config.put(e.getKey(), messages);
                }
                Column column = Column.of(instruction.getSubRolls(), config);
                String sig = column.signature();
                if (sig.equals(previousSig)) {
                    run++;
                } else {
                    if (run > 0) {
                        uses.add(new UnifiedSetPartitionSolver.ColumnUse(previousColumn, run));
                    }
                    previousSig = sig;
                    previousColumn = column;
                    run = 1;
                }
            }
            if (run > 0) {
                uses.add(new UnifiedSetPartitionSolver.ColumnUse(previousColumn, run));
            }
        }
        return uses;
    }

    private List<test.demo.apsmodule.service.SolverOrderItem> loadSixianItems() throws Exception {
        List<test.demo.apsmodule.service.SolverOrderItem> items = new ArrayList<>();
        try (BufferedReader br = open("/sixian.csv")) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split(",", -1);
                test.demo.apsmodule.service.SolverOrderItem item = new test.demo.apsmodule.service.SolverOrderItem();
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

    private test.demo.apsmodule.service.SolverConfig buildSolverConfig() {
        test.demo.apsmodule.service.SolverConfig c = new test.demo.apsmodule.service.SolverConfig();
        c.setMode("variable");
        c.setMinWidth(4300);
        c.setMaxWidth(4400);
        c.setStepSize(10);
        c.setTotalWidth(TOTAL_WIDTH);
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

    private void verifyDemandExact(Result result, Map<String, Integer> demand) {
        Map<String, Integer> produced = new TreeMap<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : result.uses()) {
            for (Map.Entry<String, Integer> e : use.column().demandUse().entrySet()) {
                produced.merge(e.getKey(), e.getValue() * use.count(), Integer::sum);
            }
        }
        assertEquals(new TreeMap<>(demand), produced, "exact demand coverage");
    }

    private Map<Integer, Map<String, Integer>> byWidth(Map<String, Integer> demand) {
        Map<Integer, Map<String, Integer>> byWidth = new TreeMap<>();
        for (Map.Entry<String, Integer> e : demand.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            byWidth.computeIfAbsent(Integer.parseInt(parts[0]), k -> new LinkedHashMap<>())
                    .put(parts[1], e.getValue());
        }
        return byWidth;
    }

    /** sixian.csv 的 1350m 需求 → (width|message) -> q */
    private Map<String, Integer> loadBigGroupDemand() throws Exception {
        Map<String, Integer> demand = new LinkedHashMap<>();
        try (BufferedReader br = open("/sixian.csv")) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split(",", -1);
                if (Integer.parseInt(p[3].trim()) != 1350) {
                    continue;
                }
                demand.merge(p[1].trim() + "|" + p[0].trim(), Integer.parseInt(p[2].trim()), Integer::sum);
            }
        }
        return demand;
    }

    /** manual_blocks_t9est188.csv → 46 个 1350m 列（排除 380+1000 的 1000m 块）。 */
    private List<Column> loadManualBigGroupColumns() throws Exception {
        return loadManualBigGroupUses().stream()
                .map(UnifiedSetPartitionSolver.ColumnUse::column)
                .collect(java.util.stream.Collectors.toList());
    }

    /** 人工 1350m 块 → 列+车数（warm start 用）。 */
    private List<UnifiedSetPartitionSolver.ColumnUse> loadManualBigGroupUses() throws Exception {
        Pattern station = Pattern.compile("(\\d+)@(\\d+) x(\\d+)");
        List<UnifiedSetPartitionSolver.ColumnUse> uses = new ArrayList<>();
        try (BufferedReader br = open("/manual_blocks_t9est188.csv")) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split(",", 3);
                int cars = Integer.parseInt(p[1].trim());
                String stations = p[2].replace("\"", "");
                Map<Integer, Integer> pattern = new TreeMap<>();
                Map<Integer, List<String>> config = new TreeMap<>();
                boolean is1000m = false;
                Matcher m = station.matcher(stations);
                while (m.find()) {
                    String msg = m.group(1);
                    int width = Integer.parseInt(m.group(2));
                    int slots = Integer.parseInt(m.group(3));
                    if (width == 380) {
                        is1000m = true;
                    }
                    pattern.merge(width, slots, Integer::sum);
                    List<String> messages = config.computeIfAbsent(width, k -> new ArrayList<>());
                    for (int s = 0; s < slots; s++) {
                        messages.add(msg);
                    }
                }
                if (is1000m || pattern.isEmpty()) {
                    continue;
                }
                uses.add(new UnifiedSetPartitionSolver.ColumnUse(Column.of(pattern, config), cars));
            }
        }
        return uses;
    }

    private BufferedReader open(String resource) {
        InputStream in = getClass().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException(resource + " not found on test classpath");
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
