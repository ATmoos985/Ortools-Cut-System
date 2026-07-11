package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.generator.NewSolver.output.SolverRunColumnArchive;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Real production case "四线T9EST188" (83 orders, ~1825 rolls). Human planners achieve
 * 47 sequence groups; the production system 55 — at identical efficiency. This harness
 * runs the NewSolver to see where it lands, as the baseline for sequence-group
 * consolidation (Approach 3 / LNS).
 */
class B5SixianTest {

    @Test
    void runSixian() throws Exception {
        Path lockPath = Path.of(System.getProperty(
                "cutting.test.experimentLock", "solver-experiments/solver.lock"));
        try (SolverExperimentGuard ignored = SolverExperimentGuard.acquire(lockPath)) {
            List<SolverOrderItem> items = loadItems();
            SolverConfig config = buildConfig();

            long t0 = System.currentTimeMillis();
            CuttingSolver solver = new CuttingSolver();
            SolverRunColumnArchive.Captured<List<CuttingInstruction>> captured =
                    SolverRunColumnArchive.capture(() -> solver.solve(items, config));
            List<CuttingInstruction> instructions = captured.value();
            long elapsed = System.currentTimeMillis() - t0;

            SequenceGroupPostProcessor.GroupStats stats =
                    SequenceGroupPostProcessor.computeGroupStats(instructions);
            List<ColumnUse> uses = SolverExperimentSnapshot.fromInstructions(instructions);
            SolverExperimentSnapshot.Metrics metrics =
                    SolverExperimentSnapshot.metrics(uses, config.getTotalWidth());
            org.junit.jupiter.api.Assertions.assertEquals(
                    SolverExperimentSnapshot.demandOf(items),
                    SolverExperimentSnapshot.producedBy(uses));
            org.junit.jupiter.api.Assertions.assertEquals(stats.groups(), metrics.groups());
            org.junit.jupiter.api.Assertions.assertEquals(stats.oddCarGroups(), metrics.oddGroups());
            org.junit.jupiter.api.Assertions.assertEquals(stats.smallCarGroups(), metrics.smallGroups());
            if (CuttingSolver.qualityMode()) {
                org.junit.jupiter.api.Assertions.assertTrue(stats.groups() <= 42,
                        "quality groups=" + stats.groups());
                org.junit.jupiter.api.Assertions.assertTrue(stats.oddCarGroups() <= 2,
                        "quality odd groups=" + stats.oddCarGroups());
                org.junit.jupiter.api.Assertions.assertEquals(0, stats.oneCarGroups(),
                        "quality one-car groups");
                org.junit.jupiter.api.Assertions.assertTrue(stats.smallCarGroups() <= 12,
                        "quality small groups=" + stats.smallCarGroups());
            }

            System.out.println("\n##### SIXIAN RESULT #####");
            System.out.println("items=" + items.size() + " instructions=" + instructions.size());
            System.out.println("groups=" + stats.groups()
                    + " oddCarGroups=" + stats.oddCarGroups()
                    + " oneCarGroups=" + stats.oneCarGroups()
                    + " smallCarGroups=" + stats.smallCarGroups());
            System.out.println("cars=" + metrics.cars() + " waste=" + metrics.waste());
            System.out.println("discoveredColumns=" + captured.columns().size());
            System.out.println("elapsedMs=" + elapsed + "   (quality target=42/2/12)");
            System.out.println("#########################\n");

            persistSixianArtifacts(uses, captured.columns(), metrics, config.getTotalWidth());
            dumpPatterns(instructions);
        }
    }

