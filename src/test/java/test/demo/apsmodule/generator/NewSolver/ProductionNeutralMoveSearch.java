package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.mip.AssignmentMIPSolver;
import test.demo.apsmodule.generator.NewSolver.mip.Phase2SequenceGroupSolver;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Research-only closed-loop search over production-neutral integer moves. */
final class ProductionNeutralMoveSearch {

    private ProductionNeutralMoveSearch() {
    }

    static SearchResult search(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> baseline,
            Map<PatternCandidate, Integer> upperBounds,
            List<SolverOrderItem> orderItems,
            SolverParameters parameters,
            Options options) {
        Objects.requireNonNull(options, "options");
        long startedAt = System.currentTimeMillis();
        Map<Integer, Integer> expectedProduction = production(baseline);
        int expectedCars = totalCars(baseline);
        int expectedWaste = totalWaste(baseline, parameters.getTotalWidth());

        OrderCompatibilityKernelAnalyzer.Options fullOptions =
                new OrderCompatibilityKernelAnalyzer.Options(
                        options.fullAnalysisGroupTimeLimitMs(),
                        options.fullAnalysisShapeTimeLimitMs(),
                        options.maxConfigurations(),
                        options.exactNodeLimit(),
                        false);
        OrderCompatibilityKernelAnalyzer.Analysis baselineAnalysis =
                OrderCompatibilityKernelAnalyzer.analyze(
                        baseline, orderItems, fullOptions);
        if (!baselineAnalysis.groupOptimal()
                || !baselineAnalysis.shapeOptimal()
                || baselineAnalysis.exactMinimumGroups() <= 0
                || baselineAnalysis.oddGroups() != 1
                || baselineAnalysis.oneGroups() != 0
                || !usageShapeCompatible(baseline)) {
            return new SearchResult(
                    SearchStatus.BASELINE_UNPROVEN,
                    baseline,
                    baselineAnalysis,
                    baseline,
                    baselineAnalysis,
                    List.of(),
                    List.of(),
                    false,
                    false,
                    baselineAnalysis.totalElapsedMs(),
                    System.currentTimeMillis() - startedAt);
        }

        int currentBestGroups = baselineAnalysis.exactMinimumGroups();
        Map<PatternCandidate, Integer> bestSolution = baseline;
        OrderCompatibilityKernelAnalyzer.Analysis bestAnalysis = baselineAnalysis;
        long witnessRefreshMs = baselineAnalysis.totalElapsedMs();
        List<LayerRun> layerRuns = new ArrayList<>();
        List<CandidateEvaluation> allEvaluations = new ArrayList<>();
        Set<String> visitedStates = new LinkedHashSet<>();
        Deque<SearchState> frontier = new ArrayDeque<>();
        String baselineSignature = stateSignature(universe, baseline);
        visitedStates.add(baselineSignature);
        frontier.add(new SearchState(
                baseline,
                baselineSignature,
                0,
                baselineAnalysis.splitWitnesses()));
        boolean allMoveGenerationExhausted = true;
        boolean allExactEvaluationExhausted = true;
        int expandedStates = 0;
        boolean improved = false;

        while (!frontier.isEmpty()
                && expandedStates < options.maxExpandedStates()
                && !improved) {
            SearchState state = frontier.removeFirst();
            expandedStates++;
            Set<String> preferredPatterns = splitPatternSignatures(state.witnesses());
            Set<Integer> preferredWidths = splitWidths(state.witnesses());

            UnitTwoForTwoMoveEnumerator.Result unit =
                    UnitTwoForTwoMoveEnumerator.enumerate(
                            universe, state.solution(), upperBounds);
            allMoveGenerationExhausted &= unit.exhausted();
            NeighborBatch unitNeighbors = unitNeighbors(
                    unit,
                    expectedProduction,
                    expectedCars,
                    expectedWaste,
                    parameters.getTotalWidth(),
                    preferredPatterns,
                    preferredWidths,
                    visitedStates);
            EvaluationBatch unitEvaluation = evaluate(
                    unitNeighbors.neighbors(),
                    currentBestGroups,
                    orderItems,
                    parameters,
                    options,
                    fullOptions);
            allEvaluations.addAll(unitEvaluation.evaluations());
            allExactEvaluationExhausted &= unitEvaluation.exactEvaluationExhausted()
                    && unitNeighbors.invalidInvariants() == 0;
            layerRuns.add(new LayerRun(
                    state.stateSignature(),
                    state.depth(),
                    Layer.UNIT_TWO_FOR_TWO,
                    unit.metrics().uniqueStates(),
                    unitNeighbors.usageRejected(),
                    unitNeighbors.visitedRejected(),
                    unitNeighbors.invalidInvariants(),
                    unitNeighbors.neighbors().size(),
                    unitEvaluation.phase2Evaluated(),
                    unitEvaluation.exactEvaluated(),
                    unit.exhausted(),
                    unitEvaluation.exactEvaluationExhausted(),
                    unit.metrics().elapsedMs(),
                    null,
                    null));

            CandidateEvaluation improvement = unitEvaluation.evaluations().stream()
                    .filter(evaluation -> evaluation.classification()
                            == Classification.IMPROVEMENT_CONFIRMED)
                    .min(Comparator.comparingInt(evaluation ->
                            evaluation.fullAnalysis().exactMinimumGroups()))
                    .orElse(null);
            if (improvement != null) {
                bestSolution = improvement.neighbor().solution();
                bestAnalysis = improvement.fullAnalysis();
                currentBestGroups = bestAnalysis.exactMinimumGroups();
                improved = true;
                break;
            }

            EnqueueResult enqueuedPlatforms = enqueuePlatforms(
                    unitEvaluation.evaluations(),
                    state.depth(),
                    options,
                    frontier,
                    visitedStates);
            witnessRefreshMs += enqueuedPlatforms.witnessRefreshMs();

            if (options.enableSparseLayer()
                    && state.depth() == 0
                    && !improved) {
                Set<String> excluded = new LinkedHashSet<>();
                List<Map<PatternCandidate, Integer>> seedMoves = new ArrayList<>();
                unit.candidates().forEach(candidate ->
                        excluded.add(candidate.stateSignature()));
                unitNeighbors.neighbors().forEach(neighbor ->
                        seedMoves.add(neighbor.delta()));
                unit.candidates().forEach(candidate -> {
                    Map<PatternCandidate, Integer> actualDelta = new LinkedHashMap<>();
                    candidate.move().unitDelta().forEach((pattern, coefficient) ->
                            actualDelta.put(
                                    pattern, coefficient * candidate.scale()));
                    seedMoves.add(Map.copyOf(actualDelta));
                });
                SparseProductionNeutralMoveEnumerator.Options sparseOptions =
                        withPreferences(
                                options.sparseOptions(),
                                preferredPatterns,
                                preferredWidths);
                SparseProductionNeutralMoveEnumerator.Result sparse =
                        SparseProductionNeutralMoveEnumerator.enumerate(
                                universe,
                                state.solution(),
                                upperBounds,
                                excluded,
                                seedMoves,
                                sparseOptions);
                allMoveGenerationExhausted &= sparse.status()
                        == SparseProductionNeutralMoveEnumerator.Status.EXHAUSTED;
                NeighborBatch sparseNeighbors = sparseNeighbors(
                        sparse,
                        expectedProduction,
                        expectedCars,
                        expectedWaste,
                        parameters.getTotalWidth(),
                        preferredPatterns,
                        preferredWidths,
                        visitedStates);
                EvaluationBatch sparseEvaluation = evaluate(
                        sparseNeighbors.neighbors(),
                        currentBestGroups,
                        orderItems,
                        parameters,
                        options,
                        fullOptions);
                allEvaluations.addAll(sparseEvaluation.evaluations());
                allExactEvaluationExhausted &= sparseEvaluation
                        .exactEvaluationExhausted()
                        && sparseNeighbors.invalidInvariants() == 0;
                layerRuns.add(new LayerRun(
                        state.stateSignature(),
                        state.depth(),
                        Layer.SPARSE_UP_TO_THREE,
                        sparse.metrics().uniqueStates(),
                        sparseNeighbors.usageRejected(),
                        sparseNeighbors.visitedRejected(),
                        sparseNeighbors.invalidInvariants(),
                        sparseNeighbors.neighbors().size(),
                        sparseEvaluation.phase2Evaluated(),
                        sparseEvaluation.exactEvaluated(),
                        sparse.status()
                                == SparseProductionNeutralMoveEnumerator.Status.EXHAUSTED,
                        sparseEvaluation.exactEvaluationExhausted(),
                        sparse.metrics().totalElapsedMs(),
                        sparse.status(),
                        sparse.metrics()));

                improvement = sparseEvaluation.evaluations().stream()
                        .filter(evaluation -> evaluation.classification()
                                == Classification.IMPROVEMENT_CONFIRMED)
                        .min(Comparator.comparingInt(evaluation ->
                                evaluation.fullAnalysis().exactMinimumGroups()))
                        .orElse(null);
                if (improvement != null) {
                    bestSolution = improvement.neighbor().solution();
                    bestAnalysis = improvement.fullAnalysis();
                    improved = true;
                    break;
                }
                EnqueueResult sparsePlatforms = enqueuePlatforms(
                        sparseEvaluation.evaluations(),
                        state.depth(),
                        options,
                        frontier,
                        visitedStates);
                witnessRefreshMs += sparsePlatforms.witnessRefreshMs();
            }
        }

        SearchStatus status;
        if (improved) {
            status = SearchStatus.IMPROVED;
        } else if (allMoveGenerationExhausted && allExactEvaluationExhausted) {
            status = SearchStatus.NO_IMPROVEMENT_PROVEN_IN_SEARCHED_NEIGHBORHOOD;
        } else {
            status = SearchStatus.INCONCLUSIVE;
        }
        return new SearchResult(
                status,
                baseline,
                baselineAnalysis,
                bestSolution,
                bestAnalysis,
                List.copyOf(layerRuns),
                List.copyOf(allEvaluations),
                allMoveGenerationExhausted,
                allExactEvaluationExhausted,
                witnessRefreshMs,
                System.currentTimeMillis() - startedAt);
    }

