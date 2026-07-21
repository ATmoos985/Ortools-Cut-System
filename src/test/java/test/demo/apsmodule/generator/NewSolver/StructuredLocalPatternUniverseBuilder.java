package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * Research-only builder for the three nested DJX188 local pattern universes.
 *
 * <p>Every selected pattern carries structured evidence. Scope construction,
 * sparse-support generation and exact order evaluation deliberately remain
 * separate concerns.</p>
 */
final class StructuredLocalPatternUniverseBuilder {

    private static final Comparator<PatternCandidate> PATTERN_ORDER =
            Comparator.comparing(PatternCandidate::signature);
    private static final Comparator<ResidualTarget> RESIDUAL_ORDER =
            Comparator.comparing(ResidualTarget::signature);

    private StructuredLocalPatternUniverseBuilder() {
    }

    static BuildResult build(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upperBounds,
            OrderCompatibilityKernelAnalyzer.Analysis analysis,
            Options options) {
        Objects.requireNonNull(options, "options");
        Deadline deadline = new Deadline(options.clock(), options.timeLimitMs());
        InputData data = index(universe, current, upperBounds, analysis, options);

        LayerAccumulator direct = baselineLayer(data, analysis);
        if (direct.size() > options.maxPatterns()) {
            throw new IllegalArgumentException(
                    "maxPatterns is smaller than the required baseline support");
        }

        UnitTwoForTwoMoveEnumerator.Result unitResult =
                UnitTwoForTwoMoveEnumerator.enumerate(
                        data.patterns(), data.current(), data.upperBounds());
        if (!unitResult.exhausted()) {
            throw new IllegalStateException(
                    "unit 2-for-2 equation source did not exhaust its fixed scope");
        }
        List<NeutralEquation> allEquations = equations(unitResult, data, options);
        int equationLimit = Math.min(allEquations.size(), options.maxEquations());
        List<NeutralEquation> workingEquations =
                allEquations.subList(0, equationLimit);
        boolean equationCapped = equationLimit < allEquations.size();

        List<ResidualContext> residuals = residualContexts(analysis);
        UniverseBuildStatus directStatus = UniverseBuildStatus.EXHAUSTED_WITHIN_RULE;
        boolean stopped = false;
        for (ResidualContext context : residuals) {
            for (NeutralEquation equation : workingEquations) {
                if (deadline.expired()) {
                    directStatus = UniverseBuildStatus.TIMED_OUT;
                    stopped = true;
                    break;
                }
                int witnessDelta = equation.unitDelta()
                        .getOrDefault(context.target().splitPatternSignature(), 0);
                if (witnessDelta >= 0) {
                    continue;
                }
                int removedPerScale = -witnessDelta;
                if (context.target().configurationCars() % removedPerScale != 0) {
                    continue;
                }
                int scale = context.target().configurationCars() / removedPerScale;
                if (scale < 1 || scale > equation.maxScale()) {
                    continue;
                }
                Map<String, Integer> next = apply(
                        data.currentBySignature(), equation.unitDelta(), scale);
                validateState(next, data, options);
                ResidualComplementEvidence evidence =
                        residualEvidence(context, equation, scale);
                if (!direct.addEquation(
                        equation.patternSignatures(), evidence, options.maxPatterns())) {
                    directStatus = UniverseBuildStatus.CAPPED;
                    stopped = true;
                    break;
                }
            }
            if (stopped) {
                break;
            }
        }
        if (directStatus == UniverseBuildStatus.EXHAUSTED_WITHIN_RULE
                && equationCapped) {
            directStatus = UniverseBuildStatus.CAPPED;
        }

        LayerAccumulator neutral = new LayerAccumulator(direct);
        UniverseBuildStatus neutralStatus = directStatus;
        if (directStatus != UniverseBuildStatus.TIMED_OUT) {
            neutralStatus = UniverseBuildStatus.EXHAUSTED_WITHIN_RULE;
            for (NeutralEquation equation : workingEquations) {
                if (deadline.expired()) {
                    neutralStatus = UniverseBuildStatus.TIMED_OUT;
                    break;
                }
                NeutralEquationEvidence evidence = new NeutralEquationEvidence(
                        evidenceId("neutral", equation.signature()),
                        equation.signature(),
                        equation.removals(),
                        equation.additions(),
                        equation.unitDelta(),
                        equation.balancedWidths(),
                        UniverseScope.NEUTRAL_CLOSURE);
                if (!neutral.addEquation(
                        equation.patternSignatures(), evidence, options.maxPatterns())) {
                    neutralStatus = UniverseBuildStatus.CAPPED;
                    break;
                }
            }
            if (neutralStatus == UniverseBuildStatus.EXHAUSTED_WITHIN_RULE
                    && (equationCapped
                    || directStatus == UniverseBuildStatus.CAPPED)) {
                neutralStatus = UniverseBuildStatus.CAPPED;
            }
        }

        LayerAccumulator transfer = new LayerAccumulator(neutral);
        TransferBuild transferBuild = TransferBuild.empty();
        UniverseBuildStatus transferStatus = neutralStatus;
        if (neutralStatus == UniverseBuildStatus.EXHAUSTED_WITHIN_RULE) {
            transferBuild = buildTransferLayer(
                    transfer, direct, neutral, residuals, data, options, deadline);
            transferStatus = transferBuild.status();
        }

        Set<Integer> conflictWidths = conflictWidths(analysis);
        List<ResidualTarget> residualTargets = residuals.stream()
                .map(ResidualContext::target)
                .distinct()
                .sorted(RESIDUAL_ORDER)
                .toList();
        boolean bridgeDenominatorComplete =
                transferStatus == UniverseBuildStatus.EXHAUSTED_WITHIN_RULE;

        BuildMetrics buildMetrics = new BuildMetrics(
                data.patterns().size(),
                data.current().size(),
                analysis.splitWitnesses().size(),
                residualTargets.size(),
                allEquations.size(),
                equationLimit,
                transferBuild.directRepairPatterns(),
                transferBuild.sourcePairs(),
                transferBuild.complementScans(),
                transferBuild.exactPairMatches(),
                transferBuild.eligiblePaths(),
                transferBuild.eligibleEquations().size(),
                deadline.elapsedMs());

        EnumMap<UniverseScope, LocalPatternUniverse> layers =
                new EnumMap<>(UniverseScope.class);
        layers.put(
                UniverseScope.WITNESS_DIRECT,
                finishLayer(
                        UniverseScope.WITNESS_DIRECT,
                        directStatus,
                        direct,
                        data,
                        conflictWidths,
                        residualTargets,
                        allEquations,
                        transferBuild.eligibleEquations(),
                        false,
                        true,
                        deadline.elapsedMs()));
        layers.put(
                UniverseScope.NEUTRAL_CLOSURE,
                finishLayer(
                        UniverseScope.NEUTRAL_CLOSURE,
                        neutralStatus,
                        neutral,
                        data,
                        conflictWidths,
                        residualTargets,
                        allEquations,
                        transferBuild.eligibleEquations(),
                        false,
                        true,
                        deadline.elapsedMs()));
        layers.put(
                UniverseScope.TRANSFER_BRIDGE_1_HOP,
                finishLayer(
                        UniverseScope.TRANSFER_BRIDGE_1_HOP,
                        transferStatus,
                        transfer,
                        data,
                        conflictWidths,
                        residualTargets,
                        allEquations,
                        transferBuild.eligibleEquations(),
                        true,
                        bridgeDenominatorComplete,
                        deadline.elapsedMs()));

        validateNested(layers);
        return new BuildResult(
                Collections.unmodifiableMap(layers),
                List.copyOf(allEquations),
                List.copyOf(transferBuild.eligibleEquations().values()),
                unitResult.metrics(),
                buildMetrics);
    }