    private void persistSixianArtifacts(List<ColumnUse> uses, List<Column> discoveredColumns,
            SolverExperimentSnapshot.Metrics metrics, int totalWidth) throws IOException {
        Path candidateDir = Path.of(System.getProperty(
                "cutting.test.candidateOutputDir", "solver-experiments"));
        String resultHash = Integer.toUnsignedString(uses.stream()
                .map(use -> use.column().signature() + "#" + use.count())
                .sorted()
                .collect(Collectors.joining("|")).hashCode(), 16);
        Path candidatePath = candidateDir.resolve(
                "sixian-candidate-" + metrics.groups() + "-"
                        + metrics.oddGroups() + "-" + metrics.smallGroups()
                        + "-" + resultHash + ".csv");
        SolverExperimentSnapshot.write(candidatePath, "sixian-candidate", uses, totalWidth);
        List<Column> allColumns = new ArrayList<>(discoveredColumns);
        allColumns.addAll(uses.stream().map(ColumnUse::column).toList());
        Path archivePath = candidateDir.resolve("sixian-columns.csv");
        SolverExperimentSnapshot.ColumnArchive archive =
                SolverExperimentSnapshot.mergeColumnArchive(
                        archivePath, "sixian", allColumns);
        System.out.println("CANDIDATE SNAPSHOT WRITTEN: " + candidatePath.toAbsolutePath());
        System.out.println("COLUMN ARCHIVE MERGED: " + archivePath.toAbsolutePath()
                + " columns=" + archive.columns().size());

        String output = System.getProperty("cutting.test.sixianSnapshotOutput", "").trim();
        if (output.isEmpty()) {
            return;
        }
        org.junit.jupiter.api.Assertions.assertTrue(metrics.groups()
                <= Integer.getInteger("cutting.test.sixianSnapshotMaxGroups", 42));
        org.junit.jupiter.api.Assertions.assertTrue(metrics.oddGroups()
                <= Integer.getInteger("cutting.test.sixianSnapshotMaxOdd", 2));
        org.junit.jupiter.api.Assertions.assertTrue(metrics.smallGroups()
                <= Integer.getInteger("cutting.test.sixianSnapshotMaxSmall", 12));
        SolverExperimentSnapshot.write(Path.of(output), "sixian", uses, totalWidth);
    }

    /**
     * A/B harness for the balance-tiebreak change (option 1). Runs the FULL production path
     * (cutting.lns.enabled=true) twice on T9EST188 — once with the tiebreak off (baseline),
     * once on (default) — and prints groups / oddCarGroups / smallCarGroups for each, plus a
     * cars/waste conservation guard (balance must NOT change cars or waste, only order labels).
     */
    @Test
    void compareBalanceTiebreak() throws Exception {
        List<SolverOrderItem> items = loadItems();
        SolverConfig config = buildConfig();

        SequenceGroupPostProcessor.GroupStats off = runWithProps(items, config,
                java.util.Map.of("cutting.lns.enabled", "true",
                        "cutting.lns.balanceTiebreak", "false"));
        SequenceGroupPostProcessor.GroupStats on = runWithProps(items, config,
                java.util.Map.of("cutting.lns.enabled", "true",
                        "cutting.lns.balanceTiebreak", "true"));

        System.out.println("\n##### BALANCE-TIEBREAK A/B (T9EST188) #####");
        System.out.printf("  OFF (baseline): groups=%d  odd=%d  small=%d  cars=%d  waste=%d%n",
                off.groups(), off.oddCarGroups(), off.smallCarGroups(), lastCars, lastWasteOff);
        System.out.printf("  ON  (balanced): groups=%d  odd=%d  small=%d  cars=%d  waste=%d%n",
                on.groups(), on.oddCarGroups(), on.smallCarGroups(), lastCarsOn, lastWaste);
        System.out.println("  (human=47, no single-car groups)");
        System.out.println("###########################################\n");

        // Invariants that MUST hold (both solves are valid, efficiency-equivalent):
        org.junit.jupiter.api.Assertions.assertEquals(lastCars, lastCarsOn,
                "balance must not change car count");
        org.junit.jupiter.api.Assertions.assertEquals(lastWasteOff, lastWaste,
                "balance must not change waste");
        // NOTE: we do NOT assert on.groups() <= off.groups(). Measured on T9EST188 the balance
        // tiebreak lowers odd groups but RAISES total groups (49->51) — group-min and balance are
        // in tension during the local search. This harness is a measurement tool for that tradeoff.
        if (on.groups() > off.groups()) {
            System.out.printf("  [tradeoff] balance cut odd %d->%d but raised groups %d->%d%n",
                    off.oddCarGroups(), on.oddCarGroups(), off.groups(), on.groups());
        }
    }

    private int lastCars, lastCarsOn, lastWasteOff, lastWaste;