    private static NeighborBatch unitNeighbors(
            UnitTwoForTwoMoveEnumerator.Result result,
            Map<Integer, Integer> expectedProduction,
            int expectedCars,
            int expectedWaste,
            int totalWidth,
            Set<String> preferredPatterns,
            Set<Integer> preferredWidths,
            Set<String> visitedStates) {
        List<Neighbor> neighbors = new ArrayList<>();
        int usageRejected = 0;
        int visitedRejected = 0;
        int invalid = 0;
        for (UnitTwoForTwoMoveEnumerator.Candidate candidate : result.candidates()) {
            if (!validInvariants(
                    candidate.solution(), expectedProduction, expectedCars,
                    expectedWaste, totalWidth)) {
                invalid++;
                continue;
            }
            if (!usageShapeCompatible(candidate.solution())) {
                usageRejected++;
                continue;
            }
            if (visitedStates.contains(candidate.stateSignature())) {
                visitedRejected++;
                continue;
            }
            Map<PatternCandidate, Integer> actualDelta = new LinkedHashMap<>();
            candidate.move().unitDelta().forEach((pattern, coefficient) ->
                    actualDelta.put(pattern, coefficient * candidate.scale()));
            neighbors.add(neighbor(
                    candidate.solution(),
                    candidate.stateSignature(),
                    actualDelta,
                    Layer.UNIT_TWO_FOR_TWO,
                    preferredPatterns,
                    preferredWidths));
        }
        neighbors.sort(Neighbor.STRUCTURAL_ORDER);
        return new NeighborBatch(
                List.copyOf(neighbors), usageRejected, visitedRejected, invalid);
    }

