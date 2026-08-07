package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.DemandKey;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.GroupColumn;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Input;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Options;
import test.demo.apsmodule.generator.NewSolver.OrderGroupColumnPricingPrototype.Result;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.pattern.CompletePatternEnumerator;
import test.demo.apsmodule.generator.NewSolver.pattern.PatternGenerator;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Research-only compact candidate-pool experiment. */
class Djx188CompactOrderPatternPricingExperimentTest {

    @Test
    void compactPoolKeepsBurdenSmallAndGuidedSupportRestoresExactSolve()
            throws Exception {
        Loader.loadNativeLibraries();
        List<SolverOrderItem> items = Djx188ManualBaselineFixture.loadItems();
        Map<Integer, Integer> demands = Djx188ManualBaselineFixture.demands(items);
        SolverParameters params = Djx188ManualBaselineFixture.parameters();
        List<PatternCandidate> production =
                new PatternGenerator(params).generate(demands);
        List<PatternCandidate> complete =
                new CompletePatternEnumerator(params, 5).generate(demands);
        List<PatternCandidate> compact =
                OrderGroupCompactPatternPoolBuilder.build(
                        production, complete, demands, params, 1_100);

        Map<String, PatternCandidate> guidedBySignature = new LinkedHashMap<>();
        Djx188ProductionNeutralFixture.loadAutomatic22().keySet().forEach(
                pattern -> guidedBySignature.putIfAbsent(pattern.signature(), pattern));
        List<PatternCandidate> guided = List.copyOf(guidedBySignature.values());
        long supportInCompact = guided.stream()
                .filter(pattern -> compact.stream().anyMatch(
                        candidate -> candidate.signature().equals(pattern.signature())))
                .count();
        List<String> missingSupport = guided.stream()
                .filter(pattern -> compact.stream().noneMatch(
                        candidate -> candidate.signature().equals(pattern.signature())))
                .map(PatternCandidate::signature)
                .toList();

        Input input = new Input(items, guided, params, 169, 36_870, 1, 0);
        Result result = OrderGroupColumnPricingPrototype.solve(
                input, Options.defaults().withoutAutomaticIncumbentSeed());

        System.out.printf(
                "DJX188 compact order pricing: complete=%d production=%d "
                        + "compact=%d guided=%d status=%s pool=%d selected=%d "
                        + "groups=%d odd=%d one=%d cars=%d waste=%d "
                        + "totalMs=%d pricingMs=%d integerMs=%d%n",
                complete.size(), production.size(), compact.size(), guided.size(),
                result.status(),
                result.pool().size(), result.selectedColumns().size(),
                result.directMetrics().groups(), result.directMetrics().oddGroups(),
                result.directMetrics().oneCarGroups(), result.directMetrics().cars(),
                result.directMetrics().waste(), result.totalMs(), result.pricingMs(),
                result.integerMs());
        System.out.printf("DJX188 compact support coverage: guided=%d inCompact=%d%n",
                guided.size(), supportInCompact);
        System.out.println("DJX188 compact support missing=" + missingSupport);

        assertTrue(compact.size() <= 1_100,
                "compact pool must stay bounded");
        assertTrue(result.feasible(), () ->
                "compact order pricing failed: status=" + result.status()
                        + ", pricing=" + result.pricingTermination()
                        + ", pool=" + result.pool().size());
        verifyExact(input, result);
    }

    private static void verifyExact(Input input, Result result) {
        Map<DemandKey, Integer> produced = new TreeMap<>();
        for (GroupColumn column : result.selectedColumns()) {
            column.coverage().forEach((key, value) ->
                    produced.merge(key, value, Math::addExact));
        }
        assertEquals(input.demand(), produced);
        assertEquals(input.exactCars(), result.directMetrics().cars());
        assertEquals(input.exactWaste(), result.directMetrics().waste());
        assertEquals(input.exactOddGroups(), result.directMetrics().oddGroups());
        assertEquals(input.exactOneCarGroups(), result.directMetrics().oneCarGroups());
        assertTrue(result.semanticConsistent());
        assertEquals(result.directMetrics(), result.displayedMetrics());
    }
}
