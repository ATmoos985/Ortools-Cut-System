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
    void level1bStrictLexicographicReproducesManualBusinessFixture() throws Exception {
        List<Column> manualColumns = loadManualBigGroupColumns();
        Map<String, Integer> demand = loadBigGroupDemand();

        UnifiedSetPartitionSolver.LexicographicResult result =
                new UnifiedSetPartitionSolver().solveLexicographic(
                        manualColumns,
                        demand,
                        MANUAL_BIG_CARS,
                        MANUAL_BIG_WASTE,
                        TOTAL_WIDTH,
                        30_000L,
                        loadManualBigGroupUses());

        assertNotNull(result);
        assertTrue(result.provenOptimal(), "all three lexicographic phases must be optimal");
        assertNotNull(result.result());
        assertEquals(3, result.phases().size());
        assertEquals(46, result.result().groups());
        assertEquals(1, result.result().oddBlocks());
        assertEquals(MANUAL_BIG_CARS, result.result().cars());
        assertEquals(MANUAL_BIG_WASTE, result.result().waste());
        assertTrue(result.phases().stream().allMatch(phase -> phase.relativeGap() < 1e-9));
        verifyDemandExact(result.result(), demand);
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

    /**
     * Level 8（池包含性诊断）：裁决"45/1 没被生产公平模式选出来"的病因是
     * 假设A（池缺列：形状/截断把人工风格配对切掉了）还是假设B（列都在，SCIP 搜索迷路）。
     * 方法：重现 L2 的 45/1 解，逐列检查三层包含性——
     *   ①形状 ∈ 生产公平分层枚举形状集？
     *   ②配置在生产截断参数（buffer=2, maxOptions=20）下可生成？
     *   ③放开截断（buffer=8, maxOptions=5000）后可生成？
     * ①缺→分层抽样要改；②缺③在→截断丢列（剪枝排序方向对）；全在→纯搜索问题（定价不可绕）。
     */
    @Test
    void level8PoolContainmentDiagnostic() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        Map<Integer, Map<String, Integer>> demandByWidth = byWidth(demand);

        // 1. 重现 45/1（L2 配置：人工列 + 人工形状结构化列，人工解 warm start）
        List<Column> manualColumns = loadManualBigGroupColumns();
        List<Map<Integer, Integer>> manualPatterns = new ArrayList<>();
        for (Column column : manualColumns) {
            manualPatterns.add(column.pattern());
        }
        List<Column> pool2 = new ArrayList<>(manualColumns);
        pool2.addAll(UnifiedSetPartitionSolver.structuredColumns(
                manualPatterns, demandByWidth, 3, 20, 100));
        Result r2 = new UnifiedSetPartitionSolver().solve(
                pool2, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                180_000, 0.02, loadManualBigGroupUses());
        assertNotNull(r2);
        System.out.printf("Level8 reference solution: groups=%d odd=%d (expect 45/1)%n",
                r2.groups(), r2.oddBlocks());

        // 2. 生产公平形状集（分层枚举，零人工知识）
        List<Map<Integer, Integer>> prodShapes = stratifiedShapes(
                new ArrayList<>(demandByWidth.keySet()), 4300, 4400, 5, 6000, 50, 400);
        java.util.Set<String> prodShapeSigs = new java.util.HashSet<>();
        for (Map<Integer, Integer> shape : prodShapes) {
            prodShapeSigs.add(new TreeMap<>(shape).toString());
        }

        // 3. 逐列三层包含性
        int shapeMissing = 0;
        int cappedMissing = 0;
        int wideMissing = 0;
        List<String> examples = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : r2.uses()) {
            Column column = use.column();
            boolean shapeOk = prodShapeSigs.contains(new TreeMap<>(column.pattern()).toString());
            boolean cappedOk = configGeneratable(column, demandByWidth, 2, 20);
            boolean wideOk = configGeneratable(column, demandByWidth, 8, 5000);
            if (!shapeOk) {
                shapeMissing++;
            }
            if (!cappedOk) {
                cappedMissing++;
            }
            if (!wideOk) {
                wideMissing++;
            }
            if ((!shapeOk || !cappedOk) && examples.size() < 8) {
                examples.add(String.format("%d车 shape=%s capped=%s wide=%s | %s",
                        use.count(), shapeOk, cappedOk, wideOk, column.signature()));
            }
        }
        System.out.printf("%n##### Level8 containment: refCols=%d | shapeMissing=%d cappedMissing=%d wideMissing=%d%n",
                r2.uses().size(), shapeMissing, cappedMissing, wideMissing);
        System.out.println("verdict: shapeMissing>0 → 形状抽样缺口; cappedMissing>0&wideMissing==0 → 截断丢列(剪枝方向对); 全0 → 纯搜索问题(定价不可绕)");
        for (String example : examples) {
            System.out.println("  MISSING: " + example);
        }
    }

    /**
     * Level 9（修复后复测）：L8 三刀修复后的生产公平池——需求质量加权分层形状（800）+
     * 比例匹配配置无 cap + 管线列垫底。先复查包含性（参考解列应基本进池），
     * 再跑生产公平求解（管线 warm start），对照基线 46/7。
     */
    @Test
    void level9FixedPoolProductionFair() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        Map<Integer, Map<String, Integer>> demandByWidth = byWidth(demand);

        // 参考解（L2 重现，用于包含性复查）
        List<Column> manualColumns = loadManualBigGroupColumns();
        List<Map<Integer, Integer>> manualPatterns = new ArrayList<>();
        for (Column column : manualColumns) {
            manualPatterns.add(column.pattern());
        }
        List<Column> pool2 = new ArrayList<>(manualColumns);
        pool2.addAll(UnifiedSetPartitionSolver.structuredColumns(
                manualPatterns, demandByWidth, 3, 20, 100));
        Result reference = new UnifiedSetPartitionSolver().solve(
                pool2, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                180_000, 0.02, loadManualBigGroupUses());
        assertNotNull(reference);
        System.out.printf("Level9 reference: groups=%d odd=%d%n", reference.groups(), reference.oddBlocks());

        // 修复后的生产公平池
        List<UnifiedSetPartitionSolver.ColumnUse> pipelineUses = runPipelineBigGroup();
        List<Map<Integer, Integer>> shapes = stratifiedShapes(
                new ArrayList<>(demandByWidth.keySet()), 4300, 4400, 5, 6000, 100, 800, demandByWidth);
        List<Column> pool = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : pipelineUses) {
            pool.add(use.column());
        }
        // 每形状少而精（11 配置/形状，比例匹配排前）：v1 的全局 9000 截尾让 800 形状
        // 只剩前 ~110 个有列，形状覆盖塌方（containment stillMissing=30）
        pool.addAll(UnifiedSetPartitionSolver.structuredColumns(shapes, demandByWidth, 2, 20, 11));
        System.out.println("Level9 pool size (pre-dedup): " + pool.size());

        // 包含性复查（对照 L8 的 27/7/2 缺口）
        java.util.Set<String> poolSigs = new java.util.HashSet<>();
        for (Column column : pool) {
            poolSigs.add(column.signature());
        }
        int stillMissing = 0;
        for (UnifiedSetPartitionSolver.ColumnUse use : reference.uses()) {
            if (!poolSigs.contains(use.column().signature())) {
                stillMissing++;
            }
        }
        System.out.printf("Level9 containment after fix: refCols=%d stillMissing=%d (L8 baseline: 27 shape + 7 capped)%n",
                reference.uses().size(), stillMissing);

        // 生产公平求解（基线 46/7）
        Result r9 = new UnifiedSetPartitionSolver().solve(
                pool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                300_000, 0.02, pipelineUses);
        assertNotNull(r9);
        Result r9b = r9.groups() > 0
                ? new UnifiedSetPartitionSolver().solve(
                        pool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                        300_000, 0.02, r9.uses(), r9.groups())
                : r9;
        System.out.printf("%n##### UnifiedSP Level9 (fixed pool, production-fair): stage1 %d/%d/%d -> refine %d/%d/%d cars=%d waste=%d status=%s/%s (baseline 46/7, manual 46/1, ref %d/%d)%n",
                r9.groups(), r9.oddBlocks(), r9.smallBlocks(),
                r9b.groups(), r9b.oddBlocks(), r9b.smallBlocks(),
                r9b.cars(), r9b.waste(), r9.status(), r9b.status(),
                reference.groups(), reference.oddBlocks());
        if (r9b.groups() > 0) {
            assertEquals(MANUAL_BIG_CARS, r9b.cars(), "cars conserved");
            assertTrue(r9b.waste() <= MANUAL_BIG_WASTE, "waste within cap");
            verifyDemandExact(r9b, demand);
        }
    }

    /**
     * Level 10（定价第一checkpoint）：对偶价能否指到静态生成漏掉的列。
     * 受限主问题（生产公平池）LP 松弛取对偶价，对参考解中"不在池里"的列逐根算
     * reduced cost。验证目标：≥70% 缺失列 RC<0 ⇒ 定价恰好补上静态生成的洞，
     * 绿灯建全量定价循环；否则主问题建模需先修。附带产出：LP 目标值 = 组数数学下界。
     */
    @Test
    void level10PricingDualCheck() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        Map<Integer, Map<String, Integer>> demandByWidth = byWidth(demand);

        // 参考解（L2 重现）
        List<Column> manualColumns = loadManualBigGroupColumns();
        List<Map<Integer, Integer>> manualPatterns = new ArrayList<>();
        for (Column column : manualColumns) {
            manualPatterns.add(column.pattern());
        }
        List<Column> pool2 = new ArrayList<>(manualColumns);
        pool2.addAll(UnifiedSetPartitionSolver.structuredColumns(
                manualPatterns, demandByWidth, 3, 20, 100));
        Result reference = new UnifiedSetPartitionSolver().solve(
                pool2, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                180_000, 0.02, loadManualBigGroupUses());
        assertNotNull(reference);
        System.out.printf("Level10 reference: groups=%d odd=%d%n", reference.groups(), reference.oddBlocks());

        // 生产公平池（L9v1 参数：形状需求加权 800 + 比例无cap + 每形状80）
        List<UnifiedSetPartitionSolver.ColumnUse> pipelineUses = runPipelineBigGroup();
        List<Map<Integer, Integer>> shapes = stratifiedShapes(
                new ArrayList<>(demandByWidth.keySet()), 4300, 4400, 5, 6000, 100, 800, demandByWidth);
        List<Column> pool = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : pipelineUses) {
            pool.add(use.column());
        }
        pool.addAll(UnifiedSetPartitionSolver.structuredColumns(shapes, demandByWidth, 2, 20, 80));
        java.util.Set<String> poolSigs = new java.util.HashSet<>();
        for (Column column : pool) {
            poolSigs.add(column.signature());
        }

        // LP 松弛 + 对偶价
        UnifiedSetPartitionSolver.LpDuals duals = new UnifiedSetPartitionSolver()
                .solveLpRelaxation(pool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH);
        assertNotNull(duals);
        System.out.printf("Level10 LP bound: objective=%.3f status=%s (参考解=%d组)%n",
                duals.objective(), duals.status(), reference.groups());

        // 缺失列 RC 检查
        int missing = 0;
        int negativeRc = 0;
        List<String> rows = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : reference.uses()) {
            if (poolSigs.contains(use.column().signature())) {
                continue;
            }
            missing++;
            double rc = duals.reducedCost(use.column(), demand, TOTAL_WIDTH);
            if (rc < -1e-9) {
                negativeRc++;
            }
            if (rows.size() < 12) {
                rows.add(String.format("  RC=%+.4f %s | %d车 %s",
                        rc, rc < -1e-9 ? "PULL" : "skip", use.count(), use.column().signature()));
            }
        }
        System.out.printf("%n##### Level10 pricing dual check: missing=%d negativeRC=%d (%.0f%%) — 目标≥70%%%n",
                missing, negativeRc, missing == 0 ? 100.0 : 100.0 * negativeRc / missing);
        for (String row : rows) {
            System.out.println(row);
        }
    }

    /**
     * Level 11（定长列重构checkpoint）：L10 琥珀灯的修复验证。主问题重构为定长列
     * (config, c)——c 固化进列，y 的每一单位就是一个组。c 档位 = 精确耗尽值 + 半档 +
     * 偶邻；support 碎片档被排除（它正是 LP 下界 26.9 摊薄病的来源）。三个验证目标：
     *   ①自检：含 support 档的展开 LP 下界应 ≈26.9（与旧连续松弛数学等价）；
     *   ②收紧：严格档位下 LP 下界显著抬升（越接近 45-46，diving 越有依据）；
     *   ③定价：缺失参考定长列（sig#count 粒度）RC<0 命中率 ≥70% ⇒ 绿灯建定价循环。
     */
    @Test
    void level11FixedLengthLpCheckpoint() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        Map<Integer, Map<String, Integer>> demandByWidth = byWidth(demand);

        // 参考解（L2 重现）
        List<Column> manualColumns = loadManualBigGroupColumns();
        List<Map<Integer, Integer>> manualPatterns = new ArrayList<>();
        for (Column column : manualColumns) {
            manualPatterns.add(column.pattern());
        }
        List<Column> pool2 = new ArrayList<>(manualColumns);
        pool2.addAll(UnifiedSetPartitionSolver.structuredColumns(
                manualPatterns, demandByWidth, 3, 20, 100));
        Result reference = new UnifiedSetPartitionSolver().solve(
                pool2, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH,
                180_000, 0.02, loadManualBigGroupUses());
        assertNotNull(reference);
        System.out.printf("Level11 reference: groups=%d odd=%d%n", reference.groups(), reference.oddBlocks());

        // 生产公平池（L10 同参）
        List<UnifiedSetPartitionSolver.ColumnUse> pipelineUses = runPipelineBigGroup();
        List<Map<Integer, Integer>> shapes = stratifiedShapes(
                new ArrayList<>(demandByWidth.keySet()), 4300, 4400, 5, 6000, 100, 800, demandByWidth);
        List<Column> pool = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : pipelineUses) {
            pool.add(use.column());
        }
        pool.addAll(UnifiedSetPartitionSolver.structuredColumns(shapes, demandByWidth, 2, 20, 80));

        // 定长展开：严格档位（真收紧）；管线 warm-start 定长列兜底整数可行性
        List<UnifiedSetPartitionSolver.FixedColumn> strictPool =
                UnifiedSetPartitionSolver.expandFixedColumns(pool, demand, MANUAL_BIG_CARS, false);
        // 自检变体：含 support 档 → 应退化回旧下界 26.9
        List<UnifiedSetPartitionSolver.FixedColumn> selfCheckPool =
                UnifiedSetPartitionSolver.expandFixedColumns(pool, demand, MANUAL_BIG_CARS, true);
        for (UnifiedSetPartitionSolver.ColumnUse use : pipelineUses) {
            strictPool.add(new UnifiedSetPartitionSolver.FixedColumn(use.column(), use.count()));
            selfCheckPool.add(new UnifiedSetPartitionSolver.FixedColumn(use.column(), use.count()));
        }
        System.out.printf("Level11 fixed pools: strict=%d selfCheck=%d (configs=%d)%n",
                strictPool.size(), selfCheckPool.size(), pool.size());

        UnifiedSetPartitionSolver solver = new UnifiedSetPartitionSolver();
        UnifiedSetPartitionSolver.LpDuals selfCheck = solver.solveFixedLpRelaxation(
                selfCheckPool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH, 0.02);
        UnifiedSetPartitionSolver.LpDuals strict = solver.solveFixedLpRelaxation(
                strictPool, demand, MANUAL_BIG_CARS, MANUAL_BIG_WASTE, TOTAL_WIDTH, 0.02);
        assertNotNull(strict);
        assertNotNull(selfCheck);
        System.out.printf("%n##### Level11 LP bounds: strict=%.3f (%s) | selfCheck=%.3f (旧下界26.9, %s) | 参考解=%d组%n",
                strict.objective(), strict.status(), selfCheck.objective(), selfCheck.status(),
                reference.groups());

        // 缺失定长列 RC 检查（sig#count 粒度——config 在池但档位缺 = levelMissing）
        java.util.Set<String> strictKeys = new java.util.HashSet<>();
        for (UnifiedSetPartitionSolver.FixedColumn fixed : strictPool) {
            strictKeys.add(fixed.key());
        }
        java.util.Set<String> poolSigs = new java.util.HashSet<>();
        for (Column column : pool) {
            poolSigs.add(column.signature());
        }
        int missing = 0;
        int configMissing = 0;
        int negativeRc = 0;
        List<String> rows = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : reference.uses()) {
            String key = use.column().signature() + "#" + use.count();
            if (strictKeys.contains(key)) {
                continue;
            }
            missing++;
            boolean sigInPool = poolSigs.contains(use.column().signature());
            if (!sigInPool) {
                configMissing++;
            }
            double rc = strict.fixedReducedCost(use.column(), use.count(), TOTAL_WIDTH, 0.02);
            if (rc < -1e-9) {
                negativeRc++;
            }
            if (rows.size() < 12) {
                rows.add(String.format("  RC=%+.4f %s %s | %d车 %s",
                        rc, rc < -1e-9 ? "PULL" : "skip",
                        sigInPool ? "levelMissing" : "configMissing",
                        use.count(), use.column().signature()));
            }
        }
        System.out.printf("##### Level11 pricing check: missing=%d (configMissing=%d levelMissing=%d) negativeRC=%d (%.0f%%) — 目标≥70%%%n",
                missing, configMissing, missing - configMissing, negativeRc,
                missing == 0 ? 100.0 : 100.0 * negativeRc / missing);
        for (String row : rows) {
            System.out.println(row);
        }
        System.out.println("verdict: strict下界接近46 且 RC命中≥70% → 绿灯建定价循环+diving; 下界仍塌 → 档位设计再修; RC不达标 → 主问题仍需重构");
    }

    /**
     * Level 12（残差导向列注入 v1）：L11 关闭 LP 对偶定价后的幸存方向。
     * 上下文 = 当前整数解的残差，不是 LP 对偶：从生产公平管线解出发，拆掉弱块
     * （odd 或 c≤5），残差需求现场跑比例匹配列生成（widthOptions 在残差量上恰好
     * 给出"还剩谁、谁互补"的配对——L9 证明静态排序原理上猜不中的正是这个上下文），
     * 小规模 set-partition 精确重建（车数等式+废边≤拆除额，全局走廊不变；
     * 弱块本身进子池兜底，重建只可能不劣）。子问题小 ⇒ SCIP 可 OPTIMAL，
     * 绕开 L3-L9 反复撞的 warm start 盆地搜索迷路。
     * Round A：只拆弱块；Round B：弱块 + 每弱块一个宽度重叠最大的捐赠强块。
     * 通过标准：任一 Round 组数或 odd 严格改善 ⇒ 机制成立，做成多轮迭代。
     */
    @Test
    void level12ResidualColumnInjection() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        List<UnifiedSetPartitionSolver.ColumnUse> start = runPipelineBigGroup();
        printBlockStats("Level12 start (pipeline)", start);

        // Round A：弱块 = odd 或 small(c≤5)
        List<UnifiedSetPartitionSolver.ColumnUse> weak = new ArrayList<>();
        List<UnifiedSetPartitionSolver.ColumnUse> kept = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : start) {
            if (use.count() % 2 != 0 || use.count() <= 5) {
                weak.add(use);
            } else {
                kept.add(use);
            }
        }
        rebuildAndReport("Level12 RoundA (weak only)", weak, kept, demand);

        // Round B：弱块 + 每弱块一个宽度重叠最大的捐赠强块（扩大配对空间）
        List<UnifiedSetPartitionSolver.ColumnUse> donors = new ArrayList<>();
        List<UnifiedSetPartitionSolver.ColumnUse> keptB = new ArrayList<>(kept);
        for (UnifiedSetPartitionSolver.ColumnUse w : weak) {
            UnifiedSetPartitionSolver.ColumnUse best = null;
            int bestOverlap = 0;
            for (UnifiedSetPartitionSolver.ColumnUse s : keptB) {
                int overlap = 0;
                for (Integer width : w.column().pattern().keySet()) {
                    if (s.column().pattern().containsKey(width)) {
                        overlap++;
                    }
                }
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = s;
                }
            }
            if (best != null) {
                donors.add(best);
                keptB.remove(best);
            }
        }
        List<UnifiedSetPartitionSolver.ColumnUse> weakB = new ArrayList<>(weak);
        weakB.addAll(donors);
        rebuildAndReport("Level12 RoundB (weak + donors)", weakB, keptB, demand);
    }

    /**
     * Level 12b（残差导向列注入 v2：微邻域迭代）：v1 裁决——RoundA 弱块残差无量可配、
     * RoundB 邻域过大回到搜索迷路区，两轮均 FEASIBLE 非 OPTIMAL。v2 取中间尺度：
     * 每次 1 个目标块（odd 优先，其次 small）+ 3 个共享需求键最多的捐赠块，
     * 子问题 10-80 车 SCIP 可证 OPTIMAL，逐块接受严格改善（组数减，或组数平且 odd 减），
     * 多 pass 至不动点。这是生产 LNS 的移动结构 × set-partition 组数目标 × 比例匹配注入列。
     */
    @Test
    void level12bMicroNeighborhoodIteration() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        List<UnifiedSetPartitionSolver.ColumnUse> current = new ArrayList<>(runPipelineBigGroup());
        printBlockStats("Level12b start (pipeline)", current);

        current = microIterate(current, 3, 30_000, 3, "Level12b");
        verifyMerged("Level12b micro iteration (start 47/3/13, ref 46/1)", current, demand);
    }

    /**
     * Level 12c（合并推进段）：L12b 把 odd 修到 1 后，组数 47→46 需要 k→k−1 合并
     * ——4 块微邻域内无组数 accept，扩到 1 目标 + 5 捐赠（约 6 块/60-150 车）60s 子解。
     * 两段式：先跑 L12b 同参奇偶段，再推合并；同一严格改善接受（组减 > 组平且odd减）。
     */
    @Test
    void level12cGroupsMergePush() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        List<UnifiedSetPartitionSolver.ColumnUse> current;
        if (java.nio.file.Files.exists(PIPELINE_SNAPSHOT)) {
            current = new ArrayList<>(loadPipelineSnapshot());
            System.out.println("Level12c start from snapshot: " + PIPELINE_SNAPSHOT);
        } else {
            current = new ArrayList<>(runPipelineBigGroup());
        }
        printBlockStats("Level12c start", current);
        current = microIterate(current, 3, 30_000, 3, "Level12c-parity");
        printBlockStats("Level12c after parity stage", current);
        current = microIterate(current, 5, 60_000, 2, "Level12c-merge");
        verifyMerged("Level12c merge push (parity目标46/1)", current, demand);
    }

    @Test
    void level13StrictPipelineSnapshotPoolBoundary() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        List<UnifiedSetPartitionSolver.ColumnUse> warmStart = loadPipelineSnapshot();
        List<Column> pool = warmStart.stream()
                .map(UnifiedSetPartitionSolver.ColumnUse::column)
                .toList();
        int cars = warmStart.stream()
                .mapToInt(UnifiedSetPartitionSolver.ColumnUse::count)
                .sum();
        int waste = warmStart.stream()
                .mapToInt(use -> (TOTAL_WIDTH - use.column().patternWidth()) * use.count())
                .sum();

        UnifiedSetPartitionSolver.LexicographicResult result =
                new UnifiedSetPartitionSolver().solveLexicographic(
                        pool, demand, cars, waste, TOTAL_WIDTH, 30_000L, warmStart);

        assertNotNull(result);
        assertTrue(result.provenOptimal(), "snapshot column-pool boundary must be proven");
        assertNotNull(result.result());
        assertEquals(47, result.result().groups());
        assertEquals(3, result.result().oddBlocks());
        assertEquals(13, result.result().smallBlocks());
        assertEquals(449, result.result().cars());
        assertEquals(99_380, result.result().waste());
        verifyDemandExact(result.result(), demand);
    }

    @Test
    void level14ArchivedResidualColumnsFeedStrictGlobalMaster() throws Exception {
        Map<String, Integer> demand = loadBigGroupDemand();
        List<UnifiedSetPartitionSolver.ColumnUse> start = loadPipelineSnapshot();
        Map<String, Column> archive = new LinkedHashMap<>();

        List<UnifiedSetPartitionSolver.ColumnUse> current = microIterate(
                start, 3, 30_000L, 3, "Level14-parity", archive);
        current = microIterate(
                current, 5, 60_000L, 2, "Level14-merge", archive);
        for (UnifiedSetPartitionSolver.ColumnUse use : current) {
            archive.putIfAbsent(use.column().signature(), use.column());
        }

        UnifiedSetPartitionSolver.LexicographicResult result =
                new UnifiedSetPartitionSolver().solveLexicographic(
                        new ArrayList<>(archive.values()),
                        demand,
                        MANUAL_BIG_CARS,
                        MANUAL_BIG_WASTE,
                        TOTAL_WIDTH,
                        30_000L,
                        current);

        assertNotNull(result);
        assertNotNull(result.result());
        assertTrue(result.result().groups() <= 46, "global master must rebuild 46-group incumbent");
        assertTrue(result.result().oddBlocks() <= 1, "global master must keep odd improvement");
        assertEquals(MANUAL_BIG_CARS, result.result().cars());
        assertTrue(result.result().waste() <= MANUAL_BIG_WASTE);
        verifyDemandExact(result.result(), demand);
        System.out.printf("%n##### Level14 archived global master: pool=%d result=%d/%d/%d "
                        + "status=%s proven=%s bound=%.3f gap=%.6f%n",
                archive.size(), result.result().groups(), result.result().oddBlocks(),
                result.result().smallBlocks(), result.result().status(), result.provenOptimal(),
                result.result().bestBound(), result.result().relativeGap());
    }

    private static final java.nio.file.Path PIPELINE_SNAPSHOT =
            java.nio.file.Path.of("src/test/resources/pipeline_big_group_snapshot.csv");

    /**
     * 工具测试：跑一次管线并把 1350m 大组解快照到 CSV（count;signature）。
     * 管线墙钟截断非确定（两日实测 47/3/13 vs 52/7/19），微邻域实验必须冻结好起点。
     * 仅当无快照或候选更优（组少，平则 odd 少）时覆盖——可反复重掷直到抽到好起点。
     */
    @Test
    void dumpPipelineBigGroupSnapshot() throws Exception {
        List<UnifiedSetPartitionSolver.ColumnUse> candidate = runPipelineBigGroup();
        printBlockStats("snapshot candidate", candidate);
        if (java.nio.file.Files.exists(PIPELINE_SNAPSHOT)) {
            List<UnifiedSetPartitionSolver.ColumnUse> existing = loadPipelineSnapshot();
            int[] prev = groupsOdd(existing);
            int[] cand = groupsOdd(candidate);
            if (prev[0] < cand[0] || (prev[0] == cand[0] && prev[1] <= cand[1])) {
                System.out.printf("KEEP existing snapshot %d/%d (candidate %d/%d worse-or-equal)%n",
                        prev[0], prev[1], cand[0], cand[1]);
                return;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (UnifiedSetPartitionSolver.ColumnUse use : candidate) {
            sb.append(use.count()).append(';').append(use.column().signature()).append('\n');
        }
        java.nio.file.Files.createDirectories(PIPELINE_SNAPSHOT.getParent());
        java.nio.file.Files.writeString(PIPELINE_SNAPSHOT, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("SNAPSHOT WRITTEN: " + PIPELINE_SNAPSHOT);
    }

    /** 快照加载：signature 完整可逆（config → pattern=各宽度工位数）。 */
    private List<UnifiedSetPartitionSolver.ColumnUse> loadPipelineSnapshot() throws Exception {
        List<UnifiedSetPartitionSolver.ColumnUse> uses = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(PIPELINE_SNAPSHOT, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            int sep = line.indexOf(';');
            int count = Integer.parseInt(line.substring(0, sep));
            Map<Integer, List<String>> config = new TreeMap<>();
            Map<Integer, Integer> pattern = new TreeMap<>();
            for (String part : line.substring(sep + 1).split("\\|")) {
                int eq = part.indexOf('=');
                int width = Integer.parseInt(part.substring(0, eq));
                List<String> messages = new ArrayList<>(List.of(part.substring(eq + 1).split(",")));
                config.put(width, messages);
                pattern.put(width, messages.size());
            }
            uses.add(new UnifiedSetPartitionSolver.ColumnUse(Column.of(pattern, config), count));
        }
        return uses;
    }

    private int[] groupsOdd(List<UnifiedSetPartitionSolver.ColumnUse> uses) {
        int odd = 0;
        for (UnifiedSetPartitionSolver.ColumnUse use : uses) {
            if (use.count() % 2 != 0) {
                odd++;
            }
        }
        return new int[] {uses.size(), odd};
    }

    /**
     * 微邻域迭代主循环：每次 1 目标块（odd 优先，其次 small≤5）+ donorCount 个捐赠块
     * （共享需求键×2+共享宽度打分），残差精确重建，严格改善（组减，或组平 odd 减）接受，
     * pass 无改善即收敛退出。
     */
    private List<UnifiedSetPartitionSolver.ColumnUse> microIterate(
            List<UnifiedSetPartitionSolver.ColumnUse> start,
            int donorCount, long budgetMs, int maxPasses, String label) {
        return microIterate(start, donorCount, budgetMs, maxPasses, label, null);
    }

    private List<UnifiedSetPartitionSolver.ColumnUse> microIterate(
            List<UnifiedSetPartitionSolver.ColumnUse> start,
            int donorCount, long budgetMs, int maxPasses, String label,
            Map<String, Column> archive) {
        List<UnifiedSetPartitionSolver.ColumnUse> current = new ArrayList<>(start);
        if (archive != null) {
            for (UnifiedSetPartitionSolver.ColumnUse use : current) {
                archive.putIfAbsent(use.column().signature(), use.column());
            }
        }
        for (int pass = 1; pass <= maxPasses; pass++) {
            boolean improvedThisPass = false;
            // 目标快照（odd 优先，其次 small；按值定位，块可能已被前面的重建消耗）
            List<UnifiedSetPartitionSolver.ColumnUse> targets = new ArrayList<>();
            for (UnifiedSetPartitionSolver.ColumnUse use : current) {
                if (use.count() % 2 != 0) {
                    targets.add(use);
                }
            }
            for (UnifiedSetPartitionSolver.ColumnUse use : current) {
                if (use.count() % 2 == 0 && use.count() <= 5) {
                    targets.add(use);
                }
            }
            for (UnifiedSetPartitionSolver.ColumnUse target : targets) {
                int targetIdx = current.indexOf(target);
                if (targetIdx < 0) {
                    continue;
                }
                List<UnifiedSetPartitionSolver.ColumnUse> others = new ArrayList<>(current);
                others.remove(targetIdx);
                Map<String, Integer> targetUse = target.column().demandUse();
                others.sort(Comparator.comparingInt((UnifiedSetPartitionSolver.ColumnUse s) -> {
                    int sharedKeys = 0;
                    for (String key : s.column().demandUse().keySet()) {
                        if (targetUse.containsKey(key)) {
                            sharedKeys++;
                        }
                    }
                    int sharedWidths = 0;
                    for (Integer width : s.column().pattern().keySet()) {
                        if (target.column().pattern().containsKey(width)) {
                            sharedWidths++;
                        }
                    }
                    return -(sharedKeys * 2 + sharedWidths);
                }));
                List<UnifiedSetPartitionSolver.ColumnUse> removed = new ArrayList<>();
                removed.add(target);
                removed.addAll(others.subList(0, Math.min(donorCount, others.size())));
                List<UnifiedSetPartitionSolver.ColumnUse> kept = new ArrayList<>(current);
                for (UnifiedSetPartitionSolver.ColumnUse r : removed) {
                    kept.remove(r);
                }

                Result sub = microRebuild(removed, budgetMs, archive);
                if (sub == null || sub.groups() == 0) {
                    continue;
                }
                int removedOdd = 0;
                for (UnifiedSetPartitionSolver.ColumnUse r : removed) {
                    if (r.count() % 2 != 0) {
                        removedOdd++;
                    }
                }
                boolean better = sub.groups() < removed.size()
                        || (sub.groups() == removed.size() && sub.oddBlocks() < removedOdd);
                if (better) {
                    System.out.printf("  ACCEPT %s pass%d: %d blocks (odd %d) -> %d (odd %d) | target=%d车 %s%n",
                            label, pass, removed.size(), removedOdd, sub.groups(), sub.oddBlocks(),
                            target.count(), target.column().signature());
                    current = kept;
                    current.addAll(sub.uses());
                    improvedThisPass = true;
                }
            }
            printBlockStats(label + " after pass" + pass, current);
            if (!improvedThisPass) {
                break;
            }
        }
        return current;
    }

    /** 合并解全局守恒验证（车数等式/废边上限/需求逐键精确）+ 汇报。 */
    private void verifyMerged(String label,
            List<UnifiedSetPartitionSolver.ColumnUse> current, Map<String, Integer> demand) {
        int odd = 0;
        int small = 0;
        int cars = 0;
        int waste = 0;
        for (UnifiedSetPartitionSolver.ColumnUse use : current) {
            cars += use.count();
            waste += use.count() * (TOTAL_WIDTH - use.column().patternWidth());
            if (use.count() % 2 != 0) {
                odd++;
            }
            if (use.count() <= 5) {
                small++;
            }
        }
        Result mergedResult = new Result(current, current.size(), odd, small, cars, waste, "MICRO");
        assertEquals(MANUAL_BIG_CARS, cars, "cars conserved");
        assertTrue(waste <= MANUAL_BIG_WASTE, "waste within cap");
        verifyDemandExact(mergedResult, demand);
        System.out.printf("%n##### %s: global %d/%d/%d cars=%d waste=%d%n",
                label, current.size(), odd, small, cars, waste);
    }

    /** 微邻域重建：残差需求 + 比例匹配注入列 + 拆除块兜底，单段求解（odd 已在目标）。 */
    private Result microRebuild(List<UnifiedSetPartitionSolver.ColumnUse> removed, long budgetMs) {
        return microRebuild(removed, budgetMs, null);
    }

    private Result microRebuild(List<UnifiedSetPartitionSolver.ColumnUse> removed,
            long budgetMs, Map<String, Column> archive) {
        Map<String, Integer> residual = new LinkedHashMap<>();
        int removedCars = 0;
        int removedWaste = 0;
        for (UnifiedSetPartitionSolver.ColumnUse use : removed) {
            removedCars += use.count();
            removedWaste += use.count() * (TOTAL_WIDTH - use.column().patternWidth());
            for (Map.Entry<String, Integer> e : use.column().demandUse().entrySet()) {
                residual.merge(e.getKey(), use.count() * e.getValue(), Integer::sum);
            }
        }
        Map<Integer, Map<String, Integer>> residualByWidth = byWidth(residual);
        List<Map<Integer, Integer>> shapes = stratifiedShapes(
                new ArrayList<>(residualByWidth.keySet()), 4300, 4400, 5, 6000, 100, 400,
                residualByWidth);
        List<Column> subPool = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : removed) {
            subPool.add(use.column());
            shapes.add(use.column().pattern());
        }
        subPool.addAll(UnifiedSetPartitionSolver.structuredColumns(shapes, residualByWidth, 2, 20, 80));
        if (archive != null) {
            for (Column column : subPool) {
                archive.putIfAbsent(column.signature(), column);
            }
        }
        return new UnifiedSetPartitionSolver().solve(
                subPool, residual, removedCars, removedWaste, TOTAL_WIDTH, budgetMs, 0.02,
                new ArrayList<>(removed));
    }

    /** 拆除 removed、对残差现场生成比例匹配列、精确重建（字典序两段）、合并守恒验证。 */
    private void rebuildAndReport(String label,
            List<UnifiedSetPartitionSolver.ColumnUse> removed,
            List<UnifiedSetPartitionSolver.ColumnUse> kept,
            Map<String, Integer> demand) {
        Map<String, Integer> residual = new LinkedHashMap<>();
        int removedCars = 0;
        int removedWaste = 0;
        int removedOdd = 0;
        for (UnifiedSetPartitionSolver.ColumnUse use : removed) {
            removedCars += use.count();
            removedWaste += use.count() * (TOTAL_WIDTH - use.column().patternWidth());
            if (use.count() % 2 != 0) {
                removedOdd++;
            }
            for (Map.Entry<String, Integer> e : use.column().demandUse().entrySet()) {
                residual.merge(e.getKey(), use.count() * e.getValue(), Integer::sum);
            }
        }
        Map<Integer, Map<String, Integer>> residualByWidth = byWidth(residual);
        List<Map<Integer, Integer>> shapes = stratifiedShapes(
                new ArrayList<>(residualByWidth.keySet()), 4300, 4400, 5, 6000, 100, 400,
                residualByWidth);
        List<Column> subPool = new ArrayList<>();
        for (UnifiedSetPartitionSolver.ColumnUse use : removed) {
            subPool.add(use.column());
            shapes.add(use.column().pattern());
        }
        subPool.addAll(UnifiedSetPartitionSolver.structuredColumns(shapes, residualByWidth, 2, 20, 80));
        System.out.printf("%s: removed=%d blocks (odd=%d cars=%d waste=%d) residualKeys=%d subPool=%d%n",
                label, removed.size(), removedOdd, removedCars, removedWaste,
                residual.size(), subPool.size());

        Result rebuilt = new UnifiedSetPartitionSolver().solve(
                subPool, residual, removedCars, removedWaste, TOTAL_WIDTH, 180_000, 0.02,
                new ArrayList<>(removed));
        assertNotNull(rebuilt);
        Result finalSub = rebuilt;
        if (rebuilt.groups() > 0) {
            Result refined = new UnifiedSetPartitionSolver().solve(
                    subPool, residual, removedCars, removedWaste, TOTAL_WIDTH, 120_000, 0.02,
                    rebuilt.uses(), rebuilt.groups());
            if (refined != null && refined.groups() > 0) {
                finalSub = refined;
            }
        }

        // 合并 + 全局守恒验证
        List<UnifiedSetPartitionSolver.ColumnUse> merged = new ArrayList<>(kept);
        merged.addAll(finalSub.uses());
        int odd = 0;
        int small = 0;
        int cars = 0;
        int waste = 0;
        for (UnifiedSetPartitionSolver.ColumnUse use : merged) {
            cars += use.count();
            waste += use.count() * (TOTAL_WIDTH - use.column().patternWidth());
            if (use.count() % 2 != 0) {
                odd++;
            }
            if (use.count() <= 5) {
                small++;
            }
        }
        Result mergedResult = new Result(merged, merged.size(), odd, small, cars, waste,
                finalSub.status());
        assertEquals(MANUAL_BIG_CARS, cars, "cars conserved");
        assertTrue(waste <= MANUAL_BIG_WASTE, "waste within cap");
        verifyDemandExact(mergedResult, demand);
        System.out.printf("%n##### %s: sub %d->%d blocks (odd %d->%d, status=%s) | global %d/%d/%d cars=%d waste=%d%n",
                label, removed.size(), finalSub.groups(), removedOdd, finalSub.oddBlocks(),
                finalSub.status(), merged.size(), odd, small, cars, waste);
    }

    private void printBlockStats(String label, List<UnifiedSetPartitionSolver.ColumnUse> uses) {
        int odd = 0;
        int small = 0;
        int cars = 0;
        int waste = 0;
        for (UnifiedSetPartitionSolver.ColumnUse use : uses) {
            cars += use.count();
            waste += use.count() * (TOTAL_WIDTH - use.column().patternWidth());
            if (use.count() % 2 != 0) {
                odd++;
            }
            if (use.count() <= 5) {
                small++;
            }
        }
        System.out.printf("%s: groups=%d odd=%d small=%d cars=%d waste=%d%n",
                label, uses.size(), odd, small, cars, waste);
    }

    /** 该列的每个宽度配置能否被 widthOptions 在给定截断参数下生成。 */
    private boolean configGeneratable(Column column,
            Map<Integer, Map<String, Integer>> demandByWidth, int bufferCount, int maxOptions) {
        for (Map.Entry<Integer, List<String>> entry : column.config().entrySet()) {
            List<String> wanted = new ArrayList<>(entry.getValue());
            wanted.sort(String::compareTo);
            List<List<String>> options = UnifiedSetPartitionSolver.widthOptions(
                    demandByWidth.getOrDefault(entry.getKey(), Map.of()),
                    wanted.size(), bufferCount, maxOptions);
            boolean found = false;
            for (List<String> option : options) {
                List<String> sorted = new ArrayList<>(option);
                sorted.sort(String::compareTo);
                if (sorted.equals(wanted)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
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
        return stratifiedShapes(widths, minRw, maxRw, maxDistinct, rawCap, perBand, totalCap, null);
    }

    /**
     * demandByWidth 非空时用"需求质量加权"排序（L8 诊断：宽度种类少优先的旧排序把
     * 参考解 27/46 的形状排出池外——人工大量用 5 宽度近邻组合如 840×2+850×2+980，
     * 与旧标准正好相反）。得分 = Σ k_w×q_w（形状触及的总需求量），大者优先。
     */
    private List<Map<Integer, Integer>> stratifiedShapes(List<Integer> widths,
            int minRw, int maxRw, int maxDistinct, int rawCap, int perBand, int totalCap,
            Map<Integer, Map<String, Integer>> demandByWidth) {
        List<Map<Integer, Integer>> raw = new ArrayList<>();
        enumShapesRec(widths, 0, new LinkedHashMap<>(), 0, minRw, maxRw, maxDistinct, raw, rawCap);
        Map<Integer, List<Map<Integer, Integer>>> byBand = new TreeMap<>(Comparator.reverseOrder());
        for (Map<Integer, Integer> shape : raw) {
            int pw = shape.entrySet().stream().mapToInt(e -> e.getKey() * e.getValue()).sum();
            byBand.computeIfAbsent(pw / 10, k -> new ArrayList<>()).add(shape);
        }
        Comparator<Map<Integer, Integer>> ranking;
        if (demandByWidth == null) {
            ranking = Comparator
                    .comparingInt((Map<Integer, Integer> s) -> s.size())
                    .thenComparing(s -> -s.entrySet().stream().mapToInt(e -> e.getKey() * e.getValue()).sum());
        } else {
            ranking = Comparator.comparingLong((Map<Integer, Integer> s) -> -s.entrySet().stream()
                    .mapToLong(e -> (long) e.getValue() * demandByWidth
                            .getOrDefault(e.getKey(), Map.of()).values().stream()
                            .mapToInt(Integer::intValue).sum())
                    .sum());
        }
        List<Map<Integer, Integer>> kept = new ArrayList<>();
        for (List<Map<Integer, Integer>> band : byBand.values()) {
            band.sort(ranking);
            for (int i = 0; i < Math.min(perBand, band.size()) && kept.size() < totalCap; i++) {
                kept.add(band.get(i));
            }
            if (kept.size() >= totalCap) {
                break;
            }
        }
        System.out.printf("stratifiedShapes: raw=%d bands=%d kept=%d demandAware=%b%n",
                raw.size(), byBand.size(), kept.size(), demandByWidth != null);
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