    private SequenceGroupPostProcessor.GroupStats runWithProps(
            List<SolverOrderItem> items, SolverConfig config, java.util.Map<String, String> props) {
        java.util.Map<String, String> previous = new java.util.HashMap<>();
        for (String k : props.keySet()) previous.put(k, System.getProperty(k));
        props.forEach(System::setProperty);
        try {
            long t0 = System.currentTimeMillis();
            List<CuttingInstruction> ins = new CuttingSolver().solve(items, config);
            long elapsed = System.currentTimeMillis() - t0;
            int cars = 0, waste = 0;
            for (CuttingInstruction ci : ins) {
                cars += ci.getUsageCount();
                waste += ci.getWaste() * ci.getUsageCount();
            }
            boolean off = "false".equals(props.get("cutting.lns.balanceTiebreak"));
            if (off) { lastCars = cars; lastWasteOff = waste; }
            else { lastCarsOn = cars; lastWaste = waste; }
            SequenceGroupPostProcessor.GroupStats stats =
                    SequenceGroupPostProcessor.computeGroupStats(ins);
            System.out.printf("  [%s] groups=%d odd=%d small=%d cars=%d waste=%d elapsed=%dms%n",
                    off ? "OFF" : "ON", stats.groups(), stats.oddCarGroups(), stats.smallCarGroups(),
                    cars, waste, elapsed);
            return stats;
        } finally {
            previous.forEach((k, v) -> {
                if (v == null) System.clearProperty(k); else System.setProperty(k, v);
            });
        }
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

    /**
     * 实验:用「去集中化」目标重选 1350m 花型集(同最小废边、得率中性),再喂 enriched LNS,
     * 验证 A 层均衡分布能否让我的管线高效到 ~43(碾压人工)。
     * 去集中目标 = min Σ(patternWidth≥4390 的车数),把 4400 的堆摊向中间。
     */
    @Test
    void runBalancedSelection() throws Exception {
        com.google.ortools.Loader.loadNativeLibraries();
        SolverConfig config = buildConfig();
        var params = test.demo.apsmodule.generator.NewSolver.config.SolverParameters.createDefault();
        params.mergeFrom(config);
        params.sanitize();
        int totalWidth = params.getTotalWidth(); // 4600
        int minRw = params.getMinRollWidth(), maxRw = params.getMaxRollWidth(); // 4300..4400

        List<SolverOrderItem> items1350 = new ArrayList<>();
        for (SolverOrderItem it : loadItems()) if (it.getLength() == 1350) items1350.add(it);
        java.util.Map<Integer, Integer> demand = new java.util.TreeMap<>();
        for (SolverOrderItem it : items1350) demand.merge(it.getWidth(), it.getDemand(), Integer::sum);
        List<Integer> widths = new ArrayList<>(demand.keySet());

        // 枚举花型池(宽度多重集,sum∈[minRw,maxRw],distinct≤maxDistinct),含中间 pw
        List<java.util.Map<Integer, Integer>> pool = new ArrayList<>();
        enumPatterns(widths, 0, new java.util.LinkedHashMap<>(), 0, minRw, maxRw,
                params.getMaxDistinctWidths(), pool, 6000);
        System.out.println("balanced pool=" + pool.size());

        int wasteTarget = 99380; // 1350m 组的最小废边(各跑一致),得率中性约束

        MPSolver solver = MPSolver.createSolver("SCIP");
        solver.setSolverSpecificParametersAsString(
                "randomization/randomseedshift = 0\nlimits/nodes = 40000\n");
        try { solver.setNumThreads(1); } catch (Exception ignored) {}
        int n = pool.size();
        MPVariable[] x = new MPVariable[n];
        int tot = demand.values().stream().mapToInt(Integer::intValue).sum();
        for (int i = 0; i < n; i++) x[i] = solver.makeIntVar(0, tot, "x" + i);
        for (int w : widths) {
            MPConstraint c = solver.makeConstraint(demand.get(w), demand.get(w), "d" + w);
            for (int i = 0; i < n; i++) {
                int cnt = pool.get(i).getOrDefault(w, 0);
                if (cnt > 0) c.setCoefficient(x[i], cnt);
            }
        }
        MPConstraint wc = solver.makeConstraint(0, wasteTarget, "waste");
        for (int i = 0; i < n; i++) wc.setCoefficient(x[i], totalWidth - patternWidth(pool.get(i)));
        MPObjective obj = solver.objective();
        for (int i = 0; i < n; i++) if (patternWidth(pool.get(i)) >= 4390) obj.setCoefficient(x[i], 1.0);
        obj.setMinimization();
        solver.setTimeLimit(120000);
        MPSolver.ResultStatus st = solver.solve();
        System.out.println("balance MIP status=" + st);

        java.util.Map<test.demo.apsmodule.generator.NewSolver.model.PatternCandidate, Integer> solution =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int v = (int) Math.round(x[i].solutionValue());
            if (v > 0) solution.put(new test.demo.apsmodule.generator.NewSolver.model.PatternCandidate(
                    pool.get(i), patternWidth(pool.get(i))), v);
        }
        int hi = solution.entrySet().stream().filter(e -> patternWidth(e.getKey().getPattern()) >= 4390)
                .mapToInt(java.util.Map.Entry::getValue).sum();
        int totCars = solution.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("balanced 花型=" + solution.size() + " 车=" + totCars + " 高pw(≥4390)车=" + hi);

        var converter = new test.demo.apsmodule.generator.NewSolver.output.InstructionConverter(params);
        var conv = converter.convertWithDetails(solution, items1350.get(0).getGroupKey(), items1350, demand);
        var stats = SequenceGroupPostProcessor.computeGroupStats(conv.instructions());
        System.out.println("\n##### 均衡重选(1350m) groups=" + stats.groups()
                + " winner=" + conv.selectedName() + "  (人工=46, 我集中=47-48) #####\n");
    }