    private static NeighborBatch sparseNeighbors(
            SparseProductionNeutralMoveEnumerator.Result result,
            Map<Integer, Integer> expectedProduction,
            int expectedCars,
            int expectedWaste,
            int totalWidth,
            Set<String> preferredPatterns,
            Set<Integer> preferredWidths,
            Set<String> visitedStates) {
        List<Neighbor> neighbors = new ArrayList<>();
        int usageRejected = 0;
        int visitedRejected = 0;
        int invalid = 0;
        for (SparseProductionNeutralMoveEnumerator.Candidate candidate
                : result.candidates()) {
            if (!validInvariants(
                    candidate.solution(), expectedProduction, expectedCars,
                    expectedWaste, totalWidth)) {
                invalid++;
                continue;
            }
            if (!usageShapeCompatible(candidate.solution())) {
                usageRejected++;
                continue;
            }
            if (visitedStates.contains(candidate.stateSignature())) {
                visitedRejected++;
                continue;
            }
            neighbors.add(neighbor(
                    candidate.solution(),
                    candidate.stateSignature(),
                    candidate.delta(),
                    Layer.SPARSE_UP_TO_THREE,
                    preferredPatterns,
                    preferredWidths));
        }
        neighbors.sort(Neighbor.STRUCTURAL_ORDER);
        return new NeighborBatch(
                List.copyOf(neighbors), usageRejected, visitedRejected, invalid);
    }

