package test.demo.apsmodule.generator.NewSolver.mip;

import test.demo.apsmodule.service.SolverOrderItem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only message demand view used by A-layer pattern selection.
 */
public final class PatternAlignmentContext {

    private static final PatternAlignmentContext EMPTY = new PatternAlignmentContext(Collections.emptyMap());

    private final Map<Integer, Map<String, Integer>> demandByWidthAndMessage;
    private PatternAlignmentContext(
            Map<Integer, Map<String, Integer>> demandByWidthAndMessage) {
        this.demandByWidthAndMessage = copyNested(demandByWidthAndMessage);
    }

    public static PatternAlignmentContext empty() {
        return EMPTY;
    }

    public static PatternAlignmentContext from(List<SolverOrderItem> items) {
        if (items == null || items.isEmpty()) {
            return empty();
        }

        Map<Integer, Map<String, Integer>> byWidth = new LinkedHashMap<>();
        for (SolverOrderItem item : items) {
            if (item == null || item.getDemand() <= 0) {
                continue;
            }
            int width = item.getWidth();
            String message = normalizeMessage(item.getMessageText());
            int demand = item.getDemand();
            byWidth.computeIfAbsent(width, ignored -> new LinkedHashMap<>())
                    .merge(message, demand, Integer::sum);
        }
        if (byWidth.isEmpty()) {
            return empty();
        }
        return new PatternAlignmentContext(byWidth);
    }

    public boolean isEmpty() {
        return demandByWidthAndMessage.isEmpty();
    }

    Map<String, Integer> messagesForWidth(int width) {
        return demandByWidthAndMessage.getOrDefault(width, Collections.emptyMap());
    }

    int widthDemand(int width) {
        return messagesForWidth(width).values().stream().mapToInt(Integer::intValue).sum();
    }

    double overlapRatio(int widthA, int widthB) {
        Map<String, Integer> messagesA = messagesForWidth(widthA);
        Map<String, Integer> messagesB = messagesForWidth(widthB);
        if (messagesA.isEmpty() || messagesB.isEmpty()) {
            return 0.0;
        }

        int shared = 0;
        for (Map.Entry<String, Integer> entry : messagesA.entrySet()) {
            int demandA = entry.getValue();
            int demandB = messagesB.getOrDefault(entry.getKey(), 0);
            shared += Math.min(demandA, demandB);
        }
        int denominator = Math.max(widthDemand(widthA), widthDemand(widthB));
        return denominator == 0 ? 0.0 : (double) shared / denominator;
    }

    private static <K, V> Map<K, Map<V, Integer>> copyNested(Map<K, Map<V, Integer>> source) {
        Map<K, Map<V, Integer>> copy = new LinkedHashMap<>();
        for (Map.Entry<K, Map<V, Integer>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String normalizeMessage(String message) {
        return Objects.toString(message, "").trim();
    }
}
