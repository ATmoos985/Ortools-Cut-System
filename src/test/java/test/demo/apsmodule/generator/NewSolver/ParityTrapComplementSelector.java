package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Research-only, data-driven candidate selector for forced odd usages.
 *
 * <p>The selector never receives manual patterns or dataset-specific widths.
 * For each forced odd row it enumerates every coefficient present in the
 * supplied universe, classifies the residual/coefficient quotient, and keeps
 * bounded candidate buckets for coefficients with an even quotient. If no
 * even quotient exists, it keeps the smallest controlled odd quotient and
 * rejects a single-use quotient whenever an alternative exists.</p>
 */
final class ParityTrapComplementSelector {

    private ParityTrapComplementSelector() {
    }

    static Selection select(
            PatternParityTrapAnalyzer.Analysis baseline,
            Collection<PatternCandidate> universe,
            Collection<PatternCandidate> activePatterns,
            Map<Integer, Integer> demands,
            int totalWidth,
            int perCoefficientQuota,
            int maxCandidates) {
        if (perCoefficientQuota <= 0 || maxCandidates <= 0) {
            return new Selection(List.of(), Map.of(), Map.of());
        }

        Set<String> activeSignatures = new LinkedHashSet<>();
        activePatterns.forEach(pattern -> activeSignatures.add(pattern.signature()));
        Map<Integer, Target> targets = buildTargets(baseline, universe);

        List<CandidateScore> scores = universe.stream()
                .filter(pattern -> !activeSignatures.contains(pattern.signature()))
                .map(pattern -> score(pattern, targets, demands, totalWidth))
                .filter(score -> score.targetsHelped() > 0)
                .sorted(CandidateScore.BEST_FIRST)
                .toList();

        Map<String, CandidateScore> selected = new LinkedHashMap<>();
        for (Target target : targets.values()) {
            for (int coefficient : target.preferredCoefficients()) {
                scores.stream()
                        .filter(score -> score.pattern().getPattern()
                                .getOrDefault(target.width(), 0) == coefficient)
                        .limit(perCoefficientQuota)
                        .forEach(score -> selected.putIfAbsent(
                                score.pattern().signature(), score));
            }
        }

        List<CandidateScore> bounded = selected.values().stream()
                .sorted(CandidateScore.BEST_FIRST)
                .limit(maxCandidates)
                .toList();
        Map<String, CandidateScore> boundedBySignature = new LinkedHashMap<>();
        bounded.forEach(score -> boundedBySignature.put(
                score.pattern().signature(), score));

        Map<Integer, Integer> selectedPerTarget = new TreeMap<>();
        for (Target target : targets.values()) {
            int count = (int) bounded.stream()
                    .filter(score -> target.preferredCoefficients().contains(
                            score.pattern().getPattern().getOrDefault(target.width(), 0)))
                    .count();
            selectedPerTarget.put(target.width(), count);
        }

        return new Selection(
                bounded.stream().map(CandidateScore::pattern).toList(),
                Map.copyOf(targets),
                Map.copyOf(selectedPerTarget));
    }

    private static Map<Integer, Target> buildTargets(
            PatternParityTrapAnalyzer.Analysis baseline,
            Collection<PatternCandidate> universe) {
        Map<Integer, PatternParityTrapAnalyzer.PeelStep> trapByWidth = new TreeMap<>();
        for (PatternParityTrapAnalyzer.PeelStep step : baseline.forcedOddSteps()) {
            trapByWidth.putIfAbsent(step.width(), step);
        }

        Map<Integer, Target> targets = new TreeMap<>();
        for (Map.Entry<Integer, PatternParityTrapAnalyzer.PeelStep> entry
                : trapByWidth.entrySet()) {
            int width = entry.getKey();
            int residual = entry.getValue().residualDemand();
            Set<Integer> coefficients = new LinkedHashSet<>();
            universe.stream()
                    .map(pattern -> pattern.getPattern().getOrDefault(width, 0))
                    .filter(coefficient -> coefficient > 0)
                    .sorted()
                    .forEach(coefficients::add);

            List<CoefficientOption> options = coefficients.stream()
                    .map(coefficient -> option(coefficient, residual))
                    .toList();
            Set<Integer> preferred = preferredCoefficients(options);
            targets.put(width, new Target(
                    width,
                    residual,
                    entry.getValue().coefficient(),
                    List.copyOf(options),
                    Set.copyOf(preferred)));
        }
        return targets;
    }