    /**
     * 假设验证(2026-06-23 数据驱动):人工 1350m 的"合适花型集"特征 = 每宽度被摊进更少花型
     * (Σ每宽度花型重数 人工72 vs 我84)。这里直接以 min Σ_p sel_p·|distinctWidths(p)| 为目标
     * 重选花型集(同需求精确 + 废边≤99380 得率中性),喂确定性 B 层(节点界),看序号组是否往 46-47 掉。
     * 因果验证:若低重数集装配更少组 → 花型集判据成立,这是稳定路径;否则证伪,回到 49 地板。
     */
    @Test
    void runMinWidthMultiplicitySelection() throws Exception {
        com.google.ortools.Loader.loadNativeLibraries();
        SolverConfig config = buildConfig();
        var params = test.demo.apsmodule.generator.NewSolver.config.SolverParameters.createDefault();
        params.mergeFrom(config); params.sanitize();
        int totalWidth = params.getTotalWidth();
        int minRw = params.getMinRollWidth(), maxRw = params.getMaxRollWidth();

        List<SolverOrderItem> items1350 = new ArrayList<>();
        for (SolverOrderItem it : loadItems()) if (it.getLength() == 1350) items1350.add(it);
        java.util.Map<Integer, Integer> demand = new java.util.TreeMap<>();
        for (SolverOrderItem it : items1350) demand.merge(it.getWidth(), it.getDemand(), Integer::sum);
        List<Integer> widths = new ArrayList<>(demand.keySet());

        List<java.util.Map<Integer, Integer>> pool = new ArrayList<>();
        enumPatterns(widths, 0, new java.util.LinkedHashMap<>(), 0, minRw, maxRw,
                params.getMaxDistinctWidths(), pool, 6000);
        int wasteTarget = 99380;

        MPSolver solver = MPSolver.createSolver("SCIP");
        solver.setSolverSpecificParametersAsString("randomization/randomseedshift = 0\nlimits/nodes = 60000\n");
        try { solver.setNumThreads(1); } catch (Exception ignored) {}
        int n = pool.size();
        int tot = demand.values().stream().mapToInt(Integer::intValue).sum();
        MPVariable[] x = new MPVariable[n];
        MPVariable[] sel = new MPVariable[n];
        for (int i = 0; i < n; i++) { x[i] = solver.makeIntVar(0, tot, "x" + i); sel[i] = solver.makeBoolVar("s" + i); }
        for (int wd : widths) {
            MPConstraint c = solver.makeConstraint(demand.get(wd), demand.get(wd), "d" + wd);
            for (int i = 0; i < n; i++) { int cnt = pool.get(i).getOrDefault(wd, 0); if (cnt > 0) c.setCoefficient(x[i], cnt); }
        }
        MPConstraint wc = solver.makeConstraint(0, wasteTarget, "waste");
        for (int i = 0; i < n; i++) wc.setCoefficient(x[i], totalWidth - patternWidth(pool.get(i)));
        for (int i = 0; i < n; i++) { // x_i <= tot * sel_i
            MPConstraint link = solver.makeConstraint(-MPSolver.infinity(), 0, "lk" + i);
            link.setCoefficient(x[i], 1.0); link.setCoefficient(sel[i], -(double) tot);
        }
        MPObjective obj = solver.objective();   // min Σ sel_p · |distinct widths in p|  == Σ_w 每宽度花型重数
        for (int i = 0; i < n; i++) obj.setCoefficient(sel[i], pool.get(i).size());
        obj.setMinimization();
        solver.setTimeLimit(120000);
        MPSolver.ResultStatus st = solver.solve();
        System.out.println("min-mult MIP status=" + st + " Σ每宽度重数=" + Math.round(obj.value()));

        java.util.Map<test.demo.apsmodule.generator.NewSolver.model.PatternCandidate, Integer> solution = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int v = (int) Math.round(x[i].solutionValue());
            if (v > 0) solution.put(new test.demo.apsmodule.generator.NewSolver.model.PatternCandidate(pool.get(i), patternWidth(pool.get(i))), v);
        }
        // verify the selected set's per-width multiplicity (the 84 vs 72 metric)
        java.util.Map<Integer, Integer> mult = new java.util.TreeMap<>();
        for (var e : solution.keySet()) for (int wd : e.getPattern().keySet()) mult.merge(wd, 1, Integer::sum);
        int sumMult = mult.values().stream().mapToInt(Integer::intValue).sum();
        int totCars = solution.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("花型=" + solution.size() + " 车=" + totCars + " Σ每宽度重数=" + sumMult + " (我集中=84,人工=72)");