    private static Neighbor neighbor(
            Map<PatternCandidate, Integer> solution,
            String stateSignature,
            Map<PatternCandidate, Integer> delta,
            Layer layer,
            Set<String> preferredPatterns,
            Set<Integer> preferredWidths) {
        int removedWitnessPatterns = 0;
        int removedWitnessCars = 0;
        int movedCars = 0;
        Set<Integer> addedWidths = new TreeSet<>();
        for (Map.Entry<PatternCandidate, Integer> entry : delta.entrySet()) {
            if (entry.getValue() < 0) {
                if (preferredPatterns.contains(entry.getKey().signature())) {
                    removedWitnessPatterns++;
                    removedWitnessCars += -entry.getValue();
                }
            } else {
                movedCars += entry.getValue();
                addedWidths.addAll(entry.getKey().getPattern().keySet());
            }
        }
        addedWidths.retainAll(preferredWidths);
        return new Neighbor(
                solution,
                stateSignature,
                delta,
                layer,
                solution.size(),
                removedWitnessPatterns,
                removedWitnessCars,
                addedWidths.size(),
                movedCars);
    }

    private static EvaluationBatch evaluate(
            List<Neighbor> neighbors,
            int currentBestGroups,
            List<SolverOrderItem> orderItems,
            SolverParameters parameters,
            Options options,
            OrderCompatibilityKernelAnalyzer.Options fullOptions) {
        if (neighbors.isEmpty()) {
            return new EvaluationBatch(List.of(), 0, 0, true);
        }
        int phase2Count = Math.min(options.phase2CandidateLimit(), neighbors.size());
        List<FastUpperBound> fastResults = new ArrayList<>(phase2Count);
        for (int index = 0; index < phase2Count; index++) {
            fastResults.add(phase2(neighbors.get(index), orderItems, parameters));
        }
        List<FastRankedNeighbor> ranked = new ArrayList<>(phase2Count);
        for (int index = 0; index < phase2Count; index++) {
            ranked.add(new FastRankedNeighbor(neighbors.get(index), fastResults.get(index)));
        }
        ranked.sort(FastRankedNeighbor.ORDER);

        int exactCount = Math.min(options.exactCandidateLimit(), ranked.size());
        List<CandidateEvaluation> evaluations = new ArrayList<>(exactCount);
        for (int index = 0; index < exactCount; index++) {
            FastRankedNeighbor candidate = ranked.get(index);
            evaluations.add(evaluateImprovement(
                    candidate.neighbor(),
                    candidate.fastUpperBound(),
                    currentBestGroups,
                    orderItems,
                    options,
                    fullOptions));
        }
        boolean improvementFound = evaluations.stream().anyMatch(evaluation ->
                evaluation.classification()
                        == Classification.IMPROVEMENT_CONFIRMED);
        if (!improvementFound && options.platformCandidateLimit() > 0) {
            int platformEvaluated = 0;
            for (int index = 0;
                    index < evaluations.size()
                            && platformEvaluated < options.platformCandidateLimit();
                    index++) {
                CandidateEvaluation evaluation = evaluations.get(index);
                if (evaluation.classification()
                        != Classification.NON_IMPROVING_PLATFORM_UNCHECKED) {
                    continue;
                }
                evaluations.set(index, evaluatePlatform(
                        evaluation,
                        currentBestGroups,
                        orderItems,
                        options));
                platformEvaluated++;
            }
        }
        boolean exhaustive = exactCount == neighbors.size()
                && evaluations.stream().allMatch(
                        CandidateEvaluation::improvementConclusive);
        return new EvaluationBatch(
                List.copyOf(evaluations), phase2Count, exactCount, exhaustive);
    }