    private static CoefficientOption option(int coefficient, int residual) {
        if (residual % coefficient != 0) {
            return new CoefficientOption(
                    coefficient, null, QuotientClass.REQUIRES_SPLIT);
        }
        int quotient = residual / coefficient;
        if (quotient == 1) {
            return new CoefficientOption(
                    coefficient, quotient, QuotientClass.SINGLE_USE);
        }
        if (quotient % 2 == 0) {
            return new CoefficientOption(
                    coefficient, quotient, QuotientClass.EVEN);
        }
        return new CoefficientOption(
                coefficient, quotient, QuotientClass.CONTROLLED_ODD);
    }

    private static Set<Integer> preferredCoefficients(List<CoefficientOption> options) {
        Set<Integer> even = new LinkedHashSet<>();
        options.stream()
                .filter(option -> option.quotientClass() == QuotientClass.EVEN)
                .map(CoefficientOption::coefficient)
                .forEach(even::add);
        if (!even.isEmpty()) {
            return even;
        }

        Set<Integer> controlledOdd = new LinkedHashSet<>();
        options.stream()
                .filter(option -> option.quotientClass() == QuotientClass.CONTROLLED_ODD)
                .sorted(Comparator.comparingInt(option -> option.quotient()))
                .map(CoefficientOption::coefficient)
                .forEach(controlledOdd::add);
        return controlledOdd;
    }

    private static CandidateScore score(
            PatternCandidate pattern,
            Map<Integer, Target> targets,
            Map<Integer, Integer> demands,
            int totalWidth) {
        int targetsHelped = 0;
        int evenTargets = 0;
        int controlledOddTargets = 0;
        for (Target target : targets.values()) {
            int coefficient = pattern.getPattern().getOrDefault(target.width(), 0);
            if (!target.preferredCoefficients().contains(coefficient)) {
                continue;
            }
            targetsHelped++;
            CoefficientOption option = target.options().stream()
                    .filter(candidate -> candidate.coefficient() == coefficient)
                    .findFirst()
                    .orElseThrow();
            if (option.quotientClass() == QuotientClass.EVEN) {
                evenTargets++;
            } else if (option.quotientClass() == QuotientClass.CONTROLLED_ODD) {
                controlledOddTargets++;
            }
        }

        int singleUseRisks = 0;
        int oddQuotientRisks = 0;
        long weightedCoverage = 0L;
        for (Map.Entry<Integer, Integer> cut : pattern.getPattern().entrySet()) {
            int demand = demands.getOrDefault(cut.getKey(), 0);
            weightedCoverage += (long) demand * cut.getValue();
            if (demand <= 0 || demand % cut.getValue() != 0) {
                continue;
            }
            int quotient = demand / cut.getValue();
            if (quotient == 1) {
                singleUseRisks++;
            }
            if (quotient % 2 != 0) {
                oddQuotientRisks++;
            }
        }
        int nonTargetConnections = (int) pattern.getPattern().keySet().stream()
                .filter(width -> !targets.containsKey(width))
                .count();

        return new CandidateScore(
                pattern,
                targetsHelped,
                evenTargets,
                controlledOddTargets,
                singleUseRisks,
                oddQuotientRisks,
                nonTargetConnections,
                pattern.getRealWaste(totalWidth),
                weightedCoverage);
    }

    enum QuotientClass {
        EVEN,
        CONTROLLED_ODD,
        SINGLE_USE,
        REQUIRES_SPLIT
    }

    record CoefficientOption(
            int coefficient,
            Integer quotient,
            QuotientClass quotientClass) {
    }

    record Target(
            int width,
            int residualDemand,
            int baselineCoefficient,
            List<CoefficientOption> options,
            Set<Integer> preferredCoefficients) {
    }

    record Selection(
            List<PatternCandidate> candidates,
            Map<Integer, Target> targets,
            Map<Integer, Integer> selectedPerTarget) {
    }

    private record CandidateScore(
            PatternCandidate pattern,
            int targetsHelped,
            int evenTargets,
            int controlledOddTargets,
            int singleUseRisks,
            int oddQuotientRisks,
            int nonTargetConnections,
            int realWaste,
            long weightedCoverage) {

        private static final Comparator<CandidateScore> BEST_FIRST =
                Comparator.comparingInt(CandidateScore::targetsHelped).reversed()
                        .thenComparing(Comparator.comparingInt(
                                CandidateScore::evenTargets).reversed())
                        .thenComparingInt(CandidateScore::singleUseRisks)
                        .thenComparingInt(CandidateScore::oddQuotientRisks)
                        .thenComparing(Comparator.comparingInt(
                                CandidateScore::nonTargetConnections).reversed())
                        .thenComparingInt(CandidateScore::realWaste)
                        .thenComparing(Comparator.comparingLong(
                                CandidateScore::weightedCoverage).reversed())
                        .thenComparing(score -> score.pattern().signature());
    }
}
