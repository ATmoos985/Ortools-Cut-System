package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Result;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.colgen.ColumnGenerationSolver;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.generator.NewSolver.pattern.PatternGenerator;
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
 * Opt-in architecture experiment. Enable with
 * {@code -Dcutting.test.orderGroupPricing=true}.
 */
class Djx188OrderGroupColumnPricingExperimentTest {

    private static final String ENABLE_PROPERTY = "cutting.test.orderGroupPricing";

    @Test
    void djx188UsesOrderLevelGroupColumnsWithoutManualPatternHints() throws Exception {
        assumeEnabled();
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> universe = enumerate(items, params, 5);
        assertEquals(7_717, universe.size(), "DJX188 complete skeleton universe drifted");

        Input input = new Input(items, universe, params, 169, 36_870, 1, 0);
        Result result = OrderGroupColumnPricingPrototype.solve(input, experimentOptions(true));
        print("DJX188", universe.size(), result);
        printIntegerProfileIfNeeded("DJX188", input, result);

        assertTrue(result.feasible(), () -> failureMessage("DJX188", result));
        verifyExact(input, result);
    }

    @Test
    void automatic22SupportDiagnosesOrderConfigurationCoverage() throws Exception {
        assumeEnabled();
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> automaticSupport =
                Djx188ProductionNeutralFixture.loadAutomatic22().keySet().stream()
                        .sorted(java.util.Comparator.comparing(
                                PatternCandidate::signature))
                        .toList();

        Input input = new Input(
                items, automaticSupport, params, 169, 36_870, 1, 0);
        Result result = OrderGroupColumnPricingPrototype.solve(
                input, experimentOptions(false));
        print("DJX188_AUTOMATIC22_SUPPORT", automaticSupport.size(), result);
        printIntegerProfileIfNeeded(
                "DJX188_AUTOMATIC22_SUPPORT", input, result);

        assertTrue(result.feasible(), () ->
                failureMessage("DJX188_AUTOMATIC22_SUPPORT", result));
        verifyExact(input, result);
    }

    @Test
    void djx188ProductionSkeletonPoolUsesOrderLevelGroupColumns() throws Exception {
        assumeEnabled();
        Loader.loadNativeLibraries();
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        Map<Integer, Integer> widthDemand = aggregateWidthDemand(items);
        List<PatternCandidate> universe = new ColumnGenerationSolver(params).solve(
                new PatternGenerator(params).generate(widthDemand),
                widthDemand,
                java.util.Set.of());

        Input input = new Input(items, universe, params, 169, 36_870, 1, 0);
        Result result = OrderGroupColumnPricingPrototype.solve(
                input, experimentOptions(true));
        print("DJX188_PRODUCTION_SKELETON_POOL", universe.size(), result);

        assertTrue(result.feasible(), () ->
                failureMessage("DJX188_PRODUCTION_SKELETON_POOL", result));
        verifyExact(input, result);
    }

    @Test
    void t42UsesItsOwnCompletePatternUniverse() throws Exception {
        assumeEnabled();
        List<SolverOrderItem> items = loadT42Items();
        SolverParameters params = parameters(5);
        List<PatternCandidate> universe = enumerate(items, params, 5);

        Input input = new Input(items, universe, params, 45, 10_820, 1, 0);
        Result result = OrderGroupColumnPricingPrototype.solve(input, experimentOptions(true));
        print("T42", universe.size(), result);
        printIntegerProfileIfNeeded("T42", input, result);

        assertTrue(result.feasible(), () -> failureMessage("T42", result));
        verifyExact(input, result);
    }

