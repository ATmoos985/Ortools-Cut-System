package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Exhaustively enumerates the one-step unit 2-for-2 neighborhood of a fixed
 * integer pattern-usage state.
 */
final class UnitTwoForTwoMoveEnumerator {

    private UnitTwoForTwoMoveEnumerator() {
    }

    static Result enumerate(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upperBounds) {
        Objects.requireNonNull(universe, "universe");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(upperBounds, "upperBounds");
        long startedAt = System.currentTimeMillis();

        IndexedUniverse indexed = indexUniverse(universe, current, upperBounds);
        List<Integer> support = new ArrayList<>();
        for (int index = 0; index < indexed.patterns().size(); index++) {
            if (indexed.current()[index] > 0) {
                support.add(index);
            }
        }

        long supportPairCount = ((long) support.size() * (support.size() + 1)) / 2;
        long feasibleSourcePairs = 0L;
        long complementScans = 0L;
        long complementHashHits = 0L;
        long exactPairMatches = 0L;
        long zeroMoves = 0L;
        long boundRejectedMoves = 0L;
        long duplicateMoves = 0L;
        Map<String, NormalizedMove> movesBySignature = new LinkedHashMap<>();

        for (int leftPosition = 0; leftPosition < support.size(); leftPosition++) {
            int left = support.get(leftPosition);
            for (int rightPosition = leftPosition;
                    rightPosition < support.size(); rightPosition++) {
                int right = support.get(rightPosition);
                int sourceMultiplicity = left == right ? 2 : 1;
                if (indexed.current()[left] < sourceMultiplicity) {
                    continue;
                }
                feasibleSourcePairs++;
                PatternVector source = indexed.vectors().get(left)
                        .plus(indexed.vectors().get(right));

                for (int targetLeft = 0;
                        targetLeft < indexed.patterns().size(); targetLeft++) {
                    complementScans++;
                    PatternVector firstTarget = indexed.vectors().get(targetLeft);
                    long complementHash = source.hash() - firstTarget.hash();
                    List<Integer> targetRights = indexed.indicesByHash()
                            .get(complementHash);
                    if (targetRights == null) {
                        continue;
                    }
                    complementHashHits++;
                    for (int targetRight : targetRights) {
                        if (targetLeft > targetRight) {
                            continue;
                        }
                        if (!source.equalsSum(
                                firstTarget, indexed.vectors().get(targetRight))) {
                            continue;
                        }
                        exactPairMatches++;
                        Normalization normalization = normalize(
                                left, right, targetLeft, targetRight,
                                indexed.current(), indexed.upperBounds());
                        if (normalization.zero()) {
                            zeroMoves++;
                            continue;
                        }
                        if (normalization.boundRejected()) {
                            boundRejectedMoves++;
                            continue;
                        }
                        NormalizedMove move = normalization.move();
                        if (movesBySignature.putIfAbsent(
                                move.signature(), move) != null) {
                            duplicateMoves++;
                        }
                    }
                }
            }
        }

        long expandedScaleCandidates = 0L;
        long duplicateStates = 0L;
        Map<String, Candidate> candidatesByState = new LinkedHashMap<>();
        Map<Integer, Integer> maxScaleDistribution = new TreeMap<>();
        for (NormalizedMove move : movesBySignature.values()) {
            maxScaleDistribution.merge(move.maxScale(), 1, Integer::sum);
            for (int scale = 1; scale <= move.maxScale(); scale++) {
                expandedScaleCandidates++;
                int[] next = indexed.current().clone();
                for (int position = 0; position < move.indices().length; position++) {
                    int patternIndex = move.indices()[position];
                    next[patternIndex] += scale * move.coefficients()[position];
                }
                String stateSignature = stateSignature(indexed.patterns(), next);
                Candidate candidate = new Candidate(
                        publicMove(indexed.patterns(), move),
                        scale,
                        solution(indexed.patterns(), next),
                        stateSignature);
                if (candidatesByState.putIfAbsent(
                        stateSignature, candidate) != null) {
                    duplicateStates++;
                }
            }
        }

        Metrics metrics = new Metrics(
                indexed.patterns().size(),
                support.size(),
                supportPairCount,
                feasibleSourcePairs,
                complementScans,
                complementHashHits,
                exactPairMatches,
                zeroMoves,
                boundRejectedMoves,
                movesBySignature.size(),
                duplicateMoves,
                expandedScaleCandidates,
                candidatesByState.size(),
                duplicateStates,
                Map.copyOf(maxScaleDistribution),
                System.currentTimeMillis() - startedAt);
        return new Result(List.copyOf(candidatesByState.values()), metrics, true);
    }

