package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredLocalPatternUniverseBuilderTest {

    @BeforeAll
    static void loadNativeLibraries() {
        Loader.loadNativeLibraries();
    }

    @Test
    void buildsNestedEvidenceBackedUniversesAndOneHopTransfer() {
        Fixture fixture = fixture(() -> 0L);

        StructuredLocalPatternUniverseBuilder.BuildResult result =
                StructuredLocalPatternUniverseBuilder.build(
                        fixture.universe(),
                        fixture.current(),
                        fixture.upperBounds(),
                        fixture.analysis(),
                        fixture.options());

        StructuredLocalPatternUniverseBuilder.LocalPatternUniverse direct =
                result.layer(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.WITNESS_DIRECT);
        StructuredLocalPatternUniverseBuilder.LocalPatternUniverse neutral =
                result.layer(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.NEUTRAL_CLOSURE);
        StructuredLocalPatternUniverseBuilder.LocalPatternUniverse transfer =
                result.layer(StructuredLocalPatternUniverseBuilder
                        .UniverseScope.TRANSFER_BRIDGE_1_HOP);

        assertEquals(
                StructuredLocalPatternUniverseBuilder.UniverseBuildStatus
                        .EXHAUSTED_WITHIN_RULE,
                direct.buildStatus());
        assertEquals(
                StructuredLocalPatternUniverseBuilder.UniverseBuildStatus
                        .EXHAUSTED_WITHIN_RULE,
                neutral.buildStatus());
        assertEquals(
                StructuredLocalPatternUniverseBuilder.UniverseBuildStatus
                        .EXHAUSTED_WITHIN_RULE,
                transfer.buildStatus());

        Set<String> directSignatures = signatures(direct.patterns());
        Set<String> neutralSignatures = signatures(neutral.patterns());
        Set<String> transferSignatures = signatures(transfer.patterns());
        assertTrue(neutralSignatures.containsAll(directSignatures));
        assertTrue(transferSignatures.containsAll(neutralSignatures));
        assertTrue(directSignatures.contains(fixture.directRepair().signature()));
        assertFalse(neutralSignatures.contains(fixture.bridgeTarget().signature()));
        assertTrue(transferSignatures.contains(fixture.bridgeTarget().signature()));

        List<StructuredLocalPatternUniverseBuilder.InclusionEvidence>
                bridgeEvidence = transfer.evidenceFor(
                        fixture.bridgeTarget().signature()).stream()
                        .filter(evidence -> evidence.type()
                                == StructuredLocalPatternUniverseBuilder
                                .EvidenceType.TRANSFER_BRIDGE)
                        .toList();
        assertFalse(bridgeEvidence.isEmpty());
        StructuredLocalPatternUniverseBuilder.TransferBridgeEvidence bridge =
                (StructuredLocalPatternUniverseBuilder.TransferBridgeEvidence)
                        bridgeEvidence.get(0);
        assertEquals(10, bridge.conflictWidth());
        assertEquals(20, bridge.bridgeWidth());
        assertEquals(30, bridge.newWidth());
        assertEquals(1, bridge.hopCount());
        assertTrue(bridge.secondMaxScale() >= bridge.secondScale());
        assertEquals(
                List.of(
                        "W:10",
                        "P:" + fixture.directRepair().signature(),
                        "W:20",
                        "P:" + fixture.bridgeTarget().signature(),
                        "W:30"),
                bridge.pathNodes());

        assertEquals(
                bruteForceUnitDeltas(fixture),
                result.baselineUnitEquations().stream()
                        .map(equation -> equation.unitDelta().toString())
                        .collect(TreeSet::new, Set::add, Set::addAll));
        result.baselineUnitEquations().forEach(equation ->
                assertNeutralEquation(equation, fixture));
        result.eligibleBridgeEquations().forEach(equation ->
                assertNeutralEquation(equation, fixture));

        assertEquals(1, result.metrics().directRepairPatterns());
        assertEquals(3, result.metrics().transferSourcePairs());
        assertTrue(result.metrics().transferComplementScans() > 0);
        assertTrue(result.metrics().eligibleBridgeEquations() > 0);

        assertEquals(2,
                direct.coverage().residualTargetCoverage().denominator());
        assertEquals(2,
                direct.coverage().residualTargetCoverage().numerator());
        assertFalse(neutral.coverage().bridgeEquationCoverage().applicable());
        assertTrue(transfer.coverage().bridgeEquationCoverage().applicable());
        assertTrue(transfer.coverage().bridgeEquationCoverage()
                .denominatorComplete());
        assertTrue(transfer.coverage().uncoveredResidualTargets().isEmpty());

        result.layers().values().forEach(layer -> layer.patterns().forEach(pattern ->
                assertFalse(layer.evidenceFor(pattern.signature()).isEmpty())));
    }

    @Test
    void residualTargetSignatureIsIdenticalAcrossConstructionPaths() {
        Fixture fixture = fixture(() -> 0L);
        OrderCompatibilityKernelAnalyzer.PatternSplit split =
                fixture.analysis().splitWitnesses().get(0);
        OrderCompatibilityKernelAnalyzer.ConfigUse configuration =
                split.configurations().get(0);

        StructuredLocalPatternUniverseBuilder.ResidualTarget fromWitness =
                StructuredLocalPatternUniverseBuilder.ResidualTarget.from(
                        split, configuration);
        StructuredLocalPatternUniverseBuilder.ResidualTarget fromFields =
                StructuredLocalPatternUniverseBuilder.ResidualTarget.fromValues(
                        split.patternSignature(),
                        configuration.configurationSignature(),
                        configuration.cars());

        assertEquals(fromWitness, fromFields);
        assertEquals(fromWitness.signature(), fromFields.signature());
    }

    @Test
    void deterministicPatternCapReturnsNestedCappedPrefixes() {
        Fixture fixture = fixture(() -> 0L);
        StructuredLocalPatternUniverseBuilder.Options capped =
                new StructuredLocalPatternUniverseBuilder.Options(
                        10_000L,
                        fixture.current().size(),
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        200,
                        10,
                        1_520,
                        () -> 0L);

        StructuredLocalPatternUniverseBuilder.BuildResult first =
                StructuredLocalPatternUniverseBuilder.build(
                        fixture.universe(), fixture.current(), fixture.upperBounds(),
                        fixture.analysis(), capped);
        StructuredLocalPatternUniverseBuilder.BuildResult second =
                StructuredLocalPatternUniverseBuilder.build(
                        fixture.universe(), fixture.current(), fixture.upperBounds(),
                        fixture.analysis(), capped);

        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            assertEquals(
                    StructuredLocalPatternUniverseBuilder.UniverseBuildStatus.CAPPED,
                    first.layer(scope).buildStatus());
            assertEquals(
                    signatures(first.layer(scope).patterns()),
                    signatures(second.layer(scope).patterns()));
            assertEquals(
                    first.layer(scope).evidenceByPattern(),
                    second.layer(scope).evidenceByPattern());
        }
    }

    @Test
    void injectedClockProducesTimedOutStablePrefixWithoutSleeping() {
        AtomicInteger calls = new AtomicInteger();
        LongSupplier timeoutClock = () -> calls.getAndIncrement() == 0 ? 0L : 100L;
        Fixture fixture = fixture(timeoutClock);
        StructuredLocalPatternUniverseBuilder.Options timed =
                new StructuredLocalPatternUniverseBuilder.Options(
                        50L,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        200,
                        10,
                        1_520,
                        timeoutClock);

        StructuredLocalPatternUniverseBuilder.BuildResult result =
                StructuredLocalPatternUniverseBuilder.build(
                        fixture.universe(), fixture.current(), fixture.upperBounds(),
                        fixture.analysis(), timed);

        Set<String> baseline = signatures(new ArrayList<>(fixture.current().keySet()));
        for (StructuredLocalPatternUniverseBuilder.UniverseScope scope
                : StructuredLocalPatternUniverseBuilder.UniverseScope.values()) {
            assertEquals(
                    StructuredLocalPatternUniverseBuilder.UniverseBuildStatus.TIMED_OUT,
                    result.layer(scope).buildStatus());
            assertEquals(baseline, signatures(result.layer(scope).patterns()));
        }
    }

    @Test
    void rejectsAnalysisWithoutExactGroupShapeProof() {
        Fixture fixture = fixture(() -> 0L);
        OrderCompatibilityKernelAnalyzer.Analysis invalid =
                analysis(split(fixture.witness()), false);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredLocalPatternUniverseBuilder.build(
                        fixture.universe(),
                        fixture.current(),
                        fixture.upperBounds(),
                        invalid,
                        fixture.options()));
        assertTrue(error.getMessage().contains("shape optimality"));
    }

    private static Fixture fixture(LongSupplier clock) {
        PatternCandidate witness = pattern(200, 10, 1, 20, 1);
        PatternCandidate baselineTwo = pattern(200, 10, 1, 40, 1);
        PatternCandidate baselineThree = pattern(200, 30, 1, 50, 1);
        PatternCandidate directRepair = pattern(200, 10, 2, 20, 1);
        PatternCandidate directBalance = pattern(200, 40, 1);
        PatternCandidate bridgeTarget = pattern(200, 20, 1, 30, 1);
        PatternCandidate bridgeBalance = pattern(200, 10, 2, 50, 1);
        List<PatternCandidate> universe = List.of(
                bridgeBalance,
                witness,
                directBalance,
                baselineThree,
                bridgeTarget,
                baselineTwo,
                directRepair);
        Map<PatternCandidate, Integer> current = new LinkedHashMap<>();
        current.put(witness, 4);
        current.put(baselineTwo, 4);
        current.put(baselineThree, 2);
        Map<PatternCandidate, Integer> upper = new LinkedHashMap<>();
        upper.put(witness, 4);
        upper.put(baselineTwo, 4);
        upper.put(baselineThree, 2);
        upper.put(directRepair, 2);
        upper.put(directBalance, 2);
        upper.put(bridgeTarget, 2);
        upper.put(bridgeBalance, 2);

        OrderCompatibilityKernelAnalyzer.PatternSplit split = split(witness);
        return new Fixture(
                universe,
                current,
                upper,
                analysis(split, true),
                new StructuredLocalPatternUniverseBuilder.Options(
                        10_000L,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        200,
                        10,
                        1_520,
                        clock),
                witness,
                directRepair,
                bridgeTarget);
    }

    private static OrderCompatibilityKernelAnalyzer.PatternSplit split(
            PatternCandidate witness) {
        OrderCompatibilityKernelAnalyzer.ConfigUse first =
                new OrderCompatibilityKernelAnalyzer.ConfigUse(
                        2,
                        Map.of(10, List.of("A"), 20, List.of("H")),
                        "10=A|20=H");
        OrderCompatibilityKernelAnalyzer.ConfigUse second =
                new OrderCompatibilityKernelAnalyzer.ConfigUse(
                        2,
                        Map.of(10, List.of("B"), 20, List.of("H")),
                        "10=B|20=H");
        return new OrderCompatibilityKernelAnalyzer.PatternSplit(
                witness.signature(),
                4,
                List.of(first, second),
                Set.of(10),
                Map.of(10, List.of("A", "B")));
    }

    private static OrderCompatibilityKernelAnalyzer.Analysis analysis(
            OrderCompatibilityKernelAnalyzer.PatternSplit split,
            boolean shapeOptimal) {
        return new OrderCompatibilityKernelAnalyzer.Analysis(
                OrderCompatibilityKernelAnalyzer.Status.OPTIMAL,
                "TEST",
                MPSolver.ResultStatus.OPTIMAL,
                MPSolver.ResultStatus.OPTIMAL,
                shapeOptimal
                        ? MPSolver.ResultStatus.OPTIMAL
                        : MPSolver.ResultStatus.NOT_SOLVED,
                true,
                shapeOptimal,
                3,
                4,
                4,
                4,
                1,
                1,
                0,
                2,
                0,
                0,
                0L,
                4.0,
                0.0,
                0L,
                0L,
                0L,
                0L,
                List.of(split));
    }

    private static Set<String> bruteForceUnitDeltas(Fixture fixture) {
        List<PatternCandidate> universe = fixture.universe().stream()
                .sorted(Comparator.comparing(PatternCandidate::signature))
                .toList();
        List<PatternCandidate> support = fixture.current().keySet().stream()
                .sorted(Comparator.comparing(PatternCandidate::signature))
                .toList();
        Set<String> deltas = new TreeSet<>();
        for (int sourceLeft = 0; sourceLeft < support.size(); sourceLeft++) {
            for (int sourceRight = sourceLeft;
                    sourceRight < support.size(); sourceRight++) {
                PatternCandidate left = support.get(sourceLeft);
                PatternCandidate right = support.get(sourceRight);
                int required = left.equals(right) ? 2 : 1;
                if (fixture.current().get(left) < required) {
                    continue;
                }
                Map<Integer, Integer> sourceProduction = pairProduction(left, right);
                for (int targetLeft = 0;
                        targetLeft < universe.size(); targetLeft++) {
                    for (int targetRight = targetLeft;
                            targetRight < universe.size(); targetRight++) {
                        PatternCandidate first = universe.get(targetLeft);
                        PatternCandidate second = universe.get(targetRight);
                        if (!sourceProduction.equals(pairProduction(first, second))) {
                            continue;
                        }
                        Map<String, Integer> delta = new TreeMap<>();
                        delta.merge(left.signature(), -1, Integer::sum);
                        delta.merge(right.signature(), -1, Integer::sum);
                        delta.merge(first.signature(), 1, Integer::sum);
                        delta.merge(second.signature(), 1, Integer::sum);
                        delta.values().removeIf(value -> value == 0);
                        if (delta.isEmpty()) {
                            continue;
                        }
                        if (maxScale(delta, fixture) >= 1) {
                            deltas.add(delta.toString());
                        }
                    }
                }
            }
        }
        return deltas;
    }

    private static int maxScale(Map<String, Integer> delta, Fixture fixture) {
        Map<String, Integer> current = new TreeMap<>();
        fixture.current().forEach((pattern, usage) ->
                current.put(pattern.signature(), usage));
        Map<String, Integer> upper = new TreeMap<>();
        fixture.upperBounds().forEach((pattern, value) ->
                upper.put(pattern.signature(), value));
        int max = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : delta.entrySet()) {
            int usage = current.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() < 0) {
                max = Math.min(max, usage / -entry.getValue());
            } else {
                max = Math.min(
                        max,
                        (upper.get(entry.getKey()) - usage) / entry.getValue());
            }
        }
        return max;
    }

    private static Map<Integer, Integer> pairProduction(
            PatternCandidate left,
            PatternCandidate right) {
        Map<Integer, Integer> production = new TreeMap<>();
        left.getPattern().forEach((width, coefficient) ->
                production.merge(width, coefficient, Integer::sum));
        right.getPattern().forEach((width, coefficient) ->
                production.merge(width, coefficient, Integer::sum));
        return production;
    }

    private static void assertNeutralEquation(
            StructuredLocalPatternUniverseBuilder.NeutralEquation equation,
            Fixture fixture) {
        Map<String, PatternCandidate> bySignature = new TreeMap<>();
        fixture.universe().forEach(pattern ->
                bySignature.put(pattern.signature(), pattern));
        int cars = equation.unitDelta().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        int waste = 0;
        Map<Integer, Integer> production = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : equation.unitDelta().entrySet()) {
            PatternCandidate pattern = bySignature.get(entry.getKey());
            assertNotNull(pattern);
            waste += pattern.getRealWaste(200) * entry.getValue();
            pattern.getPattern().forEach((width, coefficient) ->
                    production.merge(
                            width, coefficient * entry.getValue(), Integer::sum));
        }
        production.values().removeIf(value -> value == 0);
        assertEquals(0, cars);
        assertEquals(0, waste);
        assertTrue(production.isEmpty());
    }

    private static Set<String> signatures(List<PatternCandidate> patterns) {
        Set<String> signatures = new TreeSet<>();
        patterns.forEach(pattern -> signatures.add(pattern.signature()));
        return signatures;
    }

    private static PatternCandidate pattern(int rollWidth, int... values) {
        Map<Integer, Integer> cuts = new TreeMap<>();
        for (int index = 0; index < values.length; index += 2) {
            cuts.put(values[index], values[index + 1]);
        }
        return new PatternCandidate(cuts, rollWidth);
    }

    private record Fixture(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upperBounds,
            OrderCompatibilityKernelAnalyzer.Analysis analysis,
            StructuredLocalPatternUniverseBuilder.Options options,
            PatternCandidate witness,
            PatternCandidate directRepair,
            PatternCandidate bridgeTarget) {
    }
}
