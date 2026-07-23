package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnCandidateAuditTracker.CandidateExplanation;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Result;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in audit that explains every known 29-group witness column without
 * feeding witness signatures into the autonomous solve.
 */
class Djx188OrderGroupMissingColumnAuditTest {

    private static final String ENABLE_PROPERTY =
            "cutting.test.orderGroupMissingColumnAudit";

    @Test
    void everyKnown29GroupColumnGetsOneDeterministicLossStage() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean(ENABLE_PROPERTY),
                "enable with -D" + ENABLE_PROPERTY + "=true");

        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe =
                new CompletePatternEnumerator(params, 5)
                        .generate(aggregateWidthDemand(items));
        assertEquals(7_717, universe.size(), "complete universe drifted");

        Options autonomousOptions = experimentOptions(true);
        Input autonomousInput =
                new Input(items, universe, params, 169, 36_870, 1, 0);
        Result autonomous =
                OrderGroupColumnPricingPrototype.solve(
                        autonomousInput, autonomousOptions);
        verifyExact(autonomousInput, autonomous);

        List<PatternCandidate> automaticSupport =
                Djx188ProductionNeutralFixture.loadAutomatic22().keySet().stream()
                        .sorted(java.util.Comparator.comparing(
                                PatternCandidate::signature))
                        .toList();
        Input witnessInput =
                new Input(items, automaticSupport, params, 169, 36_870, 1, 0);
        Result witness =
                OrderGroupColumnPricingPrototype.solve(
                        witnessInput, experimentOptions(false));
        verifyExact(witnessInput, witness);

        long auditBudgetMs = Long.getLong(
                "cutting.test.orderGroupMissingColumnAudit.auditMs", 5_000L);
        OrderGroupMissingColumnAudit.AuditResult audit =
                OrderGroupMissingColumnAudit.audit(
                        autonomousInput,
                        autonomousOptions,
                        autonomous,
                        witness,
                        auditBudgetMs);
        print(autonomous, witness, audit);

        assertEquals(31, autonomous.directMetrics().groups());
        assertEquals(29, witness.directMetrics().groups());
        assertEquals(
                OrderGroupMissingColumnAudit.AuditStatus.COMPLETE,
                audit.status(),
                audit::detail);
        assertEquals(29, audit.columns().size());
        assertEquals(
                29,
                audit.counts().values().stream()
                        .mapToInt(Integer::intValue)
                        .sum());
        assertTrue(audit.exchange().conserved());
        assertEquals(-2, audit.exchange().groupDelta());
    }

    private static void print(
            Result autonomous,
            Result witness,
            OrderGroupMissingColumnAudit.AuditResult audit) {
        System.out.printf(
                "DJX188 MISSING_COLUMN_AUDIT: status=%s recommendation=%s "
                        + "autonomous=%d witness=%d lp=%.6f counts=%s "
                        + "exchange=%d conserved=%s nonnegativeAdded=%d "
                        + "lpMs=%d replayMs=%d totalMs=%d "
                        + "scanned=%d priced=%d deadline=%s detail=%s%n",
                audit.status(),
                audit.recommendation(),
                autonomous.directMetrics().groups(),
                witness.directMetrics().groups(),
                audit.optimizationLpObjective(),
                audit.counts(),
                audit.exchange().groupDelta(),
                audit.exchange().conserved(),
                audit.exchange().nonnegativeAddedColumns(),
                audit.lpMs(),
                audit.replayMs(),
                audit.totalMs(),
                audit.replayDiagnostics().scannedPatterns(),
                audit.replayDiagnostics().pricedCandidates(),
                audit.replayDiagnostics().deadlineReached(),
                audit.detail());
        for (CandidateExplanation explanation : audit.columns()) {
            System.out.printf(
                    "  stage=%s rc=%.6f rank=%d/%d locus=%s signature=%s%n",
                    explanation.stage(),
                    explanation.rawReducedCost(),
                    explanation.rank(),
                    explanation.limit(),
                    explanation.locus(),
                    explanation.signature());
        }
    }

    private static void verifyExact(Input input, Result result) {
        assertTrue(result.feasible(), () ->
                "result status=" + result.status()
                        + ", pricing=" + result.pricingTermination());
        Map<DemandKey, Integer> produced = new TreeMap<>();
        for (GroupColumn column : result.selectedColumns()) {
            column.coverage().forEach((key, value) ->
                    produced.merge(key, value, Math::addExact));
        }
        assertEquals(input.demand(), produced);
        assertEquals(input.exactCars(), result.directMetrics().cars());
        assertEquals(input.exactWaste(), result.directMetrics().waste());
        assertEquals(input.exactOddGroups(), result.directMetrics().oddGroups());
        assertEquals(
                input.exactOneCarGroups(),
                result.directMetrics().oneCarGroups());
        assertTrue(result.semanticConsistent());
        assertEquals(result.directMetrics(), result.displayedMetrics());
    }

    private static Map<Integer, Integer> aggregateWidthDemand(
            List<SolverOrderItem> items) {
        Map<Integer, Integer> demand = new TreeMap<>();
        for (SolverOrderItem item : items) {
            demand.merge(item.getWidth(), item.getDemand(), Math::addExact);
        }
        return demand;
    }

    private static Options experimentOptions(boolean automaticIncumbentSeed) {
        Options defaults = Options.defaults();
        return new Options(
                Integer.getInteger(
                        "cutting.test.orderGroupPricing.iterations",
                        defaults.maxIterations()),
                Integer.getInteger(
                        "cutting.test.orderGroupPricing.maxColumns",
                        defaults.maxColumns()),
                Integer.getInteger(
                        "cutting.test.orderGroupPricing.addPerIteration",
                        defaults.maxAddedPerIteration()),
                Integer.getInteger(
                        "cutting.test.orderGroupPricing.perPatternPerIteration",
                        defaults.maxPerPatternPerIteration()),
                Integer.getInteger(
                        "cutting.test.orderGroupPricing.localConfigs",
                        defaults.maxLocalConfigsPerWidth()),
                Integer.getInteger(
                        "cutting.test.orderGroupPricing.beamWidth",
                        defaults.beamWidth()),
                Integer.getInteger(
                        "cutting.test.orderGroupPricing.columnsPerPattern",
                        defaults.maxColumnsPerPattern()),
                Integer.getInteger(
                        "cutting.test.orderGroupPricing.carCandidates",
                        defaults.maxCarCandidatesPerPattern()),
                Long.getLong(
                        "cutting.test.orderGroupPricing.pricingMs",
                        defaults.pricingTimeLimitMs()),
                Long.getLong(
                        "cutting.test.orderGroupPricing.completionMs",
                        defaults.integerCompletionTimeLimitMs()),
                Integer.getInteger(
                        "cutting.test.orderGroupPricing.repairCandidates",
                        defaults.integerRepairCandidates()),
                Long.getLong(
                        "cutting.test.orderGroupPricing.repairMs",
                        defaults.integerRepairTimeLimitMs()),
                defaults.lpTimeLimitMs(),
                Long.getLong(
                        "cutting.test.orderGroupPricing.integerMs",
                        defaults.integerTimeLimitMs()),
                defaults.dualAlpha(),
                defaults.reducedCostEpsilon(),
                defaults.artificialEpsilon(),
                defaults.noColumnPatience(),
                automaticIncumbentSeed,
                Long.getLong(
                        "cutting.test.orderGroupPricing.patternSeedMs",
                        defaults.patternSeedTimeLimitMs()));
    }
}