        // deterministic B-layer (node-limit LNS, secondary off) for reproducibility
        java.util.Map<String, String> props = new java.util.HashMap<>();
        props.put("cutting.lns.enabled", "true"); props.put("cutting.lns.secondary", "false");
        props.put("cutting.lns.scipNodeLimit", "15000");
        java.util.Map<String, String> prev = new java.util.HashMap<>();
        for (String k : props.keySet()) prev.put(k, System.getProperty(k));
        props.forEach(System::setProperty);
        try {
            var converter = new test.demo.apsmodule.generator.NewSolver.output.InstructionConverter(params);
            var conv = converter.convertWithDetails(solution, items1350.get(0).getGroupKey(), items1350, demand);
            var s2 = SequenceGroupPostProcessor.computeGroupStats(conv.instructions());
            // validity guard
            int prodCars = 0, prodWaste = 0;
            for (CuttingInstruction ci : conv.instructions()) { prodCars += ci.getUsageCount(); prodWaste += ci.getWaste() * ci.getUsageCount(); }
            int wantWaste = 0; for (var e : solution.entrySet()) wantWaste += (totalWidth - e.getKey().getPatternWidth()) * e.getValue();
            System.out.println("\n##### MIN-WIDTH-MULT 重选(1350m) groups=" + s2.groups()
                    + " odd=" + s2.oddCarGroups() + " single=" + s2.smallCarGroups()
                    + " winner=" + conv.selectedName()
                    + " | VALID carsOk=" + (prodCars == totCars) + "(" + prodCars + "/" + totCars + ")"
                    + " wasteOk=" + (prodWaste == wantWaste) + "(" + prodWaste + "/" + wantWaste + ")"
                    + "  (我集中=48, 人工=46) #####\n");
        } finally {
            prev.forEach((k, v) -> { if (v == null) System.clearProperty(k); else System.setProperty(k, v); });
        }
    }

    private int patternWidth(java.util.Map<Integer, Integer> pat) {
        int s = 0; for (var e : pat.entrySet()) s += e.getKey() * e.getValue(); return s;
    }

    private void enumPatterns(List<Integer> widths, int idx, java.util.Map<Integer, Integer> cur, int sum,
            int minRw, int maxRw, int maxDistinct, List<java.util.Map<Integer, Integer>> out, int cap) {
        if (out.size() >= cap) return;
        if (idx == widths.size()) {
            if (sum >= minRw && sum <= maxRw && !cur.isEmpty()) out.add(new java.util.LinkedHashMap<>(cur));
            return;
        }
        int w = widths.get(idx);
        int maxC = (maxRw - sum) / w;
        for (int c = 0; c <= maxC && out.size() < cap; c++) {
            if (c > 0) { if (!cur.containsKey(w) && cur.size() >= maxDistinct) break; cur.put(w, c); }
            enumPatterns(widths, idx + 1, cur, sum + c * w, minRw, maxRw, maxDistinct, out, cap);
        }
        cur.remove(w);
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