    private static CandidateEvaluation evaluateImprovement(
            Neighbor neighbor,
            FastUpperBound fast,
            int currentBestGroups,
            List<SolverOrderItem> orderItems,
            Options options,
            OrderCompatibilityKernelAnalyzer.Options fullOptions) {
        OrderCompatibilityKernelAnalyzer.Options thresholdOptions =
                new OrderCompatibilityKernelAnalyzer.Options(
                        options.thresholdTimeLimitMs(),
                        0L,
                        options.maxConfigurations(),
                        options.exactNodeLimit(),
                        false);
        int improvementTarget = currentBestGroups - 1;
        OrderCompatibilityKernelAnalyzer.ThresholdAnalysis improvement =
                OrderCompatibilityKernelAnalyzer.checkThreshold(
                        neighbor.solution(),
                        orderItems,
                        improvementTarget,
                        1,
                        0,
                        thresholdOptions);
        if (improvement.feasible()) {
            OrderCompatibilityKernelAnalyzer.Analysis full =
                    OrderCompatibilityKernelAnalyzer.analyze(
                            neighbor.solution(), orderItems, fullOptions);
            Classification classification = full.groupOptimal()
                    && full.exactMinimumGroups() <= improvementTarget
                    ? Classification.IMPROVEMENT_CONFIRMED
                    : Classification.IMPROVEMENT_PENDING_CONFIRMATION;
            return new CandidateEvaluation(
                    neighbor, fast, improvement, null, full, classification);
        }
        if (improvement.status()
                != OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE) {
            return new CandidateEvaluation(
                    neighbor, fast, improvement, null, null,
                    Classification.UNKNOWN);
        }
        return new CandidateEvaluation(
                neighbor, fast, improvement, null, null,
                Classification.NON_IMPROVING_PLATFORM_UNCHECKED);
    }

    private static CandidateEvaluation evaluatePlatform(
            CandidateEvaluation evaluation,
            int currentBestGroups,
            List<SolverOrderItem> orderItems,
            Options options) {
        OrderCompatibilityKernelAnalyzer.Options thresholdOptions =
                new OrderCompatibilityKernelAnalyzer.Options(
                        options.thresholdTimeLimitMs(),
                        0L,
                        options.maxConfigurations(),
                        options.exactNodeLimit(),
                        false);
        OrderCompatibilityKernelAnalyzer.ThresholdAnalysis current =
                OrderCompatibilityKernelAnalyzer.checkThreshold(
                        evaluation.neighbor().solution(),
                        orderItems,
                        currentBestGroups,
                        1,
                        0,
                        thresholdOptions);
        Classification classification = classifyPlatformIdentity(
                evaluation.improvementThreshold().status(), current.status());
        return new CandidateEvaluation(
                evaluation.neighbor(),
                evaluation.fastUpperBound(),
                evaluation.improvementThreshold(),
                current,
                evaluation.fullAnalysis(),
                classification);
    }

    static Classification classifyPlatformIdentity(
            OrderCompatibilityKernelAnalyzer.ThresholdStatus improvementStatus,
            OrderCompatibilityKernelAnalyzer.ThresholdStatus currentStatus) {
        Objects.requireNonNull(improvementStatus, "improvementStatus");
        Objects.requireNonNull(currentStatus, "currentStatus");
        if (improvementStatus
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE) {
            return Classification.IMPROVEMENT_PENDING_CONFIRMATION;
        }
        if (improvementStatus
                != OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE) {
            return Classification.UNKNOWN;
        }
        if (currentStatus
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.FEASIBLE) {
            return Classification.PLATFORM;
        }
        if (currentStatus
                == OrderCompatibilityKernelAnalyzer.ThresholdStatus.INFEASIBLE) {
            return Classification.WORSE;
        }
        return Classification.UNKNOWN;
    }

    private static FastUpperBound phase2(
            Neighbor neighbor,
            List<SolverOrderItem> orderItems,
            SolverParameters parameters) {
        return fastUpperBound(neighbor.solution(), orderItems, parameters);
    }

