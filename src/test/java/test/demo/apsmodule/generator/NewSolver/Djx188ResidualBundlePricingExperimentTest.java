package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateAuditTracker.LossStage;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.ConvertedOutput;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Result;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.output.SequenceGroupPostProcessor;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in DJX188 experiments for the two audit-driven single-variable steps:
 * residual bundle pricing on the autonomous 31-group incumbent (audit design
 * section 10.3) and the width-830 local-config cap 8-to-10 audit replay
 * (section 10.4 earliest-stage fix).
 *
 * <p>Observed 2026-07-26 (default knobs, sizes 2..4):
 * residual bundle pricing reached 28/1/0/169 cars/36,870 mm from the 31-group
 * incumbent via three conserved size-3-to-2 swaps, scanning 2,815 bundles in
 * 49.1 s with no truncation and budget to spare — strictly better than the
 * 29-group automatic22 witness. The cap-10 audit kept the autonomous result
 * at 31 groups while LOCAL_CONFIGURATION_PRUNED dropped 1 to 0
 * (counts became MATERIALIZED_NONNEGATIVE=16, NEGATIVE_GLOBAL_SELECTION
 * _PRUNED=13), confirming the audit's prediction that the cap fix alone
 * cannot recover the witness.</p>
 *
 * <p>Observed 2026-07-26 (maxSize=6, bundlesPerSize=1000, budgetMs=480000,
 * maxSwaps=24): six conserved swaps (three 3-to-2, one 5-to-4, one 4-to-3,
 * one 5-to-4) reached <b>25/1/0/169 cars/36,870 mm</b> — strictly better than
 * the 26-group manual plan — scanning 10,994 bundles with zero candidate
 * truncation before the 480 s budget ran out, so 25 is a budget floor, not a
 * proven local optimum.</p>
 *
 * <p>Replications with the proven-failure memo (same knobs): 360 s reached
 * the identical 25-group solution scanning 5,697 bundles with 4,208 memo
 * skips; 480 s scanned 6,991 with 5,174 skips and still exhausted the budget
 * before completing the final size-2..6 verification sweep. All three runs
 * produced a byte-identical six-swap sequence. T42 cross-dataset
 * (-Dcutting.test.residualBundle.t42=true): baseline 10 groups, 807/837
 * bundles scanned in 120 s, zero swaps, conservation held — the 10-group
 * incumbent is near-locally-optimal at bundle sizes up to 6. On slow
 * machines pass -Dcutting.test.orderGroupPricing.pricingMs=20000: the
 * incumbent seeder's group RMP inherits pricingTimeLimitMs, and its
 * time-truncated pattern support can otherwise come out integer-infeasible
 * (observed GROUP_COLUMN_RMP_FAILED with a converged 23-pattern support).</p>
 */
class Djx188ResidualBundlePricingExperimentTest {