    private static InputData index(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upperBounds,
            OrderCompatibilityKernelAnalyzer.Analysis analysis,
            Options options) {
        Objects.requireNonNull(universe, "universe");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(upperBounds, "upperBounds");
        Objects.requireNonNull(analysis, "analysis");
        if (universe.isEmpty()) {
            throw new IllegalArgumentException("universe must not be empty");
        }
        if (analysis.status() != OrderCompatibilityKernelAnalyzer.Status.OPTIMAL
                || !analysis.groupOptimal()
                || !analysis.shapeOptimal()) {
            throw new IllegalArgumentException(
                    "compatibility analysis must prove both group and shape optimality");
        }

        List<PatternCandidate> patterns = universe.stream()
                .map(pattern -> Objects.requireNonNull(
                        pattern, "universe contains null"))
                .sorted(PATTERN_ORDER)
                .toList();
        Map<String, PatternCandidate> bySignature = new LinkedHashMap<>();
        for (PatternCandidate pattern : patterns) {
            PatternCandidate previous = bySignature.put(
                    pattern.signature(), pattern);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate universe signature: " + pattern.signature());
            }
        }

        Map<String, Integer> currentBySignature = new TreeMap<>();
        for (Map.Entry<PatternCandidate, Integer> entry : current.entrySet()) {
            PatternCandidate canonical = bySignature.get(entry.getKey().signature());
            if (canonical == null) {
                throw new IllegalArgumentException(
                        "current pattern is outside universe: "
                                + entry.getKey().signature());
            }
            Integer usage = entry.getValue();
            if (usage == null || usage <= 0) {
                throw new IllegalArgumentException(
                        "current support usage must be positive: "
                                + entry.getKey().signature());
            }
            if (currentBySignature.put(canonical.signature(), usage) != null) {
                throw new IllegalArgumentException(
                        "duplicate current signature: " + canonical.signature());
            }
        }
        if (analysis.patternCount() != currentBySignature.size()) {
            throw new IllegalArgumentException(
                    "analysis pattern count does not match current support");
        }

        Map<String, Integer> suppliedUpperBySignature = new HashMap<>();
        for (Map.Entry<PatternCandidate, Integer> entry : upperBounds.entrySet()) {
            PatternCandidate pattern = Objects.requireNonNull(
                    entry.getKey(), "upper bounds contain null pattern");
            if (!bySignature.containsKey(pattern.signature())) {
                throw new IllegalArgumentException(
                        "upper-bound pattern is outside universe: "
                                + pattern.signature());
            }
            Integer upper = entry.getValue();
            if (upper == null || upper < 0) {
                throw new IllegalArgumentException(
                        "invalid upper bound: " + pattern.signature());
            }
            if (suppliedUpperBySignature.put(pattern.signature(), upper) != null) {
                throw new IllegalArgumentException(
                        "duplicate upper-bound signature: " + pattern.signature());
            }
        }

        Map<String, Integer> upperBySignature = new TreeMap<>();
        Map<PatternCandidate, Integer> canonicalUpper = new LinkedHashMap<>();
        for (PatternCandidate pattern : patterns) {
            Integer upper = suppliedUpperBySignature.get(pattern.signature());
            if (upper == null) {
                throw new IllegalArgumentException(
                        "missing upper bound: " + pattern.signature());
            }
            int usage = currentBySignature.getOrDefault(pattern.signature(), 0);
            if (usage > upper) {
                throw new IllegalArgumentException(
                        "current usage exceeds upper bound: " + pattern.signature());
            }
            upperBySignature.put(pattern.signature(), upper);
            canonicalUpper.put(pattern, upper);
        }

        Map<PatternCandidate, Integer> canonicalCurrent = new LinkedHashMap<>();
        currentBySignature.forEach((signature, usage) ->
                canonicalCurrent.put(bySignature.get(signature), usage));

        for (OrderCompatibilityKernelAnalyzer.PatternSplit split
                : analysis.splitWitnesses()) {
            PatternCandidate pattern = bySignature.get(split.patternSignature());
            if (pattern == null) {
                throw new IllegalArgumentException(
                        "split witness is outside universe: "
                                + split.patternSignature());
            }
            Integer usage = currentBySignature.get(split.patternSignature());
            if (usage == null || usage != split.patternUsage()) {
                throw new IllegalArgumentException(
                        "split witness usage does not match current state: "
                                + split.patternSignature());
            }
            int configuredCars = split.configurations().stream()
                    .mapToInt(OrderCompatibilityKernelAnalyzer.ConfigUse::cars)
                    .sum();
            if (configuredCars != split.patternUsage()) {
                throw new IllegalArgumentException(
                        "split configurations do not sum to pattern usage: "
                                + split.patternSignature());
            }
        }

        int cars = currentBySignature.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        int waste = 0;
        for (Map.Entry<String, Integer> entry : currentBySignature.entrySet()) {
            waste += bySignature.get(entry.getKey()).getRealWaste(options.totalWidth())
                    * entry.getValue();
        }
        if (options.expectedCars() >= 0 && cars != options.expectedCars()) {
            throw new IllegalArgumentException(
                    "baseline cars mismatch: expected=" + options.expectedCars()
                            + ", actual=" + cars);
        }
        if (options.expectedWaste() >= 0 && waste != options.expectedWaste()) {
            throw new IllegalArgumentException(
                    "baseline waste mismatch: expected=" + options.expectedWaste()
                            + ", actual=" + waste);
        }

        Set<Integer> widthSet = new TreeSet<>();
        patterns.forEach(pattern -> widthSet.addAll(pattern.getPattern().keySet()));
        List<Integer> widths = List.copyOf(widthSet);
        Map<String, PatternVector> vectors = new LinkedHashMap<>();
        Map<PatternVector, List<Integer>> indicesByVector = new HashMap<>();
        for (int index = 0; index < patterns.size(); index++) {
            PatternCandidate pattern = patterns.get(index);
            int[] coefficients = new int[widths.size()];
            for (int widthIndex = 0; widthIndex < widths.size(); widthIndex++) {
                coefficients[widthIndex] = pattern.getPattern()
                        .getOrDefault(widths.get(widthIndex), 0);
            }
            PatternVector vector = new PatternVector(coefficients);
            vectors.put(pattern.signature(), vector);
            indicesByVector.computeIfAbsent(
                    vector, ignored -> new ArrayList<>()).add(index);
        }
        indicesByVector.replaceAll((ignored, indices) -> List.copyOf(indices));

