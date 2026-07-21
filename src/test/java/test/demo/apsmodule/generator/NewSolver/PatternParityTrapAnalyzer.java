package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Research-only sparse elimination of pattern-usage equations.
 *
 * <p>For an exact solution {@code A * usage = demand}, a width row with one
 * remaining carrier forces that carrier's usage. Repeating this operation
 * explains direct and cascading odd/single-car usages without relying on a
 * dataset-specific width list.</p>
 */
final class PatternParityTrapAnalyzer {

    static final String PEEL_ORDER =
            "active-degree,residual-demand,width,pattern-signature";

    private static final Comparator<RowChoice> ROW_ORDER =
            Comparator.comparingInt(RowChoice::activeDegree)
                    .thenComparingInt(RowChoice::residualDemand)
                    .thenComparingInt(RowChoice::width)
                    .thenComparing(RowChoice::patternSignature);

    private PatternParityTrapAnalyzer() {
    }

    static Analysis analyze(
            Map<PatternCandidate, Integer> solution,
            Map<Integer, Integer> exactDemand) {
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(exactDemand, "exactDemand");

        Map<String, ActivePattern> active = new TreeMap<>();
        for (Map.Entry<PatternCandidate, Integer> entry : solution.entrySet()) {
            int usage = entry.getValue() == null ? 0 : entry.getValue();
            if (usage <= 0) {
                continue;
            }
            PatternCandidate pattern = entry.getKey();
            String signature = pattern.signature();
            ActivePattern previous = active.put(signature, new ActivePattern(pattern, usage));
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate pattern signature: " + signature);
            }
        }
        if (active.isEmpty()) {
            throw new IllegalArgumentException("solution must contain at least one used pattern");
        }

        Map<Integer, Integer> demand = positiveSorted(exactDemand);
        Map<Integer, Integer> production = production(active.values());
        if (!demand.equals(production)) {
            throw new IllegalArgumentException(
                    "parity peeling requires exact demand: demand=" + demand
                            + ", production=" + production);
        }

        Map<Integer, Integer> initialDegrees = degrees(demand.keySet(), active.values());
        Map<Integer, Integer> residual = new TreeMap<>(demand);
        List<PeelStep> steps = new ArrayList<>();

        while (true) {
            RowChoice choice = nextChoice(residual, active);
            if (choice == null) {
                break;
            }

            ActivePattern forced = active.get(choice.patternSignature());
            int coefficient = forced.pattern().getPattern().getOrDefault(choice.width(), 0);
            if (coefficient <= 0 || choice.residualDemand() % coefficient != 0) {
                throw new IllegalStateException("Non-integral forced usage at width "
                        + choice.width() + ": residual=" + choice.residualDemand()
                        + ", coefficient=" + coefficient);
            }
            int forcedUsage = choice.residualDemand() / coefficient;
            if (forcedUsage != forced.usage()) {
                throw new IllegalStateException("Forced usage disagrees with exact solution for "
                        + choice.patternSignature() + ": forced=" + forcedUsage
                        + ", actual=" + forced.usage());
            }

            TrapOrigin origin = initialDegrees.getOrDefault(choice.width(), 0) == 1
                    ? TrapOrigin.INITIAL_ANCHOR
                    : TrapOrigin.CASCADE;
            steps.add(new PeelStep(
                    steps.size() + 1,
                    choice.width(),
                    choice.activeDegree(),
                    choice.residualDemand(),
                    coefficient,
                    forcedUsage,
                    origin,
                    choice.patternSignature()));

            active.remove(choice.patternSignature());
            for (Map.Entry<Integer, Integer> cut : forced.pattern().getPattern().entrySet()) {
                int next = residual.getOrDefault(cut.getKey(), 0)
                        - cut.getValue() * forcedUsage;
                if (next < 0) {
                    throw new IllegalStateException("Negative residual at width "
                            + cut.getKey() + " after peeling " + choice.patternSignature());
                }
                residual.put(cut.getKey(), next);
            }
        }

        Set<String> corePatterns = new LinkedHashSet<>(active.keySet());
        List<OddUsage> unexplainedOdd = active.values().stream()
                .filter(entry -> entry.usage() % 2 != 0)
                .map(entry -> new OddUsage(entry.pattern().signature(), entry.usage()))
                .sorted(Comparator.comparing(OddUsage::patternSignature))
                .toList();
        int actualOdd = (int) solution.values().stream()
                .filter(Objects::nonNull)
                .filter(value -> value > 0 && value % 2 != 0)
                .count();
        int actualOne = (int) solution.values().stream()
                .filter(Objects::nonNull)
                .filter(value -> value == 1)
                .count();