    private static final String BUNDLE_PROPERTY =
            "cutting.test.residualBundlePricing";
    private static final String CAP_PROPERTY =
            "cutting.test.localConfigCapAudit";
    private static final String T42_PROPERTY =
            "cutting.test.residualBundle.t42";

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void residualBundlePricingOnAutonomousIncumbent() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean(BUNDLE_PROPERTY),
                "enable with -D" + BUNDLE_PROPERTY + "=true");

        Input input;
        List<GroupColumn> incumbent;
        int baseline;
        String resumePath =
                System.getProperty("cutting.test.residualBundle.resume");
        if (resumePath == null) {
            Setup setup = autonomousSetup(
                    Options.defaults().maxLocalConfigsPerWidth());
            assertEquals(31, setup.autonomous().directMetrics().groups(),
                    "autonomous baseline drifted");
            input = setup.input();
            incumbent = setup.autonomous().selectedColumns();
            baseline = 31;
        } else {
            input = buildInput();
            incumbent = java.nio.file.Files
                    .readAllLines(java.nio.file.Path.of(resumePath),
                            StandardCharsets.UTF_8)
                    .stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> OrderGroupResidualBundlePricer
                            .parseColumn(input, line.trim()))
                    .toList();
            baseline = incumbent.size();
            System.out.printf(
                    "DJX188 RESUME: loaded %d columns from %s%n",
                    baseline, resumePath);
        }

        OrderGroupResidualBundlePricer.Options bundleOptions =
                new OrderGroupResidualBundlePricer.Options(
                        Integer.getInteger(
                                "cutting.test.residualBundle.minSize", 2),
                        Integer.getInteger(
                                "cutting.test.residualBundle.maxSize", 4),
                        Integer.getInteger(
                                "cutting.test.residualBundle.bundlesPerSize", 400),
                        Integer.getInteger(
                                "cutting.test.residualBundle.maxCandidates", 20_000),
                        Long.getLong(
                                "cutting.test.residualBundle.mipMs", 3_000L),
                        Long.getLong(
                                "cutting.test.residualBundle.budgetMs", 300_000L),
                        Integer.getInteger(
                                "cutting.test.residualBundle.maxSwaps", 16));

        OrderGroupResidualBundlePricer.Result improved =
                OrderGroupResidualBundlePricer.improve(
                        input, incumbent, bundleOptions);

        System.out.printf(
                "DJX188 RESIDUAL_BUNDLE: baseline=" + baseline
                        + " groups=%d odd=%d one=%d "
                        + "cars=%d waste=%d swaps=%d scanned=%d skipped=%d "
                        + "truncated=%d budgetExhausted=%s elapsedMs=%d "
                        + "options=%s%n",
                improved.metrics().groups(),
                improved.metrics().oddGroups(),
                improved.metrics().oneCarGroups(),
                improved.metrics().cars(),
                improved.metrics().waste(),
                improved.swapsApplied(),
                improved.bundlesScanned(),
                improved.bundlesSkipped(),
                improved.bundlesTruncated(),
                improved.budgetExhausted(),
                improved.elapsedMs(),
                bundleOptions);
        for (OrderGroupResidualBundlePricer.SwapRecord swap : improved.swaps()) {
            System.out.printf("  swap delta=%d%n", swap.groupDelta());
            swap.removedSignatures().forEach(signature ->
                    System.out.printf("    - %s%n", signature));
            swap.addedSignatures().forEach(signature ->
                    System.out.printf("    + %s%n", signature));
        }

        verifyExact(input, improved.columns());
        ConvertedOutput converted = OrderGroupColumnPricingEngine.convertSelected(
                input, improved.columns());
        SequenceGroupPostProcessor.GroupStats displayed =
                SequenceGroupPostProcessor.computeGroupStats(
                        converted.instructions());
        assertEquals(improved.metrics().groups(), displayed.groups());
        assertEquals(improved.metrics().oddGroups(), displayed.oddCarGroups());
        assertEquals(improved.metrics().oneCarGroups(), displayed.oneCarGroups());

        java.nio.file.Path saveTo = java.nio.file.Path.of(System.getProperty(
                "cutting.test.residualBundle.saveTo",
                "target/djx188-residual-bundle-incumbent.txt"));
        java.nio.file.Files.createDirectories(
                saveTo.toAbsolutePath().getParent());
        java.nio.file.Files.write(
                saveTo,
                improved.columns().stream()
                        .map(GroupColumn::signature)
                        .toList(),
                StandardCharsets.UTF_8);
        System.out.println(
                "DJX188 RESIDUAL_BUNDLE saved -> " + saveTo.toAbsolutePath());

        assertTrue(improved.metrics().groups() <= baseline);
        if (improved.swapsApplied() > 0) {
            assertTrue(improved.metrics().groups() < baseline,
                    "swaps were applied but groups did not decrease");
        }
    }

    @Test
    void localConfigCapTenEliminatesLocalPruningInAudit() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean(CAP_PROPERTY),
                "enable with -D" + CAP_PROPERTY + "=true");

        int cap = Integer.getInteger("cutting.test.localConfigCap.value", 10);
        Setup setup = autonomousSetup(cap);

        List<PatternCandidate> automaticSupport =
                Djx188ProductionNeutralFixture.loadAutomatic22().keySet().stream()
                        .sorted(java.util.Comparator.comparing(
                                PatternCandidate::signature))
                        .toList();
        Input witnessInput = new Input(
                setup.items(), automaticSupport, setup.params(),
                169, 36_870, 1, 0);
        Result witness = OrderGroupColumnPricingPrototype.solve(
                witnessInput, options(
                        Options.defaults().maxLocalConfigsPerWidth(), false));
        assertTrue(witness.feasible());
        assertEquals(29, witness.directMetrics().groups());

        OrderGroupMissingColumnAudit.AuditResult audit =
                OrderGroupMissingColumnAudit.audit(
                        setup.input(),
                        setup.autonomousOptions(),
                        setup.autonomous(),
                        witness,
                        Long.getLong(
                                "cutting.test.localConfigCap.auditMs", 5_000L));

        System.out.printf(
                "DJX188 LOCAL_CONFIG_CAP_%d: autonomousGroups=%d witness=%d "
                        + "status=%s counts=%s exchangeDelta=%d conserved=%s "
                        + "totalMs=%d%n",
                cap,
                setup.autonomous().directMetrics().groups(),
                witness.directMetrics().groups(),
                audit.status(),
                audit.counts(),
                audit.exchange().groupDelta(),
                audit.exchange().conserved(),
                audit.totalMs());

        assertEquals(
                OrderGroupMissingColumnAudit.AuditStatus.COMPLETE,
                audit.status(),
                audit::detail);
        assertEquals(29, audit.columns().size());
        assertEquals(0, audit.counts().getOrDefault(
                LossStage.LOCAL_CONFIGURATION_PRUNED, 0),
                "cap=" + cap + " should clear the width-830 local pruning");
        assertTrue(audit.exchange().conserved());
        assertTrue(setup.autonomous().directMetrics().groups() <= 31);
    }

    @Test
    void t42ResidualBundlePricingCrossDataset() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean(T42_PROPERTY),
                "enable with -D" + T42_PROPERTY + "=true");

        List<SolverOrderItem> items = loadT42Items();
        SolverParameters params = t42Parameters();
        List<PatternCandidate> universe =
                new CompletePatternEnumerator(params, 5)
                        .generate(aggregateWidthDemand(items));
        Input input = new Input(items, universe, params, 45, 10_820, 1, 0);
        Result autonomous = OrderGroupColumnPricingPrototype.solve(
                input, options(Options.defaults().maxLocalConfigsPerWidth(), true));
        assertTrue(autonomous.feasible(), () ->
                "T42 autonomous status=" + autonomous.status());
        verifyExact(input, autonomous.selectedColumns());
        int baseline = autonomous.directMetrics().groups();
        assertEquals(10, baseline, "T42 baseline drifted");

        OrderGroupResidualBundlePricer.Result improved =
                OrderGroupResidualBundlePricer.improve(
                        input,
                        autonomous.selectedColumns(),
                        new OrderGroupResidualBundlePricer.Options(
                                2,
                                Integer.getInteger(
                                        "cutting.test.residualBundle.maxSize", 6),
                                1_000,
                                20_000,
                                3_000L,
                                Long.getLong(
                                        "cutting.test.residualBundle.budgetMs",
                                        120_000L),
                                16));

        System.out.printf(
                "T42 RESIDUAL_BUNDLE: baseline=%d groups=%d odd=%d one=%d "
                        + "cars=%d waste=%d swaps=%d scanned=%d skipped=%d "
                        + "truncated=%d budgetExhausted=%s elapsedMs=%d%n",
                baseline,
                improved.metrics().groups(),
                improved.metrics().oddGroups(),
                improved.metrics().oneCarGroups(),
                improved.metrics().cars(),
                improved.metrics().waste(),
                improved.swapsApplied(),
                improved.bundlesScanned(),
                improved.bundlesSkipped(),
                improved.bundlesTruncated(),
                improved.budgetExhausted(),
                improved.elapsedMs());
        for (OrderGroupResidualBundlePricer.SwapRecord swap : improved.swaps()) {
            System.out.printf("  swap delta=%d%n", swap.groupDelta());
            swap.removedSignatures().forEach(signature ->
                    System.out.printf("    - %s%n", signature));
            swap.addedSignatures().forEach(signature ->
                    System.out.printf("    + %s%n", signature));
        }

        verifyExact(input, improved.columns());
        ConvertedOutput converted = OrderGroupColumnPricingEngine.convertSelected(
                input, improved.columns());
        SequenceGroupPostProcessor.GroupStats displayed =
                SequenceGroupPostProcessor.computeGroupStats(
                        converted.instructions());
        assertEquals(improved.metrics().groups(), displayed.groups());
        assertTrue(improved.metrics().groups() <= baseline);
    }

    private record Setup(
            List<SolverOrderItem> items,
            SolverParameters params,
            Input input,
            Options autonomousOptions,
            Result autonomous) {
    }

    private static Input buildInput() throws Exception {
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe =
                new CompletePatternEnumerator(params, 5)
                        .generate(aggregateWidthDemand(items));
        assertEquals(7_717, universe.size(), "complete universe drifted");
        return new Input(items, universe, params, 169, 36_870, 1, 0);
    }

    private static Setup autonomousSetup(int localConfigsPerWidth)
            throws Exception {
        Input input = buildInput();
        List<SolverOrderItem> items = input.items();
        SolverParameters params = input.params();
        // The incumbent seeder's pattern-support MIP is a wall-clock lottery:
        // a time-truncated support can be integer-infeasible for the group
        // RMP (observed 2026-07-26: 23-pattern support, pricing converged,
        // INTEGER_MASTER_INFEASIBLE). Deterministically reroll with escalating
        // seed budgets instead of failing on the first draw.
        long firstSeedBudget = Long.getLong(
                "cutting.test.orderGroupPricing.patternSeedMs",
                Options.defaults().patternSeedTimeLimitMs());
        long[] seedBudgets = {firstSeedBudget, 20_000L, 40_000L};
        Options autonomousOptions = null;
        Result autonomous = null;
        for (long seedBudget : seedBudgets) {
            autonomousOptions = options(
                    localConfigsPerWidth, true, seedBudget);
            autonomous = OrderGroupColumnPricingPrototype.solve(
                    input, autonomousOptions);
            System.out.printf(
                    "DJX188 AUTONOMOUS attempt(seedMsBudget=%d): status=%s "
                            + "pricing=%s groups=%d seed=%s seedPatterns=%d "
                            + "seedGroups=%d seedMs=%d seedDetail=%s "
                            + "lpMs=%d pricingMs=%d integerMs=%d totalMs=%d%n",
                    seedBudget,
                    autonomous.status(),
                    autonomous.pricingTermination(),
                    autonomous.directMetrics().groups(),
                    autonomous.seed().status(),
                    autonomous.seed().supportPatterns(),
                    autonomous.seed().groups(),
                    autonomous.seed().totalMs(),
                    autonomous.seed().detail(),
                    autonomous.lpMs(),
                    autonomous.pricingMs(),
                    autonomous.integerMs(),
                    autonomous.totalMs());
            if (autonomous.feasible()) {
                break;
            }
        }
        Result finalAutonomous = autonomous;
        assertTrue(finalAutonomous.feasible(), () ->
                "autonomous failed on all seed budgets, last: status="
                        + finalAutonomous.status()
                        + ", pricing=" + finalAutonomous.pricingTermination()
                        + ", seed=" + finalAutonomous.seed().status()
                        + ", seedDetail=" + finalAutonomous.seed().detail());
        verifyExact(input, autonomous.selectedColumns());
        return new Setup(items, params, input, autonomousOptions, autonomous);
    }

    private static Options options(
            int localConfigsPerWidth, boolean automaticIncumbentSeed) {
        return options(
                localConfigsPerWidth,
                automaticIncumbentSeed,
                Long.getLong(
                        "cutting.test.orderGroupPricing.patternSeedMs",
                        Options.defaults().patternSeedTimeLimitMs()));
    }

    /** Same property names as the missing-column audit test. */
    private static Options options(
            int localConfigsPerWidth,
            boolean automaticIncumbentSeed,
            long patternSeedTimeLimitMs) {
        Options defaults = Options.defaults();
        return new Options(
                defaults.maxIterations(),
                defaults.maxColumns(),
                defaults.maxAddedPerIteration(),
                defaults.maxPerPatternPerIteration(),
                localConfigsPerWidth,
                defaults.beamWidth(),
                defaults.maxColumnsPerPattern(),
                defaults.maxCarCandidatesPerPattern(),
                Long.getLong(
                        "cutting.test.orderGroupPricing.pricingMs",
                        defaults.pricingTimeLimitMs()),
                defaults.integerCompletionTimeLimitMs(),
                defaults.integerRepairCandidates(),
                defaults.integerRepairTimeLimitMs(),
                defaults.lpTimeLimitMs(),
                Long.getLong(
                        "cutting.test.orderGroupPricing.integerMs",
                        defaults.integerTimeLimitMs()),
                defaults.dualAlpha(),
                defaults.reducedCostEpsilon(),
                defaults.artificialEpsilon(),
                defaults.noColumnPatience(),
                automaticIncumbentSeed,
                patternSeedTimeLimitMs);
    }

    private static void verifyExact(Input input, List<GroupColumn> columns) {
        Map<DemandKey, Integer> produced = new TreeMap<>();
        for (GroupColumn column : columns) {
            column.coverage().forEach((key, value) ->
                    produced.merge(key, value, Math::addExact));
        }
        assertEquals(input.demand(), produced);
        assertEquals(input.exactCars(),
                columns.stream().mapToInt(GroupColumn::cars).sum());
        assertEquals(input.exactWaste(),
                columns.stream().mapToInt(GroupColumn::totalWaste).sum());
        assertEquals(input.exactOddGroups(),
                (int) columns.stream().filter(GroupColumn::odd).count());
        assertEquals(input.exactOneCarGroups(),
                (int) columns.stream().filter(GroupColumn::oneCar).count());
    }

    private static Map<Integer, Integer> aggregateWidthDemand(
            List<SolverOrderItem> items) {
        Map<Integer, Integer> demand = new TreeMap<>();
        for (SolverOrderItem item : items) {
            demand.merge(item.getWidth(), item.getDemand(), Math::addExact);
        }
        return demand;
    }

    /** Mirrors the T42 setup of the order-group pricing experiment. */
    private static SolverParameters t42Parameters() {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(4300);
        params.setMaxRollWidth(4400);
        params.setStepSize(10);
        params.setTotalWidth(4600);
        params.setTotalOverCap(0);
        params.setMaxPatterns(800);
        params.setMaxDistinctWidths(5);
        params.setUseOptimizedAssignment(true);
        params.sanitize();
        return params;
    }

    private static List<SolverOrderItem> loadT42Items() throws Exception {
        InputStream input = Djx188ResidualBundlePricingExperimentTest.class
                .getResourceAsStream("/t42djx250.csv");
        if (input == null) {
            throw new IllegalStateException(
                    "t42djx250.csv not found on test classpath");
        }
        List<SolverOrderItem> items = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                SolverOrderItem item = new SolverOrderItem();
                item.setMessageText(fields[0]);
                item.setWidth(Integer.parseInt(fields[1]));
                item.setDemand(Integer.parseInt(fields[2]));
                item.setLength(Integer.parseInt(fields[3]));
                item.setSurfaceTreatment(fields[4]);
                item.setGroupKey(fields[3] + "m+" + fields[4]);
                items.add(item);
            }
        }
        return items;
    }
}