    private static Options experimentOptions(boolean automaticIncumbentSeed) {
        Options defaults = Options.defaults();
        return new Options(
                Integer.getInteger("cutting.test.orderGroupPricing.iterations",
                        defaults.maxIterations()),
                Integer.getInteger("cutting.test.orderGroupPricing.maxColumns",
                        defaults.maxColumns()),
                Integer.getInteger("cutting.test.orderGroupPricing.addPerIteration",
                        defaults.maxAddedPerIteration()),
                Integer.getInteger("cutting.test.orderGroupPricing.perPatternPerIteration",
                        defaults.maxPerPatternPerIteration()),
                Integer.getInteger("cutting.test.orderGroupPricing.localConfigs",
                        defaults.maxLocalConfigsPerWidth()),
                Integer.getInteger("cutting.test.orderGroupPricing.beamWidth",
                        defaults.beamWidth()),
                Integer.getInteger("cutting.test.orderGroupPricing.columnsPerPattern",
                        defaults.maxColumnsPerPattern()),
                Integer.getInteger("cutting.test.orderGroupPricing.carCandidates",
                        defaults.maxCarCandidatesPerPattern()),
                Long.getLong("cutting.test.orderGroupPricing.pricingMs",
                        defaults.pricingTimeLimitMs()),
                Long.getLong("cutting.test.orderGroupPricing.completionMs",
                        defaults.integerCompletionTimeLimitMs()),
                Integer.getInteger("cutting.test.orderGroupPricing.repairCandidates",
                        defaults.integerRepairCandidates()),
                Long.getLong("cutting.test.orderGroupPricing.repairMs",
                        defaults.integerRepairTimeLimitMs()),
                defaults.lpTimeLimitMs(),
                Long.getLong("cutting.test.orderGroupPricing.integerMs",
                        defaults.integerTimeLimitMs()),
                defaults.dualAlpha(),
                defaults.reducedCostEpsilon(),
                defaults.artificialEpsilon(),
                defaults.noColumnPatience(),
                automaticIncumbentSeed,
                Long.getLong("cutting.test.orderGroupPricing.patternSeedMs",
                        defaults.patternSeedTimeLimitMs()));
    }

    private static void verifyExact(Input input, Result result) {
        Map<DemandKey, Integer> produced = new TreeMap<>();
        for (GroupColumn column : result.selectedColumns()) {
            column.coverage().forEach(
                    (key, value) -> produced.merge(key, value, Math::addExact));
        }
        assertEquals(input.demand(), produced);
        assertEquals(input.exactCars(), result.directMetrics().cars());
        assertEquals(input.exactWaste(), result.directMetrics().waste());
        assertEquals(input.exactOddGroups(), result.directMetrics().oddGroups());
        assertEquals(input.exactOneCarGroups(), result.directMetrics().oneCarGroups());
        assertTrue(result.semanticConsistent());
        assertEquals(result.directMetrics(), result.displayedMetrics());
    }

    private static List<PatternCandidate> enumerate(
            List<SolverOrderItem> items,
            SolverParameters params,
            int maxDistinct) {
        Map<Integer, Integer> widthDemand = new TreeMap<>();
        for (SolverOrderItem item : items) {
            widthDemand.merge(item.getWidth(), item.getDemand(), Math::addExact);
        }
        return new CompletePatternEnumerator(params, maxDistinct).generate(widthDemand);
    }

    private static Map<Integer, Integer> aggregateWidthDemand(
            List<SolverOrderItem> items) {
        Map<Integer, Integer> widthDemand = new TreeMap<>();
        for (SolverOrderItem item : items) {
            widthDemand.merge(item.getWidth(), item.getDemand(), Math::addExact);
        }
        return widthDemand;
    }