        return new Analysis(
                PEEL_ORDER,
                Map.copyOf(initialDegrees),
                List.copyOf(steps),
                Set.copyOf(corePatterns),
                List.copyOf(unexplainedOdd),
                actualOdd,
                actualOne);
    }

    private static RowChoice nextChoice(
            Map<Integer, Integer> residual,
            Map<String, ActivePattern> active) {
        List<RowChoice> choices = new ArrayList<>();
        for (Map.Entry<Integer, Integer> row : residual.entrySet()) {
            if (row.getValue() <= 0) {
                continue;
            }
            List<String> carriers = new ArrayList<>();
            for (Map.Entry<String, ActivePattern> candidate : active.entrySet()) {
                if (candidate.getValue().pattern().getPattern()
                        .getOrDefault(row.getKey(), 0) > 0) {
                    carriers.add(candidate.getKey());
                }
            }
            if (carriers.isEmpty()) {
                throw new IllegalStateException("Positive residual has no active carrier: width="
                        + row.getKey() + ", residual=" + row.getValue());
            }
            if (carriers.size() == 1) {
                choices.add(new RowChoice(
                        row.getKey(), carriers.size(), row.getValue(), carriers.get(0)));
            }
        }
        return choices.stream().min(ROW_ORDER).orElse(null);
    }

    private static Map<Integer, Integer> positiveSorted(Map<Integer, Integer> values) {
        Map<Integer, Integer> result = new TreeMap<>();
        values.forEach((width, count) -> {
            if (count != null && count > 0) {
                result.put(width, count);
            }
        });
        return result;
    }

    private static Map<Integer, Integer> production(Collection<ActivePattern> patterns) {
        Map<Integer, Integer> result = new TreeMap<>();
        for (ActivePattern entry : patterns) {
            entry.pattern().getPattern().forEach((width, coefficient) ->
                    result.merge(width, coefficient * entry.usage(), Integer::sum));
        }
        return result;
    }

    private static Map<Integer, Integer> degrees(
            Set<Integer> widths,
            Collection<ActivePattern> patterns) {
        Map<Integer, Integer> result = new TreeMap<>();
        for (int width : widths) {
            int degree = 0;
            for (ActivePattern pattern : patterns) {
                if (pattern.pattern().getPattern().getOrDefault(width, 0) > 0) {
                    degree++;
                }
            }
            result.put(width, degree);
        }
        return result;
    }

    enum TrapOrigin {
        INITIAL_ANCHOR,
        CASCADE
    }

    record PeelStep(
            int order,
            int width,
            int activeDegree,
            int residualDemand,
            int coefficient,
            int forcedUsage,
            TrapOrigin origin,
            String patternSignature) {

        boolean forcesOdd() {
            return forcedUsage % 2 != 0;
        }

        boolean forcesOne() {
            return forcedUsage == 1;
        }
    }

    record OddUsage(String patternSignature, int usage) {
    }

    record Analysis(
            String peelOrder,
            Map<Integer, Integer> initialDegrees,
            List<PeelStep> steps,
            Set<String> corePatternSignatures,
            List<OddUsage> unexplainedOddUsages,
            int actualOddUsages,
            int actualOneUsages) {

        List<PeelStep> forcedOddSteps() {
            return steps.stream().filter(PeelStep::forcesOdd).toList();
        }

        List<PeelStep> forcedOneSteps() {
            return steps.stream().filter(PeelStep::forcesOne).toList();
        }

        Set<Integer> forcedOddWidths() {
            Set<Integer> widths = new LinkedHashSet<>();
            forcedOddSteps().forEach(step -> widths.add(step.width()));
            return Set.copyOf(widths);
        }

        Set<Integer> initialOddAnchorWidths() {
            Set<Integer> widths = new LinkedHashSet<>();
            forcedOddSteps().stream()
                    .filter(step -> step.origin() == TrapOrigin.INITIAL_ANCHOR)
                    .forEach(step -> widths.add(step.width()));
            return Set.copyOf(widths);
        }

        Set<Integer> cascadingOddWidths() {
            Set<Integer> widths = new LinkedHashSet<>();
            forcedOddSteps().stream()
                    .filter(step -> step.origin() == TrapOrigin.CASCADE)
                    .forEach(step -> widths.add(step.width()));
            return Set.copyOf(widths);
        }

        int explainedOddUsages() {
            return forcedOddSteps().size();
        }
    }

    private record ActivePattern(PatternCandidate pattern, int usage) {
    }

    private record RowChoice(
            int width,
            int activeDegree,
            int residualDemand,
            String patternSignature) {
    }
}