        return new InputData(
                patterns,
                Collections.unmodifiableMap(bySignature),
                Collections.unmodifiableMap(canonicalCurrent),
                Collections.unmodifiableMap(canonicalUpper),
                Collections.unmodifiableMap(currentBySignature),
                Collections.unmodifiableMap(upperBySignature),
                widths,
                Collections.unmodifiableMap(vectors),
                Collections.unmodifiableMap(indicesByVector));
    }

    private static LayerAccumulator baselineLayer(
            InputData data,
            OrderCompatibilityKernelAnalyzer.Analysis analysis) {
        LayerAccumulator layer = new LayerAccumulator(data.bySignature());
        data.currentBySignature().forEach((signature, usage) -> {
            BaselineSupportEvidence evidence = new BaselineSupportEvidence(
                    evidenceId("baseline", signature),
                    signature,
                    usage,
                    data.upperBySignature().get(signature));
            layer.addSingle(signature, evidence);
        });
        analysis.splitWitnesses().stream()
                .sorted(Comparator.comparing(
                        OrderCompatibilityKernelAnalyzer.PatternSplit
                                ::patternSignature))
                .forEach(split -> {
                    List<ResidualTarget> targets = split.configurations().stream()
                            .map(config -> ResidualTarget.from(split, config))
                            .sorted(RESIDUAL_ORDER)
                            .toList();
                    SplitWitnessEvidence evidence = new SplitWitnessEvidence(
                            evidenceId("split", split.patternSignature()),
                            split.patternSignature(),
                            split.patternUsage(),
                            targets,
                            new TreeSet<>(split.varyingWidths()),
                            sortedMessages(split.varyingMessages()));
                    layer.addSingle(split.patternSignature(), evidence);
                });
        return layer;
    }

    private static Map<Integer, List<String>> sortedMessages(
            Map<Integer, List<String>> source) {
        Map<Integer, List<String>> result = new TreeMap<>();
        source.forEach((width, messages) -> result.put(
                width, messages.stream().sorted().toList()));
        return Collections.unmodifiableMap(result);
    }

    private static List<ResidualContext> residualContexts(
            OrderCompatibilityKernelAnalyzer.Analysis analysis) {
        List<ResidualContext> contexts = new ArrayList<>();
        analysis.splitWitnesses().stream()
                .sorted(Comparator.comparing(
                        OrderCompatibilityKernelAnalyzer.PatternSplit
                                ::patternSignature))
                .forEach(split -> split.configurations().stream()
                        .sorted(Comparator.comparing(
                                OrderCompatibilityKernelAnalyzer.ConfigUse
                                        ::configurationSignature)
                                .thenComparingInt(
                                        OrderCompatibilityKernelAnalyzer.ConfigUse
                                                ::cars))
                        .forEach(config -> contexts.add(new ResidualContext(
                                ResidualTarget.from(split, config), split))));
        contexts.sort(Comparator.comparing(context ->
                context.target().signature()));
        return List.copyOf(contexts);
    }

    private static List<NeutralEquation> equations(
            UnitTwoForTwoMoveEnumerator.Result unitResult,
            InputData data,
            Options options) {
        Map<String, NeutralEquation> equations = new TreeMap<>();
        for (UnitTwoForTwoMoveEnumerator.Candidate candidate
                : unitResult.candidates()) {
            UnitTwoForTwoMoveEnumerator.Move move = candidate.move();
            Map<String, Integer> delta = new TreeMap<>();
            move.unitDelta().forEach((pattern, coefficient) ->
                    delta.put(pattern.signature(), coefficient));
            NeutralEquation equation = neutralEquation(
                    delta, move.maxScale(), data, options.totalWidth());
            equations.putIfAbsent(equation.signature(), equation);
        }
        if (equations.size() != unitResult.metrics().normalizedMoves()) {
            throw new IllegalStateException(
                    "normalized unit equation count changed while structuring evidence");
        }
        return List.copyOf(equations.values());
    }

    private static NeutralEquation neutralEquation(
            Map<String, Integer> rawDelta,
            int maxScale,
            InputData data,
            int totalWidth) {
        TreeMap<String, Integer> delta = new TreeMap<>();
        rawDelta.forEach((signature, coefficient) -> {
            if (coefficient != 0) {
                if (!data.bySignature().containsKey(signature)) {
                    throw new IllegalStateException(
                            "equation references unknown pattern: " + signature);
                }
                delta.merge(signature, coefficient, Integer::sum);
            }
        });
        delta.values().removeIf(value -> value == 0);
        if (delta.isEmpty()) {
            throw new IllegalStateException("zero move cannot become an equation");
        }
        if (maxScale < 1) {
            throw new IllegalStateException("equation maxScale must be positive");
        }

        Map<String, Integer> removals = new TreeMap<>();
        Map<String, Integer> additions = new TreeMap<>();
        Set<Integer> balancedWidths = new TreeSet<>();
        int carDelta = 0;
        int wasteDelta = 0;
        Map<Integer, Integer> productionDelta = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : delta.entrySet()) {
            PatternCandidate pattern = data.bySignature().get(entry.getKey());
            int coefficient = entry.getValue();
            carDelta += coefficient;
            wasteDelta += coefficient * pattern.getRealWaste(totalWidth);
            if (coefficient < 0) {
                removals.put(entry.getKey(), -coefficient);
            } else {
                additions.put(entry.getKey(), coefficient);
            }
            pattern.getPattern().forEach((width, cut) -> {
                balancedWidths.add(width);
                productionDelta.merge(
                        width, coefficient * cut, Integer::sum);
            });
        }
        productionDelta.values().removeIf(value -> value == 0);
        if (carDelta != 0 || wasteDelta != 0 || !productionDelta.isEmpty()) {
            throw new IllegalStateException(
                    "invalid production-neutral equation: cars=" + carDelta
                            + ", waste=" + wasteDelta
                            + ", production=" + productionDelta);
        }
        String signature = delta.entrySet().stream()
                .map(entry -> token(entry.getKey()) + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right)
                .orElseThrow();
        return new NeutralEquation(
                signature,
                removals,
                additions,
                delta,
                maxScale,
                balancedWidths);
    }

    private static ResidualComplementEvidence residualEvidence(
            ResidualContext context,
            NeutralEquation equation,
            int scale) {
        int removedCars = -equation.unitDelta()
                .get(context.target().splitPatternSignature()) * scale;
        return new ResidualComplementEvidence(
                evidenceId(
                        "residual",
                        context.target().signature(),
                        equation.signature(),
                        Integer.toString(scale)),
                context.target(),
                equation.signature(),
                equation.unitDelta(),
                scale,
                removedCars,
                equation.maxScale(),
                equation.balancedWidths());
    }

    private static TransferBuild buildTransferLayer(
            LayerAccumulator transfer,
            LayerAccumulator direct,
            LayerAccumulator neutral,
            List<ResidualContext> residuals,
            InputData data,
            Options options,
            Deadline deadline) {
        Map<String, ResidualContext> residualBySignature = new HashMap<>();
        residuals.forEach(context -> residualBySignature.put(
                context.target().signature(), context));
        Set<String> baselineSignatures = data.currentBySignature().keySet();
        Set<String> neutralSignatures = neutral.patternSignatures();
        Set<Integer> allConflicts = residuals.stream()
                .flatMap(context -> context.split().varyingWidths().stream())
                .collect(TreeSet::new, Set::add, Set::addAll);

        List<DirectInsertion> insertions = new ArrayList<>();
        for (Map.Entry<String, List<InclusionEvidence>> entry
                : direct.evidenceByPattern().entrySet()) {
            String patternSignature = entry.getKey();
            if (baselineSignatures.contains(patternSignature)) {
                continue;
            }
            for (InclusionEvidence rawEvidence : entry.getValue()) {
                if (!(rawEvidence instanceof ResidualComplementEvidence evidence)) {
                    continue;
                }
                if (evidence.unitDelta().getOrDefault(patternSignature, 0) <= 0) {
                    continue;
                }
                ResidualContext context = residualBySignature.get(
                        evidence.target().signature());
                if (context == null) {
                    throw new IllegalStateException(
                            "residual evidence lost its split context");
                }
                PatternCandidate pattern = data.bySignature().get(patternSignature);
                boolean touchesConflict = context.split().varyingWidths().stream()
                        .anyMatch(pattern.getPattern()::containsKey);
                if (touchesConflict) {
                    insertions.add(new DirectInsertion(
                            patternSignature, evidence, context));
                }
            }
        }
        insertions.sort(Comparator
                .comparing(DirectInsertion::patternSignature)
                .thenComparing(insertion -> insertion.evidence().evidenceId()));
        Map<String, DirectInsertion> uniqueInsertions = new LinkedHashMap<>();
        for (DirectInsertion insertion : insertions) {
            uniqueInsertions.putIfAbsent(
                    insertion.patternSignature() + "|"
                            + insertion.evidence().evidenceId(),
                    insertion);
        }
        insertions = List.copyOf(uniqueInsertions.values());

        Set<String> directPatterns = new TreeSet<>();
        insertions.forEach(insertion ->
                directPatterns.add(insertion.patternSignature()));
        List<String> baseline = baselineSignatures.stream().sorted().toList();
        long sourcePairs = (long) directPatterns.size() * baseline.size();
        long complementScans = 0L;
        long exactPairMatches = 0L;
        Map<SourcePair, List<TargetPair>> targetCache = new LinkedHashMap<>();
        boolean timedOut = false;
        for (String directPattern : directPatterns) {
            for (String baselinePattern : baseline) {
                if (deadline.expired()) {
                    timedOut = true;
                    break;
                }
                SourcePair sourcePair = new SourcePair(
                        directPattern, baselinePattern);
                TargetLookup lookup = targetPairs(sourcePair, data, deadline);
                complementScans += lookup.complementScans();
                exactPairMatches += lookup.exactMatches();
                targetCache.put(sourcePair, lookup.targetPairs());
                if (lookup.timedOut()) {
                    timedOut = true;
                    break;
                }
            }
            if (timedOut) {
                break;
            }
        }
        if (timedOut) {
            return new TransferBuild(
                    UniverseBuildStatus.TIMED_OUT,
                    directPatterns.size(),
                    sourcePairs,
                    complementScans,
                    exactPairMatches,
                    0L,
                    Map.of());
        }

        long eligiblePaths = 0L;
        Map<String, NeutralEquation> eligibleEquations = new TreeMap<>();
        Set<String> seenEvidence = new LinkedHashSet<>();
        for (DirectInsertion insertion : insertions) {
            Map<String, Integer> intermediate = apply(
                    data.currentBySignature(),
                    insertion.evidence().unitDelta(),
                    insertion.evidence().scale());
            validateState(intermediate, data, options);
            for (String baselinePattern : baseline) {
                if (deadline.expired()) {
                    return new TransferBuild(
                            UniverseBuildStatus.TIMED_OUT,
                            directPatterns.size(),
                            sourcePairs,
                            complementScans,
                            exactPairMatches,
                            eligiblePaths,
                            eligibleEquations);
                }
                SourcePair source = new SourcePair(
                        insertion.patternSignature(), baselinePattern);
                for (TargetPair target : targetCache.getOrDefault(
                        source, List.of())) {
                    Map<String, Integer> rawDelta = new TreeMap<>();
                    rawDelta.merge(source.directPattern(), -1, Integer::sum);
                    rawDelta.merge(source.baselinePattern(), -1, Integer::sum);
                    rawDelta.merge(target.leftSignature(), 1, Integer::sum);
                    rawDelta.merge(target.rightSignature(), 1, Integer::sum);
                    rawDelta.values().removeIf(value -> value == 0);
                    if (rawDelta.isEmpty()) {
                        continue;
                    }
                    int secondMaxScale = maxScale(
                            rawDelta, intermediate, data.upperBySignature());
                    if (secondMaxScale < 1) {
                        continue;
                    }
                    NeutralEquation equation = neutralEquation(
                            rawDelta, secondMaxScale, data, options.totalWidth());
                    for (String targetSignature : target.signatures()) {
                        if (neutralSignatures.contains(targetSignature)) {
                            continue;
                        }
                        BridgePath path = bridgePath(
                                insertion,
                                baselinePattern,
                                targetSignature,
                                data,
                                allConflicts);
                        if (path == null) {
                            continue;
                        }
                        String evidenceKey = insertion.evidence().evidenceId()
                                + "|" + equation.signature()
                                + "|" + targetSignature
                                + "|" + path.signature();
                        if (!seenEvidence.add(evidenceKey)) {
                            continue;
                        }
                        if (eligiblePaths >= options.maxBridgePaths()) {
                            return new TransferBuild(
                                    UniverseBuildStatus.CAPPED,
                                    directPatterns.size(),
                                    sourcePairs,
                                    complementScans,
                                    exactPairMatches,
                                    eligiblePaths,
                                    eligibleEquations);
                        }
                        eligiblePaths++;
                        TransferBridgeEvidence evidence =
                                new TransferBridgeEvidence(
                                        evidenceId("bridge", evidenceKey),
                                        insertion.context().target(),
                                        insertion.evidence().evidenceId(),
                                        insertion.patternSignature(),
                                        baselinePattern,
                                        equation.signature(),
                                        equation.unitDelta(),
                                        stateSignature(intermediate),
                                        1,
                                        secondMaxScale,
                                        path.conflictWidth(),
                                        path.bridgeWidth(),
                                        path.newWidth(),
                                        path.nodes(),
                                        1);
                        if (!transfer.addEquation(
                                equation.patternSignatures(),
                                evidence,
                                options.maxPatterns())) {
                            return new TransferBuild(
                                    UniverseBuildStatus.CAPPED,
                                    directPatterns.size(),
                                    sourcePairs,
                                    complementScans,
                                    exactPairMatches,
                                    eligiblePaths,
                                    eligibleEquations);
                        }
                        eligibleEquations.putIfAbsent(
                                equation.signature(), equation);
                    }
                }
            }
        }
        return new TransferBuild(
                UniverseBuildStatus.EXHAUSTED_WITHIN_RULE,
                directPatterns.size(),
                sourcePairs,
                complementScans,
                exactPairMatches,
                eligiblePaths,
                eligibleEquations);
    }

    private static TargetLookup targetPairs(
            SourcePair source,
            InputData data,
            Deadline deadline) {
        PatternVector sourceVector = data.vectors().get(source.directPattern())
                .plus(data.vectors().get(source.baselinePattern()));
        List<TargetPair> targets = new ArrayList<>();
        long scans = 0L;
        long exact = 0L;
        for (int leftIndex = 0; leftIndex < data.patterns().size(); leftIndex++) {
            if (deadline.expired()) {
                return new TargetLookup(
                        List.copyOf(targets), scans, exact, true);
            }
            scans++;
            PatternVector leftVector = data.vectors().get(
                    data.patterns().get(leftIndex).signature());
            PatternVector complement = sourceVector.minus(leftVector);
            if (complement == null) {
                continue;
            }
            List<Integer> rightIndices = data.indicesByVector()
                    .get(complement);
            if (rightIndices == null) {
                continue;
            }
            for (int rightIndex : rightIndices) {
                if (leftIndex > rightIndex) {
                    continue;
                }
                PatternVector rightVector = data.vectors().get(
                        data.patterns().get(rightIndex).signature());
                if (!sourceVector.equals(leftVector.plus(rightVector))) {
                    continue;
                }
                exact++;
                targets.add(new TargetPair(
                        data.patterns().get(leftIndex).signature(),
                        data.patterns().get(rightIndex).signature()));
            }
        }
        targets.sort(Comparator
                .comparing(TargetPair::leftSignature)
                .thenComparing(TargetPair::rightSignature));
        return new TargetLookup(List.copyOf(targets), scans, exact, false);
    }

    private static BridgePath bridgePath(
            DirectInsertion insertion,
            String baselineSignature,
            String targetSignature,
            InputData data,
            Set<Integer> allConflicts) {
        PatternCandidate direct = data.bySignature().get(
                insertion.patternSignature());
        PatternCandidate baseline = data.bySignature().get(baselineSignature);
        PatternCandidate target = data.bySignature().get(targetSignature);
        List<Integer> conflicts = insertion.context().split().varyingWidths()
                .stream()
                .filter(direct.getPattern()::containsKey)
                .sorted()
                .toList();
        for (int conflict : conflicts) {
            List<Integer> bridgeWidths = direct.getPattern().keySet().stream()
                    .filter(target.getPattern()::containsKey)
                    .filter(width -> width != conflict)
                    .filter(width -> !allConflicts.contains(width))
                    .sorted()
                    .toList();
            for (int bridgeWidth : bridgeWidths) {
                List<Integer> newWidths = target.getPattern().keySet().stream()
                        .filter(baseline.getPattern()::containsKey)
                        .filter(width -> !direct.getPattern().containsKey(width))
                        .filter(width -> !allConflicts.contains(width))
                        .sorted()
                        .toList();
                if (!newWidths.isEmpty()) {
                    int newWidth = newWidths.get(0);
                    return new BridgePath(
                            conflict,
                            bridgeWidth,
                            newWidth,
                            List.of(
                                    "W:" + conflict,
                                    "P:" + insertion.patternSignature(),
                                    "W:" + bridgeWidth,
                                    "P:" + targetSignature,
                                    "W:" + newWidth));
                }
            }
        }
        return null;
    }

    private static int maxScale(
            Map<String, Integer> delta,
            Map<String, Integer> current,
            Map<String, Integer> upper) {
        int maxScale = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : delta.entrySet()) {
            int usage = current.getOrDefault(entry.getKey(), 0);
            int coefficient = entry.getValue();
            if (coefficient < 0) {
                maxScale = Math.min(maxScale, usage / -coefficient);
            } else if (coefficient > 0) {
                maxScale = Math.min(
                        maxScale,
                        (upper.get(entry.getKey()) - usage) / coefficient);
            }
        }
        return maxScale == Integer.MAX_VALUE ? 0 : maxScale;
    }

    private static Map<String, Integer> apply(
            Map<String, Integer> current,
            Map<String, Integer> delta,
            int scale) {
        Map<String, Integer> next = new TreeMap<>(current);
        delta.forEach((signature, coefficient) -> next.merge(
                signature, scale * coefficient, Integer::sum));
        next.values().removeIf(value -> value == 0);
        return Collections.unmodifiableMap(next);
    }

    private static void validateState(
            Map<String, Integer> state,
            InputData data,
            Options options) {
        int cars = 0;
        int waste = 0;
        Map<Integer, Integer> production = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : state.entrySet()) {
            PatternCandidate pattern = data.bySignature().get(entry.getKey());
            if (pattern == null) {
                throw new IllegalStateException(
                        "state references unknown pattern: " + entry.getKey());
            }
            int usage = entry.getValue();
            if (usage < 0 || usage > data.upperBySignature().get(entry.getKey())) {
                throw new IllegalStateException(
                        "state violates pattern bounds: " + entry.getKey());
            }
            cars += usage;
            waste += usage * pattern.getRealWaste(options.totalWidth());
            pattern.getPattern().forEach((width, coefficient) ->
                    production.merge(width, usage * coefficient, Integer::sum));
        }
        if (cars != totalCars(data.currentBySignature())) {
            throw new IllegalStateException("neutral move changed total cars");
        }
        if (waste != totalWaste(
                data.currentBySignature(), data, options.totalWidth())) {
            throw new IllegalStateException("neutral move changed total waste");
        }
        if (!production.equals(production(data.currentBySignature(), data))) {
            throw new IllegalStateException("neutral move changed production");
        }
    }

    private static int totalCars(Map<String, Integer> state) {
        return state.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int totalWaste(
            Map<String, Integer> state,
            InputData data,
            int totalWidth) {
        int waste = 0;
        for (Map.Entry<String, Integer> entry : state.entrySet()) {
            waste += data.bySignature().get(entry.getKey())
                    .getRealWaste(totalWidth) * entry.getValue();
        }
        return waste;
    }

    private static Map<Integer, Integer> production(
            Map<String, Integer> state,
            InputData data) {
        Map<Integer, Integer> production = new TreeMap<>();
        state.forEach((signature, usage) -> data.bySignature().get(signature)
                .getPattern().forEach((width, coefficient) ->
                        production.merge(
                                width, usage * coefficient, Integer::sum)));
        return production;
    }

    private static String stateSignature(Map<String, Integer> state) {
        return state.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> token(entry.getKey()) + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private static Set<Integer> conflictWidths(
            OrderCompatibilityKernelAnalyzer.Analysis analysis) {
        Set<Integer> widths = new TreeSet<>();
        analysis.splitWitnesses().forEach(split ->
                widths.addAll(split.varyingWidths()));
        return Collections.unmodifiableSet(widths);
    }

    private static LocalPatternUniverse finishLayer(
            UniverseScope scope,
            UniverseBuildStatus status,
            LayerAccumulator accumulator,
            InputData data,
            Set<Integer> conflictWidths,
            List<ResidualTarget> residualTargets,
            List<NeutralEquation> baselineEquations,
            Map<String, NeutralEquation> bridgeEquations,
            boolean bridgeApplicable,
            boolean bridgeDenominatorComplete,
            long elapsedMs) {
        Map<String, List<InclusionEvidence>> evidence = accumulator.finishEvidence();
        List<PatternCandidate> patterns = accumulator.patternSignatures().stream()
                .map(data.bySignature()::get)
                .sorted(PATTERN_ORDER)
                .toList();
        for (PatternCandidate pattern : patterns) {
            if (evidence.getOrDefault(pattern.signature(), List.of()).isEmpty()) {
                throw new IllegalStateException(
                        "selected pattern has no evidence: " + pattern.signature());
            }
        }
        CoverageProfile coverage = coverage(
                scope,
                patterns,
                evidence,
                data,
                conflictWidths,
                residualTargets,
                baselineEquations,
                bridgeEquations,
                bridgeApplicable,
                bridgeDenominatorComplete);
        int evidenceCount = evidence.values().stream()
                .mapToInt(List::size)
                .sum();
        Map<EvidenceType, Integer> evidenceDistribution =
                new EnumMap<>(EvidenceType.class);
        evidence.values().stream().flatMap(Collection::stream).forEach(item ->
                evidenceDistribution.merge(item.type(), 1, Integer::sum));
        return new LocalPatternUniverse(
                scope,
                status,
                patterns,
                evidence,
                coverage,
                new LayerMetrics(
                        patterns.size(),
                        evidenceCount,
                        evidenceDistribution,
                        elapsedMs));
    }

    private static CoverageProfile coverage(
            UniverseScope scope,
            List<PatternCandidate> selectedPatterns,
            Map<String, List<InclusionEvidence>> evidence,
            InputData data,
            Set<Integer> conflictWidths,
            List<ResidualTarget> residualTargets,
            List<NeutralEquation> baselineEquations,
            Map<String, NeutralEquation> bridgeEquations,
            boolean bridgeApplicable,
            boolean bridgeDenominatorComplete) {
        Set<String> selected = selectedPatterns.stream()
                .map(PatternCandidate::signature)
                .collect(TreeSet::new, Set::add, Set::addAll);
        Set<String> baseline = data.currentBySignature().keySet();

        Set<Integer> selectedConflictWidths = new TreeSet<>();
        selectedPatterns.stream()
                .filter(pattern -> !baseline.contains(pattern.signature()))
                .forEach(pattern -> conflictWidths.stream()
                        .filter(pattern.getPattern()::containsKey)
                        .forEach(selectedConflictWidths::add));

        Set<String> allUsableTouching = new TreeSet<>();
        Set<String> selectedUsableTouching = new TreeSet<>();
        Set<String> allCoefficientClasses = new TreeSet<>();
        Set<String> selectedCoefficientClasses = new TreeSet<>();
        for (PatternCandidate pattern : data.patterns()) {
            if (data.upperBySignature().get(pattern.signature()) < 2) {
                continue;
            }
            boolean touching = false;
            for (int width : conflictWidths) {
                int coefficient = pattern.getPattern().getOrDefault(width, 0);
                if (coefficient <= 0) {
                    continue;
                }
                touching = true;
                String coefficientClass = width + "x" + coefficient;
                allCoefficientClasses.add(coefficientClass);
                if (selected.contains(pattern.signature())) {
                    selectedCoefficientClasses.add(coefficientClass);
                }
            }
            if (touching) {
                allUsableTouching.add(pattern.signature());
                if (selected.contains(pattern.signature())) {
                    selectedUsableTouching.add(pattern.signature());
                }
            }
        }

        Set<ResidualTarget> coveredResiduals = new TreeSet<>(RESIDUAL_ORDER);
        evidence.values().stream().flatMap(Collection::stream)
                .filter(ResidualComplementEvidence.class::isInstance)
                .map(ResidualComplementEvidence.class::cast)
                .map(ResidualComplementEvidence::target)
                .forEach(coveredResiduals::add);
        List<ResidualTarget> uncovered = residualTargets.stream()
                .filter(target -> !coveredResiduals.contains(target))
                .toList();

        long containedBaselineEquations = baselineEquations.stream()
                .filter(equation -> selected.containsAll(
                        equation.patternSignatures()))
                .count();
        long containedBridgeEquations = bridgeEquations.values().stream()
                .filter(equation -> selected.containsAll(
                        equation.patternSignatures()))
                .count();

        return new CoverageProfile(
                CoverageMeasure.of(
                        selectedConflictWidths.size(),
                        conflictWidths.size(),
                        true,
                        "non-baseline selected conflict widths / witness conflict widths"),
                CoverageMeasure.of(
                        selectedUsableTouching.size(),
                        allUsableTouching.size(),
                        true,
                        "selected usable conflict-touching patterns / all such universe patterns"),
                CoverageMeasure.of(
                        selectedCoefficientClasses.size(),
                        allCoefficientClasses.size(),
                        true,
                        "selected (conflict width, coefficient) classes / all usable classes"),
                CoverageMeasure.of(
                        coveredResiduals.size(),
                        residualTargets.size(),
                        true,
                        "residual targets with exact complement evidence / all residual targets"),
                CoverageMeasure.of(
                        containedBaselineEquations,
                        baselineEquations.size(),
                        true,
                        "fully contained baseline unit equations / complete baseline equations"),
                bridgeApplicable
                        ? CoverageMeasure.of(
                                containedBridgeEquations,
                                bridgeEquations.size(),
                                bridgeDenominatorComplete,
                                "fully contained eligible one-hop bridge equations / enumerated eligible equations")
                        : CoverageMeasure.notApplicable(
                                "one-hop bridge coverage is not designed for " + scope),
                CoverageMeasure.of(
                        selected.size(),
                        data.patterns().size(),
                        true,
                        "selected patterns / complete universe patterns"),
                uncovered);
    }

    private static void validateNested(
            Map<UniverseScope, LocalPatternUniverse> layers) {
        Set<String> direct = signatures(
                layers.get(UniverseScope.WITNESS_DIRECT).patterns());
        Set<String> neutral = signatures(
                layers.get(UniverseScope.NEUTRAL_CLOSURE).patterns());
        Set<String> transfer = signatures(
                layers.get(UniverseScope.TRANSFER_BRIDGE_1_HOP).patterns());
        if (!neutral.containsAll(direct) || !transfer.containsAll(neutral)) {
            throw new IllegalStateException("local universes are not nested");
        }
    }

    private static Set<String> signatures(List<PatternCandidate> patterns) {
        Set<String> result = new TreeSet<>();
        patterns.forEach(pattern -> result.add(pattern.signature()));
        return result;
    }

    private static String evidenceId(String prefix, String... parts) {
        StringBuilder id = new StringBuilder(prefix);
        for (String part : parts) {
            id.append('|').append(token(part));
        }
        return id.toString();
    }

    private static String token(String value) {
        return value.length() + ":" + value;
    }

    enum UniverseScope {
        WITNESS_DIRECT,
        NEUTRAL_CLOSURE,
        TRANSFER_BRIDGE_1_HOP
    }

    enum UniverseBuildStatus {
        EXHAUSTED_WITHIN_RULE,
        CAPPED,
        TIMED_OUT,
        ABNORMAL
    }

    enum EvidenceType {
        BASELINE_SUPPORT,
        SPLIT_WITNESS,
        RESIDUAL_COMPLEMENT,
        NEUTRAL_EQUATION,
        TRANSFER_BRIDGE
    }

    sealed interface InclusionEvidence permits
            BaselineSupportEvidence,
            SplitWitnessEvidence,
            ResidualComplementEvidence,
            NeutralEquationEvidence,
            TransferBridgeEvidence {

        String evidenceId();

        EvidenceType type();
    }

    record BaselineSupportEvidence(
            String evidenceId,
            String patternSignature,
            int usage,
            int upperBound) implements InclusionEvidence {

        BaselineSupportEvidence {
            Objects.requireNonNull(evidenceId);
            Objects.requireNonNull(patternSignature);
            if (usage <= 0 || upperBound < usage) {
                throw new IllegalArgumentException("invalid baseline support evidence");
            }
        }

        @Override
        public EvidenceType type() {
            return EvidenceType.BASELINE_SUPPORT;
        }
    }

    record SplitWitnessEvidence(
            String evidenceId,
            String patternSignature,
            int patternUsage,
            List<ResidualTarget> residualTargets,
            Set<Integer> varyingWidths,
            Map<Integer, List<String>> varyingMessages)
            implements InclusionEvidence {

        SplitWitnessEvidence {
            Objects.requireNonNull(evidenceId);
            Objects.requireNonNull(patternSignature);
            residualTargets = List.copyOf(residualTargets);
            varyingWidths = Collections.unmodifiableSet(
                    new TreeSet<>(varyingWidths));
            varyingMessages = sortedMessages(varyingMessages);
        }

        @Override
        public EvidenceType type() {
            return EvidenceType.SPLIT_WITNESS;
        }
    }

    record ResidualComplementEvidence(
            String evidenceId,
            ResidualTarget target,
            String equationSignature,
            Map<String, Integer> unitDelta,
            int scale,
            int removedCars,
            int maxScale,
            Set<Integer> balancedWidths) implements InclusionEvidence {

        ResidualComplementEvidence {
            Objects.requireNonNull(evidenceId);
            Objects.requireNonNull(target);
            Objects.requireNonNull(equationSignature);
            unitDelta = Collections.unmodifiableMap(new TreeMap<>(unitDelta));
            balancedWidths = Collections.unmodifiableSet(
                    new TreeSet<>(balancedWidths));
            if (scale < 1 || scale > maxScale || removedCars < 1) {
                throw new IllegalArgumentException("invalid residual evidence bounds");
            }
        }

        @Override
        public EvidenceType type() {
            return EvidenceType.RESIDUAL_COMPLEMENT;
        }
    }

    record NeutralEquationEvidence(
            String evidenceId,
            String equationSignature,
            Map<String, Integer> removals,
            Map<String, Integer> additions,
            Map<String, Integer> unitDelta,
            Set<Integer> balancedWidths,
            UniverseScope originScope) implements InclusionEvidence {

        NeutralEquationEvidence {
            Objects.requireNonNull(evidenceId);
            Objects.requireNonNull(equationSignature);
            removals = Collections.unmodifiableMap(new TreeMap<>(removals));
            additions = Collections.unmodifiableMap(new TreeMap<>(additions));
            unitDelta = Collections.unmodifiableMap(new TreeMap<>(unitDelta));
            balancedWidths = Collections.unmodifiableSet(
                    new TreeSet<>(balancedWidths));
            Objects.requireNonNull(originScope);
        }

        @Override
        public EvidenceType type() {
            return EvidenceType.NEUTRAL_EQUATION;
        }
    }

    record TransferBridgeEvidence(
            String evidenceId,
            ResidualTarget target,
            String residualEvidenceId,
            String directPatternSignature,
            String baselinePartnerSignature,
            String equationSignature,
            Map<String, Integer> unitDelta,
            String intermediateStateSignature,
            int secondScale,
            int secondMaxScale,
            int conflictWidth,
            int bridgeWidth,
            int newWidth,
            List<String> pathNodes,
            int hopCount) implements InclusionEvidence {

        TransferBridgeEvidence {
            Objects.requireNonNull(evidenceId);
            Objects.requireNonNull(target);
            Objects.requireNonNull(residualEvidenceId);
            Objects.requireNonNull(directPatternSignature);
            Objects.requireNonNull(baselinePartnerSignature);
            Objects.requireNonNull(equationSignature);
            unitDelta = Collections.unmodifiableMap(new TreeMap<>(unitDelta));
            Objects.requireNonNull(intermediateStateSignature);
            pathNodes = List.copyOf(pathNodes);
            if (secondScale < 1 || secondScale > secondMaxScale || hopCount != 1) {
                throw new IllegalArgumentException("invalid transfer bridge evidence");
            }
        }

        @Override
        public EvidenceType type() {
            return EvidenceType.TRANSFER_BRIDGE;
        }
    }

    record ResidualTarget(
            String splitPatternSignature,
            String configurationSignature,
            int configurationCars) {

        ResidualTarget {
            Objects.requireNonNull(splitPatternSignature);
            Objects.requireNonNull(configurationSignature);
            if (configurationCars <= 0) {
                throw new IllegalArgumentException(
                        "configurationCars must be positive");
            }
        }

        static ResidualTarget from(
                OrderCompatibilityKernelAnalyzer.PatternSplit split,
                OrderCompatibilityKernelAnalyzer.ConfigUse configuration) {
            return new ResidualTarget(
                    split.patternSignature(),
                    configuration.configurationSignature(),
                    configuration.cars());
        }

        static ResidualTarget fromValues(
                String splitPatternSignature,
                String configurationSignature,
                int configurationCars) {
            return new ResidualTarget(
                    splitPatternSignature,
                    configurationSignature,
                    configurationCars);
        }

        String signature() {
            return token(splitPatternSignature)
                    + "|" + token(configurationSignature)
                    + "|cars=" + configurationCars;
        }
    }

    record NeutralEquation(
            String signature,
            Map<String, Integer> removals,
            Map<String, Integer> additions,
            Map<String, Integer> unitDelta,
            int maxScale,
            Set<Integer> balancedWidths) {

        NeutralEquation {
            Objects.requireNonNull(signature);
            removals = Collections.unmodifiableMap(new TreeMap<>(removals));
            additions = Collections.unmodifiableMap(new TreeMap<>(additions));
            unitDelta = Collections.unmodifiableMap(new TreeMap<>(unitDelta));
            balancedWidths = Collections.unmodifiableSet(
                    new TreeSet<>(balancedWidths));
        }

        Set<String> patternSignatures() {
            return unitDelta.keySet();
        }
    }

    record CoverageMeasure(
            long numerator,
            long denominator,
            boolean applicable,
            boolean denominatorComplete,
            String definition) {

        CoverageMeasure {
            Objects.requireNonNull(definition);
            if (numerator < 0 || denominator < 0 || numerator > denominator) {
                throw new IllegalArgumentException("invalid coverage measure");
            }
        }

        static CoverageMeasure of(
                long numerator,
                long denominator,
                boolean denominatorComplete,
                String definition) {
            return new CoverageMeasure(
                    numerator, denominator, true, denominatorComplete, definition);
        }

        static CoverageMeasure notApplicable(String definition) {
            return new CoverageMeasure(0, 0, false, true, definition);
        }
    }

    record CoverageProfile(
            CoverageMeasure conflictWidthCoverage,
            CoverageMeasure conflictTouchPatternCoverage,
            CoverageMeasure coefficientClassCoverage,
            CoverageMeasure residualTargetCoverage,
            CoverageMeasure neutralEquationCoverage,
            CoverageMeasure bridgeEquationCoverage,
            CoverageMeasure universeSizeCoverage,
            List<ResidualTarget> uncoveredResidualTargets) {

        CoverageProfile {
            uncoveredResidualTargets = List.copyOf(uncoveredResidualTargets);
        }
    }

    record LayerMetrics(
            int selectedPatterns,
            int evidenceCount,
            Map<EvidenceType, Integer> evidenceDistribution,
            long elapsedMs) {

        LayerMetrics {
            evidenceDistribution = Collections.unmodifiableMap(
                    new EnumMap<>(evidenceDistribution));
        }
    }

    record LocalPatternUniverse(
            UniverseScope scope,
            UniverseBuildStatus buildStatus,
            List<PatternCandidate> patterns,
            Map<String, List<InclusionEvidence>> evidenceByPattern,
            CoverageProfile coverage,
            LayerMetrics metrics) {

        LocalPatternUniverse {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(buildStatus);
            patterns = List.copyOf(patterns);
            Map<String, List<InclusionEvidence>> copy = new TreeMap<>();
            evidenceByPattern.forEach((signature, values) ->
                    copy.put(signature, List.copyOf(values)));
            evidenceByPattern = Collections.unmodifiableMap(copy);
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(metrics);
        }

        List<InclusionEvidence> evidenceFor(String patternSignature) {
            return evidenceByPattern.getOrDefault(patternSignature, List.of());
        }

        List<InclusionEvidence> evidenceOfType(EvidenceType type) {
            return evidenceByPattern.values().stream()
                    .flatMap(Collection::stream)
                    .filter(evidence -> evidence.type() == type)
                    .distinct()
                    .sorted(Comparator.comparing(InclusionEvidence::evidenceId))
                    .toList();
        }
    }

    record BuildMetrics(
            int fullUniversePatterns,
            int baselineSupportPatterns,
            int splitWitnesses,
            int residualTargets,
            int completeBaselineEquations,
            int processedBaselineEquations,
            int directRepairPatterns,
            long transferSourcePairs,
            long transferComplementScans,
            long transferExactPairMatches,
            long eligibleBridgePaths,
            int eligibleBridgeEquations,
            long elapsedMs) {
    }

    record BuildResult(
            Map<UniverseScope, LocalPatternUniverse> layers,
            List<NeutralEquation> baselineUnitEquations,
            List<NeutralEquation> eligibleBridgeEquations,
            UnitTwoForTwoMoveEnumerator.Metrics unitMetrics,
            BuildMetrics metrics) {

        BuildResult {
            layers = Collections.unmodifiableMap(new EnumMap<>(layers));
            baselineUnitEquations = List.copyOf(baselineUnitEquations);
            eligibleBridgeEquations = List.copyOf(eligibleBridgeEquations);
            Objects.requireNonNull(unitMetrics);
            Objects.requireNonNull(metrics);
        }

        LocalPatternUniverse layer(UniverseScope scope) {
            return Objects.requireNonNull(layers.get(scope));
        }
    }

    record Options(
            long timeLimitMs,
            int maxPatterns,
            int maxEquations,
            int maxBridgePaths,
            int totalWidth,
            int expectedCars,
            int expectedWaste,
            LongSupplier clock) {

        Options {
            if (timeLimitMs <= 0
                    || maxPatterns <= 0
                    || maxEquations <= 0
                    || maxBridgePaths <= 0
                    || totalWidth <= 0
                    || expectedCars < -1
                    || expectedWaste < -1) {
                throw new IllegalArgumentException("invalid local-universe options");
            }
            Objects.requireNonNull(clock);
        }

        static Options researchDefaults() {
            return new Options(
                    120_000L,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    4_600,
                    169,
                    36_870,
                    System::currentTimeMillis);
        }
    }

    private record InputData(
            List<PatternCandidate> patterns,
            Map<String, PatternCandidate> bySignature,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upperBounds,
            Map<String, Integer> currentBySignature,
            Map<String, Integer> upperBySignature,
            List<Integer> widths,
            Map<String, PatternVector> vectors,
            Map<PatternVector, List<Integer>> indicesByVector) {
    }

    private record ResidualContext(
            ResidualTarget target,
            OrderCompatibilityKernelAnalyzer.PatternSplit split) {
    }

    private record DirectInsertion(
            String patternSignature,
            ResidualComplementEvidence evidence,
            ResidualContext context) {
    }

    private record SourcePair(
            String directPattern,
            String baselinePattern) {
    }

    private record TargetPair(
            String leftSignature,
            String rightSignature) {

        List<String> signatures() {
            return leftSignature.equals(rightSignature)
                    ? List.of(leftSignature)
                    : List.of(leftSignature, rightSignature);
        }
    }

    private record TargetLookup(
            List<TargetPair> targetPairs,
            long complementScans,
            long exactMatches,
            boolean timedOut) {
    }

    private record BridgePath(
            int conflictWidth,
            int bridgeWidth,
            int newWidth,
            List<String> nodes) {

        BridgePath {
            nodes = List.copyOf(nodes);
        }

        String signature() {
            return conflictWidth + ">" + bridgeWidth + ">" + newWidth
                    + "|" + String.join(">", nodes);
        }
    }

    private record TransferBuild(
            UniverseBuildStatus status,
            int directRepairPatterns,
            long sourcePairs,
            long complementScans,
            long exactPairMatches,
            long eligiblePaths,
            Map<String, NeutralEquation> eligibleEquations) {

        TransferBuild {
            eligibleEquations = Collections.unmodifiableMap(
                    new TreeMap<>(eligibleEquations));
        }

        static TransferBuild empty() {
            return new TransferBuild(
                    UniverseBuildStatus.EXHAUSTED_WITHIN_RULE,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    Map.of());
        }
    }

    private static final class LayerAccumulator {

        private final Map<String, PatternCandidate> universe;
        private final Set<String> patterns = new TreeSet<>();
        private final Map<String, List<InclusionEvidence>> evidence =
                new TreeMap<>();

        private LayerAccumulator(Map<String, PatternCandidate> universe) {
            this.universe = universe;
        }

        private LayerAccumulator(LayerAccumulator source) {
            this.universe = source.universe;
            this.patterns.addAll(source.patterns);
            source.evidence.forEach((signature, values) ->
                    this.evidence.put(signature, new ArrayList<>(values)));
        }

        private int size() {
            return patterns.size();
        }

        private Set<String> patternSignatures() {
            return Collections.unmodifiableSet(new TreeSet<>(patterns));
        }

        private Map<String, List<InclusionEvidence>> evidenceByPattern() {
            return evidence;
        }

        private void addSingle(
                String patternSignature,
                InclusionEvidence item) {
            if (!universe.containsKey(patternSignature)) {
                throw new IllegalStateException(
                        "evidence references unknown pattern: " + patternSignature);
            }
            patterns.add(patternSignature);
            addEvidence(patternSignature, item);
        }

        private boolean addEquation(
                Collection<String> equationPatterns,
                InclusionEvidence item,
                int maxPatterns) {
            Set<String> additions = new TreeSet<>(equationPatterns);
            if (!universe.keySet().containsAll(additions)) {
                throw new IllegalStateException(
                        "equation evidence references unknown patterns");
            }
            long newPatterns = additions.stream()
                    .filter(signature -> !patterns.contains(signature))
                    .count();
            if (patterns.size() + newPatterns > maxPatterns) {
                return false;
            }
            patterns.addAll(additions);
            additions.forEach(signature -> addEvidence(signature, item));
            return true;
        }

        private void addEvidence(
                String patternSignature,
                InclusionEvidence item) {
            List<InclusionEvidence> values = evidence.computeIfAbsent(
                    patternSignature, ignored -> new ArrayList<>());
            boolean duplicate = values.stream().anyMatch(existing ->
                    existing.evidenceId().equals(item.evidenceId()));
            if (!duplicate) {
                values.add(item);
                values.sort(Comparator.comparing(InclusionEvidence::evidenceId));
            }
        }

        private Map<String, List<InclusionEvidence>> finishEvidence() {
            Map<String, List<InclusionEvidence>> result = new TreeMap<>();
            evidence.forEach((signature, values) ->
                    result.put(signature, List.copyOf(values)));
            return Collections.unmodifiableMap(result);
        }
    }

    private static final class PatternVector {

        private final int[] coefficients;
        private final int hash;

        private PatternVector(int[] coefficients) {
            this.coefficients = coefficients.clone();
            this.hash = java.util.Arrays.hashCode(this.coefficients);
        }

        private PatternVector plus(PatternVector other) {
            int[] sum = new int[coefficients.length];
            for (int index = 0; index < coefficients.length; index++) {
                sum[index] = coefficients[index] + other.coefficients[index];
            }
            return new PatternVector(sum);
        }

        private PatternVector minus(PatternVector other) {
            int[] difference = new int[coefficients.length];
            for (int index = 0; index < coefficients.length; index++) {
                difference[index] = coefficients[index] - other.coefficients[index];
                if (difference[index] < 0) {
                    return null;
                }
            }
            return new PatternVector(difference);
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof PatternVector other
                    && java.util.Arrays.equals(coefficients, other.coefficients);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class Deadline {

        private final LongSupplier clock;
        private final long startedAt;
        private final long limitMs;
        private long lastObserved;

        private Deadline(LongSupplier clock, long limitMs) {
            this.clock = clock;
            this.startedAt = clock.getAsLong();
            this.lastObserved = startedAt;
            this.limitMs = limitMs;
        }

        private boolean expired() {
            lastObserved = Math.max(lastObserved, clock.getAsLong());
            return lastObserved - startedAt >= limitMs;
        }

        private long elapsedMs() {
            lastObserved = Math.max(lastObserved, clock.getAsLong());
            return Math.max(0L, lastObserved - startedAt);
        }
    }
}
