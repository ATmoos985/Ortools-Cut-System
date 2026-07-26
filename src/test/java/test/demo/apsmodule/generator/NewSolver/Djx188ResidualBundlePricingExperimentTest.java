package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Assumptions;
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
 */
class Djx188ResidualBundlePricingExperimentTest {

    private static final String BUNDLE_PROPERTY =
            "cutting.test.residualBundlePricing";
    private static final String CAP_PROPERTY =
            "cutting.test.localConfigCapAudit";

    @Test
    void residualBundlePricingOnAutonomousIncumbent() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean(BUNDLE_PROPERTY),
                "enable with -D" + BUNDLE_PROPERTY + "=true");

        Setup setup = autonomousSetup(Options.defaults().maxLocalConfigsPerWidth());
        assertEquals(31, setup.autonomous().directMetrics().groups(),
                "autonomous baseline drifted");

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
                        setup.input(),
                        setup.autonomous().selectedColumns(),
                        bundleOptions);

        System.out.printf(
                "DJX188 RESIDUAL_BUNDLE: baseline=31 groups=%d odd=%d one=%d "
                        + "cars=%d waste=%d swaps=%d scanned=%d truncated=%d "
                        + "budgetExhausted=%s elapsedMs=%d options=%s%n",
                improved.metrics().groups(),
                improved.metrics().oddGroups(),
                improved.metrics().oneCarGroups(),
                improved.metrics().cars(),
                improved.metrics().waste(),
                improved.swapsApplied(),
                improved.bundlesScanned(),
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

        verifyExact(setup.input(), improved.columns());
        ConvertedOutput converted = OrderGroupColumnPricingEngine.convertSelected(
                setup.input(), improved.columns());
        SequenceGroupPostProcessor.GroupStats displayed =
                SequenceGroupPostProcessor.computeGroupStats(
                        converted.instructions());
        assertEquals(improved.metrics().groups(), displayed.groups());
        assertEquals(improved.metrics().oddGroups(), displayed.oddCarGroups());
        assertEquals(improved.metrics().oneCarGroups(), displayed.oneCarGroups());

        assertTrue(improved.metrics().groups() <= 31);
        if (improved.swapsApplied() > 0) {
            assertTrue(improved.metrics().groups() < 31,
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

    private record Setup(
            List<SolverOrderItem> items,
            SolverParameters params,
            Input input,
            Options autonomousOptions,
            Result autonomous) {
    }

    private static Setup autonomousSetup(int localConfigsPerWidth)
            throws Exception {
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe =
                new CompletePatternEnumerator(params, 5)
                        .generate(aggregateWidthDemand(items));
        assertEquals(7_717, universe.size(), "complete universe drifted");

        Options autonomousOptions = options(localConfigsPerWidth, true);
        Input input = new Input(items, universe, params, 169, 36_870, 1, 0);
        Result autonomous =
                OrderGroupColumnPricingPrototype.solve(input, autonomousOptions);
        assertTrue(autonomous.feasible(), () ->
                "autonomous status=" + autonomous.status()
                        + ", pricing=" + autonomous.pricingTermination());
        verifyExact(input, autonomous.selectedColumns());
        return new Setup(items, params, input, autonomousOptions, autonomous);
    }

    private static Options options(
            int localConfigsPerWidth, boolean automaticIncumbentSeed) {
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
                defaults.pricingTimeLimitMs(),
                defaults.integerCompletionTimeLimitMs(),
                defaults.integerRepairCandidates(),
                defaults.integerRepairTimeLimitMs(),
                defaults.lpTimeLimitMs(),
                defaults.integerTimeLimitMs(),
                defaults.dualAlpha(),
                defaults.reducedCostEpsilon(),
                defaults.artificialEpsilon(),
                defaults.noColumnPatience(),
                automaticIncumbentSeed,
                defaults.patternSeedTimeLimitMs());
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
}