    static FastUpperBound fastUpperBound(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> orderItems,
            SolverParameters parameters) {
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(orderItems, "orderItems");
        Objects.requireNonNull(parameters, "parameters");
        long startedAt = System.currentTimeMillis();
        try {
            Phase2SequenceGroupSolver.SolveResult result =
                    new Phase2SequenceGroupSolver(parameters)
                            .solveWithSolution(solution, orderItems);
            if (result == null || result.assignments() == null) {
                return new FastUpperBound(
                        FastStatus.NO_SOLUTION, -1, -1, -1,
                        System.currentTimeMillis() - startedAt);
            }
            int groups = 0;
            int odd = 0;
            int one = 0;
            for (Map.Entry<PatternCandidate,
                    List<AssignmentMIPSolver.AssignmentBlock>> entry
                    : result.assignments().entrySet()) {
                int assignedCars = 0;
                for (AssignmentMIPSolver.AssignmentBlock block : entry.getValue()) {
                    groups++;
                    assignedCars += block.getCount();
                    if (block.getCount() % 2 != 0) {
                        odd++;
                    }
                    if (block.getCount() == 1) {
                        one++;
                    }
                }
                if (assignedCars != solution
                        .getOrDefault(entry.getKey(), 0)) {
                    return new FastUpperBound(
                            FastStatus.INVALID, groups, odd, one,
                            System.currentTimeMillis() - startedAt);
                }
            }
            int totalAssigned = result.assignments().values().stream()
                    .flatMap(List::stream)
                    .mapToInt(AssignmentMIPSolver.AssignmentBlock::getCount)
                    .sum();
            FastStatus status = totalAssigned == totalCars(solution)
                    ? FastStatus.FEASIBLE : FastStatus.INVALID;
            return new FastUpperBound(
                    status, groups, odd, one,
                    System.currentTimeMillis() - startedAt);
        } catch (RuntimeException exception) {
            return new FastUpperBound(
                    FastStatus.ERROR, -1, -1, -1,
                    System.currentTimeMillis() - startedAt);
        }
    }

    private static EnqueueResult enqueuePlatforms(
            List<CandidateEvaluation> evaluations,
            int parentDepth,
            Options options,
            Deque<SearchState> frontier,
            Set<String> visitedStates) {
        if (parentDepth >= options.maxDepth()) {
            return new EnqueueResult(0, 0L);
        }
        int capacity = Math.max(0, options.platformCapacity() - frontier.size());
        int enqueued = 0;
        long witnessRefreshMs = 0L;
        for (CandidateEvaluation evaluation : evaluations) {
            if (enqueued >= capacity) {
                break;
            }
            if (evaluation.classification() != Classification.PLATFORM
                    || evaluation.currentThreshold() == null
                    || !evaluation.currentThreshold().feasible()) {
                continue;
            }
            if (!visitedStates.add(evaluation.neighbor().stateSignature())) {
                continue;
            }
            frontier.addLast(new SearchState(
                    evaluation.neighbor().solution(),
                    evaluation.neighbor().stateSignature(),
                    parentDepth + 1,
                    evaluation.currentThreshold().splitWitnesses()));
            witnessRefreshMs += evaluation.currentThreshold().totalElapsedMs();
            enqueued++;
        }
        return new EnqueueResult(enqueued, witnessRefreshMs);
    }

    private static SparseProductionNeutralMoveEnumerator.Options withPreferences(
            SparseProductionNeutralMoveEnumerator.Options source,
            Set<String> preferredPatterns,
            Set<Integer> preferredWidths) {
        return new SparseProductionNeutralMoveEnumerator.Options(
                source.totalTimeLimitMs(),
                source.masterSolveTimeLimitMs(),
                source.subproblemTimeLimitMs(),
                source.nodeLimit(),
                source.maxSupports(),
                source.maxCoefficientSolutions(),
                source.maxUniqueStates(),
                preferredPatterns,
                preferredWidths);
    }

    private static Set<String> splitPatternSignatures(
            List<OrderCompatibilityKernelAnalyzer.PatternSplit> witnesses) {
        Set<String> signatures = new LinkedHashSet<>();
        witnesses.forEach(witness -> signatures.add(witness.patternSignature()));
        return Set.copyOf(signatures);
    }

    private static Set<Integer> splitWidths(
            List<OrderCompatibilityKernelAnalyzer.PatternSplit> witnesses) {
        Set<Integer> widths = new LinkedHashSet<>();
        witnesses.forEach(witness -> widths.addAll(witness.varyingWidths()));
        return Set.copyOf(widths);
    }