    private static IndexedUniverse indexUniverse(
            List<PatternCandidate> universe,
            Map<PatternCandidate, Integer> current,
            Map<PatternCandidate, Integer> upperBounds) {
        if (universe.isEmpty()) {
            throw new IllegalArgumentException("universe must not be empty");
        }
        List<PatternCandidate> patterns = universe.stream()
                .map(pattern -> Objects.requireNonNull(pattern,
                        "universe contains null"))
                .sorted(Comparator.comparing(PatternCandidate::signature))
                .toList();
        Map<String, PatternCandidate> bySignature = new LinkedHashMap<>();
        for (PatternCandidate pattern : patterns) {
            if (bySignature.put(pattern.signature(), pattern) != null) {
                throw new IllegalArgumentException(
                        "duplicate universe signature: " + pattern.signature());
            }
        }
        for (PatternCandidate pattern : current.keySet()) {
            if (!bySignature.containsKey(pattern.signature())) {
                throw new IllegalArgumentException(
                        "current pattern is outside universe: " + pattern.signature());
            }
        }

        Set<Integer> widthSet = new TreeSet<>();
        patterns.forEach(pattern -> widthSet.addAll(pattern.getPattern().keySet()));
        List<Integer> widths = List.copyOf(widthSet);
        Map<String, Integer> currentBySignature = new HashMap<>();
        current.forEach((pattern, value) -> {
            if (value == null || value < 0) {
                throw new IllegalArgumentException(
                        "current usage must not be negative: " + pattern.signature());
            }
            currentBySignature.put(pattern.signature(), value);
        });
        Map<String, Integer> upperBySignature = new HashMap<>();
        upperBounds.forEach((pattern, value) -> {
            if (value == null || value < 0) {
                throw new IllegalArgumentException(
                        "upper bound must not be negative: " + pattern.signature());
            }
            upperBySignature.put(pattern.signature(), value);
        });

        int[] currentValues = new int[patterns.size()];
        int[] upperValues = new int[patterns.size()];
        List<PatternVector> vectors = new ArrayList<>(patterns.size());
        Map<Long, List<Integer>> indicesByHash = new HashMap<>();
        for (int index = 0; index < patterns.size(); index++) {
            PatternCandidate pattern = patterns.get(index);
            int usage = currentBySignature.getOrDefault(pattern.signature(), 0);
            Integer upper = upperBySignature.get(pattern.signature());
            if (upper == null) {
                throw new IllegalArgumentException(
                        "missing upper bound: " + pattern.signature());
            }
            if (usage > upper) {
                throw new IllegalArgumentException(
                        "current usage exceeds upper bound: " + pattern.signature());
            }
            currentValues[index] = usage;
            upperValues[index] = upper;
            int[] coefficients = new int[widths.size()];
            long hash = 0L;
            for (int widthIndex = 0; widthIndex < widths.size(); widthIndex++) {
                int coefficient = pattern.getPattern()
                        .getOrDefault(widths.get(widthIndex), 0);
                coefficients[widthIndex] = coefficient;
                hash += coefficient * hashWeight(widths.get(widthIndex));
            }
            PatternVector vector = new PatternVector(coefficients, hash);
            vectors.add(vector);
            indicesByHash.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(index);
        }
        indicesByHash.replaceAll((hash, indices) -> List.copyOf(indices));
        return new IndexedUniverse(
                patterns,
                List.copyOf(vectors),
                currentValues,
                upperValues,
                Collections.unmodifiableMap(indicesByHash));
    }

    private static long hashWeight(int width) {
        long value = Integer.toUnsignedLong(width) + 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static Normalization normalize(
            int sourceLeft,
            int sourceRight,
            int targetLeft,
            int targetRight,
            int[] current,
            int[] upperBounds) {
        TreeMap<Integer, Integer> delta = new TreeMap<>();
        delta.merge(sourceLeft, -1, Integer::sum);
        delta.merge(sourceRight, -1, Integer::sum);
        delta.merge(targetLeft, 1, Integer::sum);
        delta.merge(targetRight, 1, Integer::sum);
        delta.values().removeIf(value -> value == 0);
        if (delta.isEmpty()) {
            return new Normalization(null, true, false);
        }

        int maxScale = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> entry : delta.entrySet()) {
            int index = entry.getKey();
            int coefficient = entry.getValue();
            if (coefficient < 0) {
                maxScale = Math.min(
                        maxScale, current[index] / -coefficient);
            } else {
                maxScale = Math.min(
                        maxScale,
                        (upperBounds[index] - current[index]) / coefficient);
            }
        }
        if (maxScale < 1 || maxScale == Integer.MAX_VALUE) {
            return new Normalization(null, false, true);
        }

        int[] indices = new int[delta.size()];
        int[] coefficients = new int[delta.size()];
        StringBuilder signature = new StringBuilder();
        int position = 0;
        for (Map.Entry<Integer, Integer> entry : delta.entrySet()) {
            indices[position] = entry.getKey();
            coefficients[position] = entry.getValue();
            if (position > 0) {
                signature.append(';');
            }
            signature.append(entry.getKey()).append(':').append(entry.getValue());
            position++;
        }
        return new Normalization(
                new NormalizedMove(
                        indices, coefficients, maxScale, signature.toString()),
                false,
                false);
    }