    private static void print(String dataset, int universeSize, Result result) {
        System.out.printf(
                "%s ORDER_GROUP_PRICING: status=%s pricingStop=%s "
                        + "universe=%d pool=%d selected=%d iterations=%d "
                        + "groups=%d odd=%d one=%d small=%d cars=%d waste=%d "
                        + "displayed=%s semantic=%s artificial=%.6f "
                        + "lpObj=%.6f mip=%s mipObj=%.6f nodes=%d "
                        + "repair=%s repairCandidates=%d repairSelected=%d "
                        + "repairArtificial=%.6f repairMs=%d "
                        + "seed=%s seedPatterns=%d seedGroups=%d seedMs=%d "
                        + "lpMs=%d pricingMs=%d integerMs=%d totalMs=%d "
                        + "scanned=%d priced=%d negative=%d returned=%d "
                        + "deadline=%s%n",
                dataset,
                result.status(),
                result.pricingTermination(),
                universeSize,
                result.pool().size(),
                result.selectedColumns().size(),
                result.iterations(),
                result.directMetrics().groups(),
                result.directMetrics().oddGroups(),
                result.directMetrics().oneCarGroups(),
                result.directMetrics().smallGroups(),
                result.directMetrics().cars(),
                result.directMetrics().waste(),
                result.displayedMetrics(),
                result.semanticConsistent(),
                result.maxArtificial(),
                result.finalLpObjective(),
                result.integerStatus(),
                result.integerObjective(),
                result.integerNodes(),
                result.repairStatus(),
                result.repairCandidateCount(),
                result.repairSelectedCount(),
                result.repairMaxArtificial(),
                result.repairMs(),
                result.seed().status(),
                result.seed().supportPatterns(),
                result.seed().groups(),
                result.seed().totalMs(),
                result.lpMs(),
                result.pricingMs(),
                result.integerMs(),
                result.totalMs(),
                result.pricingDiagnostics().scannedPatterns(),
                result.pricingDiagnostics().pricedCandidates(),
                result.pricingDiagnostics().negativeReducedCostColumns(),
                result.pricingDiagnostics().returnedColumns(),
                result.pricingDiagnostics().deadlineReached());
        result.traces().forEach(trace -> System.out.printf(
                "  iter=%d phase=%s pool=%d added=%d lp=%.6f art=%.6f "
                        + "dualL1=%.6f rc=[%.6f,%.6f]%n",
                trace.iteration(),
                trace.phase(),
                trace.poolSize(),
                trace.addedColumns(),
                trace.lpObjective(),
                trace.maxArtificial(),
                trace.dualL1(),
                trace.bestRawReducedCost(),
                trace.worstRawReducedCost()));
    }

    private static void printIntegerProfileIfNeeded(
            String dataset, Input input, Result result) {
        if (result.integerStatus()
                != com.google.ortools.linearsolver.MPSolver.ResultStatus.INFEASIBLE) {
            return;
        }
        OrderGroupRestrictedMaster.IntegerFeasibilityProfile profile =
                OrderGroupRestrictedMaster.diagnoseIntegerFeasibility(
                        input, experimentOptions(false), result.pool());
        System.out.printf(
                "%s INTEGER_PROFILE: productionOnly=%s withOdd=%s fullShape=%s%n",
                dataset,
                profile.productionOnly(),
                profile.withOdd(),
                profile.withFullShape());
    }

    private static String failureMessage(String dataset, Result result) {
        return dataset + " failed: status=" + result.status()
                + ", pricingStop=" + result.pricingTermination()
                + ", pool=" + result.pool().size()
                + ", iterations=" + result.iterations()
                + ", artificial=" + result.maxArtificial()
                + ", pricing=" + result.pricingDiagnostics();
    }

    private static void assumeEnabled() {
        Assumptions.assumeTrue(
                Boolean.getBoolean(ENABLE_PROPERTY),
                "enable with -D" + ENABLE_PROPERTY + "=true");
    }

    private static SolverParameters parameters(int maxDistinct) {
        SolverParameters params = SolverParameters.createDefault();
        params.setMinRollWidth(4300);
        params.setMaxRollWidth(4400);
        params.setStepSize(10);
        params.setTotalWidth(4600);
        params.setTotalOverCap(0);
        params.setMaxPatterns(800);
        params.setMaxDistinctWidths(maxDistinct);
        params.setUseOptimizedAssignment(true);
        params.sanitize();
        return params;
    }

    private static List<SolverOrderItem> loadT42Items() throws Exception {
        InputStream input = Djx188OrderGroupColumnPricingExperimentTest.class
                .getResourceAsStream("/t42djx250.csv");
        if (input == null) {
            throw new IllegalStateException("t42djx250.csv not found on test classpath");
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