    private static boolean validInvariants(
            Map<PatternCandidate, Integer> solution,
            Map<Integer, Integer> expectedProduction,
            int expectedCars,
            int expectedWaste,
            int totalWidth) {
        return solution.values().stream().allMatch(value -> value != null && value > 0)
                && production(solution).equals(expectedProduction)
                && totalCars(solution) == expectedCars
                && totalWaste(solution, totalWidth) == expectedWaste;
    }

    private static boolean usageShapeCompatible(
            Map<PatternCandidate, Integer> solution) {
        return solution.values().stream().filter(value -> value % 2 != 0).count() == 1
                && solution.values().stream().noneMatch(value -> value == 1);
    }

    private static Map<Integer, Integer> production(
            Map<PatternCandidate, Integer> solution) {
        Map<Integer, Integer> production = new TreeMap<>();
        solution.forEach((pattern, usage) -> pattern.getPattern().forEach(
                (width, coefficient) -> production.merge(
                        width, coefficient * usage, Integer::sum)));
        return production;
    }

    private static int totalCars(Map<PatternCandidate, Integer> solution) {
        return solution.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int totalWaste(
            Map<PatternCandidate, Integer> solution,
            int totalWidth) {
        return solution.entrySet().stream()
                .mapToInt(entry -> entry.getKey().getRealWaste(totalWidth)
                        * entry.getValue())
                .sum();
    }

    private static String stateSignature(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> solution) {
        Map<String, Integer> usageBySignature = new TreeMap<>();
        solution.forEach((pattern, usage) ->
                usageBySignature.put(pattern.signature(), usage));
        return usageBySignature.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    enum SearchStatus {
        IMPROVED,
        NO_IMPROVEMENT_PROVEN_IN_SEARCHED_NEIGHBORHOOD,
        INCONCLUSIVE,
        BASELINE_UNPROVEN
    }

    enum Layer {
        UNIT_TWO_FOR_TWO,
        SPARSE_UP_TO_THREE
    }

    enum Classification {
        IMPROVEMENT_CONFIRMED,
        IMPROVEMENT_PENDING_CONFIRMATION,
        PLATFORM,
        WORSE,
        NON_IMPROVING_PLATFORM_UNCHECKED,
        UNKNOWN
    }

    enum FastStatus {
        FEASIBLE,
        NO_SOLUTION,
        INVALID,
        ERROR
    }

    record Options(
            int phase2CandidateLimit,
            int exactCandidateLimit,
            int platformCandidateLimit,
            int platformCapacity,
            int maxExpandedStates,
            int maxDepth,
            boolean enableSparseLayer,
            long thresholdTimeLimitMs,
            long fullAnalysisGroupTimeLimitMs,
            long fullAnalysisShapeTimeLimitMs,
            long maxConfigurations,
            long exactNodeLimit,
            SparseProductionNeutralMoveEnumerator.Options sparseOptions) {

        Options {
            if (phase2CandidateLimit <= 0
                    || exactCandidateLimit <= 0
                    || platformCandidateLimit < 0
                    || platformCapacity < 0
                    || maxExpandedStates <= 0
                    || maxDepth < 0) {
                throw new IllegalArgumentException("search counts are outside their domain");
            }
            if (thresholdTimeLimitMs <= 0
                    || fullAnalysisGroupTimeLimitMs <= 0
                    || fullAnalysisShapeTimeLimitMs < 0
                    || maxConfigurations <= 0) {
                throw new IllegalArgumentException("exact evaluation limits are invalid");
            }
            sparseOptions = Objects.requireNonNull(sparseOptions);
        }
    }

    record FastUpperBound(
            FastStatus status,
            int groups,
            int oddGroups,
            int oneGroups,
            long elapsedMs) {

        boolean targetShapeFeasible() {
            return status == FastStatus.FEASIBLE
                    && oddGroups == 1
                    && oneGroups == 0;
        }
    }

    record CandidateEvaluation(
            Neighbor neighbor,
            FastUpperBound fastUpperBound,
            OrderCompatibilityKernelAnalyzer.ThresholdAnalysis improvementThreshold,
            OrderCompatibilityKernelAnalyzer.ThresholdAnalysis currentThreshold,
            OrderCompatibilityKernelAnalyzer.Analysis fullAnalysis,
            Classification classification) {

        boolean improvementConclusive() {
            return classification == Classification.IMPROVEMENT_CONFIRMED
                    || classification == Classification.NON_IMPROVING_PLATFORM_UNCHECKED
                    || classification == Classification.PLATFORM
                    || classification == Classification.WORSE;
        }
    }

    record LayerRun(
            String parentStateSignature,
            int depth,
            Layer layer,
            long generatedStates,
            int usageRejected,
            int visitedRejected,
            int invalidInvariants,
            int eligibleNeighbors,
            int phase2Evaluated,
            int exactEvaluated,
            boolean moveGenerationExhausted,
            boolean exactEvaluationExhausted,
            long generationElapsedMs,
            SparseProductionNeutralMoveEnumerator.Status sparseStatus,
            SparseProductionNeutralMoveEnumerator.Metrics sparseMetrics) {
    }

    record SearchResult(
            SearchStatus status,
            Map<PatternCandidate, Integer> baseline,
            OrderCompatibilityKernelAnalyzer.Analysis baselineAnalysis,
            Map<PatternCandidate, Integer> bestSolution,
            OrderCompatibilityKernelAnalyzer.Analysis bestAnalysis,
            List<LayerRun> layerRuns,
            List<CandidateEvaluation> evaluations,
            boolean moveGenerationExhausted,
            boolean exactEvaluationExhausted,
            long witnessRefreshMs,
            long totalElapsedMs) {

        SearchResult {
            baseline = Map.copyOf(baseline);
            bestSolution = Map.copyOf(bestSolution);
            layerRuns = List.copyOf(layerRuns);
            evaluations = List.copyOf(evaluations);
        }
    }

    private record SearchState(
            Map<PatternCandidate, Integer> solution,
            String stateSignature,
            int depth,
            List<OrderCompatibilityKernelAnalyzer.PatternSplit> witnesses) {

        SearchState {
            solution = Map.copyOf(solution);
            witnesses = List.copyOf(witnesses);
        }
    }

    record Neighbor(
            Map<PatternCandidate, Integer> solution,
            String stateSignature,
            Map<PatternCandidate, Integer> delta,
            Layer layer,
            int supportCount,
            int removedWitnessPatterns,
            int removedWitnessCars,
            int addedPreferredWidths,
            int movedCars) {

        private static final Comparator<Neighbor> STRUCTURAL_ORDER =
                Comparator.comparingInt(Neighbor::supportCount).reversed()
                        .thenComparing(
                                Comparator.comparingInt(
                                        Neighbor::removedWitnessPatterns).reversed())
                        .thenComparing(
                                Comparator.comparingInt(
                                        Neighbor::removedWitnessCars).reversed())
                        .thenComparing(
                                Comparator.comparingInt(
                                        Neighbor::addedPreferredWidths).reversed())
                        .thenComparing(
                                Comparator.comparingInt(Neighbor::movedCars).reversed())
                        .thenComparing(Neighbor::stateSignature);

        Neighbor {
            solution = Map.copyOf(solution);
            delta = Map.copyOf(delta);
        }
    }

    private record NeighborBatch(
            List<Neighbor> neighbors,
            int usageRejected,
            int visitedRejected,
            int invalidInvariants) {
    }

    private record FastRankedNeighbor(
            Neighbor neighbor,
            FastUpperBound fastUpperBound) {

        private static final Comparator<FastRankedNeighbor> ORDER =
                Comparator.comparing(
                                (FastRankedNeighbor value) ->
                                        !value.fastUpperBound().targetShapeFeasible())
                        .thenComparingInt(value ->
                                value.fastUpperBound().groups() < 0
                                        ? Integer.MAX_VALUE
                                        : value.fastUpperBound().groups())
                        .thenComparing(value -> value.neighbor(),
                                Neighbor.STRUCTURAL_ORDER);
    }

    private record EvaluationBatch(
            List<CandidateEvaluation> evaluations,
            int phase2Evaluated,
            int exactEvaluated,
            boolean exactEvaluationExhausted) {
    }

    private record EnqueueResult(int count, long witnessRefreshMs) {
    }
}