    private static Move publicMove(
            List<PatternCandidate> patterns,
            NormalizedMove move) {
        Map<PatternCandidate, Integer> delta = new LinkedHashMap<>();
        for (int position = 0; position < move.indices().length; position++) {
            delta.put(patterns.get(move.indices()[position]),
                    move.coefficients()[position]);
        }
        return new Move(delta, move.maxScale(), move.signature());
    }

    private static Map<PatternCandidate, Integer> solution(
            List<PatternCandidate> patterns,
            int[] values) {
        Map<PatternCandidate, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index++) {
            if (values[index] > 0) {
                result.put(patterns.get(index), values[index]);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static String stateSignature(
            List<PatternCandidate> patterns,
            int[] values) {
        StringBuilder signature = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (values[index] <= 0) {
                continue;
            }
            if (!signature.isEmpty()) {
                signature.append(';');
            }
            signature.append(patterns.get(index).signature())
                    .append('=').append(values[index]);
        }
        return signature.toString();
    }

    record Move(
            Map<PatternCandidate, Integer> unitDelta,
            int maxScale,
            String signature) {

        Move {
            unitDelta = Collections.unmodifiableMap(new LinkedHashMap<>(unitDelta));
            signature = Objects.requireNonNull(signature);
        }
    }

    record Candidate(
            Move move,
            int scale,
            Map<PatternCandidate, Integer> solution,
            String stateSignature) {

        Candidate {
            move = Objects.requireNonNull(move);
            if (scale <= 0 || scale > move.maxScale()) {
                throw new IllegalArgumentException("scale is outside move bounds");
            }
            solution = Collections.unmodifiableMap(new LinkedHashMap<>(solution));
            stateSignature = Objects.requireNonNull(stateSignature);
        }
    }

    record Metrics(
            int universeSize,
            int supportSize,
            long supportPairCount,
            long feasibleSourcePairs,
            long complementScans,
            long complementHashHits,
            long exactPairMatches,
            long zeroMoves,
            long boundRejectedMoves,
            long normalizedMoves,
            long duplicateMoves,
            long expandedScaleCandidates,
            long uniqueStates,
            long duplicateStates,
            Map<Integer, Integer> maxScaleDistribution,
            long elapsedMs) {

        Metrics {
            maxScaleDistribution = Map.copyOf(maxScaleDistribution);
        }
    }

    record Result(
            List<Candidate> candidates,
            Metrics metrics,
            boolean exhausted) {

        Result {
            candidates = List.copyOf(candidates);
            metrics = Objects.requireNonNull(metrics);
        }
    }

    private record IndexedUniverse(
            List<PatternCandidate> patterns,
            List<PatternVector> vectors,
            int[] current,
            int[] upperBounds,
            Map<Long, List<Integer>> indicesByHash) {
    }

    private record PatternVector(int[] coefficients, long hash) {

        PatternVector plus(PatternVector other) {
            int[] sum = new int[coefficients.length];
            for (int index = 0; index < coefficients.length; index++) {
                sum[index] = coefficients[index] + other.coefficients[index];
            }
            return new PatternVector(sum, hash + other.hash);
        }

        boolean equalsSum(PatternVector left, PatternVector right) {
            if (coefficients.length != left.coefficients.length
                    || coefficients.length != right.coefficients.length) {
                return false;
            }
            for (int index = 0; index < coefficients.length; index++) {
                if (coefficients[index]
                        != left.coefficients[index] + right.coefficients[index]) {
                    return false;
                }
            }
            return true;
        }
    }

    private record NormalizedMove(
            int[] indices,
            int[] coefficients,
            int maxScale,
            String signature) {
    }

    private record Normalization(
            NormalizedMove move,
            boolean zero,
            boolean boundRejected) {
    }
}
