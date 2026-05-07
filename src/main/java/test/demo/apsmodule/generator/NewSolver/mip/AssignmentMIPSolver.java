package test.demo.apsmodule.generator.NewSolver.mip;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverOrderItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 5 assignment MIP.
 *
 * This model is a candidate generator. It minimizes a proxy upper bound on the
 * number of assignment blocks, not the real exported sequence-group count.
 */
public class AssignmentMIPSolver {

    private static final Logger log = LoggerFactory.getLogger(AssignmentMIPSolver.class);
    private static final long DEFAULT_STAGE5_TIME_LIMIT_MS = 30000L;

    private final SolverParameters params;

    /**
     * A contiguous batch of rolls inside one pattern that shares the same
     * width-to-message assignment.
     */
    public static class AssignmentBlock {
        private final Map<Integer, String> config;
        private final Map<Integer, List<String>> stationConfig;
        private final int count;

        public AssignmentBlock(Map<Integer, String> config, int count) {
            this(config, toStationConfig(config), count);
        }

        private AssignmentBlock(Map<Integer, String> config, Map<Integer, List<String>> stationConfig, int count) {
            this.config = copyConfig(config);
            this.stationConfig = copyStationConfig(stationConfig);
            this.count = count;
        }

        public static AssignmentBlock fromStationConfig(Map<Integer, List<String>> stationConfig, int count) {
            return new AssignmentBlock(toRepresentativeConfig(stationConfig), stationConfig, count);
        }

        public Map<Integer, String> getConfig() {
            return config;
        }

        public Map<Integer, List<String>> getStationConfig() {
            return stationConfig;
        }

        public int getCount() {
            return count;
        }

        private static Map<Integer, String> copyConfig(Map<Integer, String> source) {
            Map<Integer, String> copy = new LinkedHashMap<>();
            if (source != null) {
                copy.putAll(source);
            }
            return copy;
        }

        private static Map<Integer, List<String>> toStationConfig(Map<Integer, String> config) {
            Map<Integer, List<String>> stationConfig = new LinkedHashMap<>();
            if (config != null) {
                for (Map.Entry<Integer, String> entry : config.entrySet()) {
                    stationConfig.put(entry.getKey(), List.of(entry.getValue() == null ? "" : entry.getValue()));
                }
            }
            return stationConfig;
        }

        private static Map<Integer, String> toRepresentativeConfig(Map<Integer, List<String>> stationConfig) {
            Map<Integer, String> representative = new LinkedHashMap<>();
            if (stationConfig != null) {
                for (Map.Entry<Integer, List<String>> entry : stationConfig.entrySet()) {
                    List<String> messages = entry.getValue();
                    representative.put(entry.getKey(),
                            messages == null || messages.isEmpty() ? "" : messages.get(0));
                }
            }
            return representative;
        }

        private static Map<Integer, List<String>> copyStationConfig(Map<Integer, List<String>> source) {
            Map<Integer, List<String>> copy = new LinkedHashMap<>();
            if (source != null) {
                for (Map.Entry<Integer, List<String>> entry : source.entrySet()) {
                    List<String> messages = new ArrayList<>();
                    if (entry.getValue() != null) {
                        for (String message : entry.getValue()) {
                            messages.add(message == null ? "" : message);
                        }
                    }
                    copy.put(entry.getKey(), List.copyOf(messages));
                }
            }
            return copy;
        }
    }

    public AssignmentMIPSolver(SolverParameters params) {
        this.params = params;
    }

    public Map<PatternCandidate, List<AssignmentBlock>> solve(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> groupItems) {

        log.info("--- Stage 5: assignment candidate MIP ---");

        Map<String, Integer> detailedDemands = new LinkedHashMap<>();
        for (SolverOrderItem item : groupItems) {
            String key = demandKey(item.getWidth(), item.getMessageText());
            detailedDemands.merge(key, item.getDemand(), Integer::sum);
        }

        Map<Integer, List<String>> messagesByWidth = new LinkedHashMap<>();
        for (SolverOrderItem item : groupItems) {
            messagesByWidth.computeIfAbsent(item.getWidth(), key -> new ArrayList<>());
            List<String> messages = messagesByWidth.get(item.getWidth());
            if (!messages.contains(item.getMessageText())) {
                messages.add(item.getMessageText());
            }
        }

        List<PatternCandidate> patternList = new ArrayList<>(solution.keySet());
        log.debug("Stage5 patterns={} detailedDemands={}", patternList.size(), detailedDemands.size());

        try {
            MPSolver solver = MPSolver.createSolver("SCIP");
            if (solver == null) {
                solver = MPSolver.createSolver("CBC");
            }
            if (solver == null) {
                log.error("Stage5 could not create a MIP solver");
                return null;
            }

            Map<String, MPVariable> aVars = new LinkedHashMap<>();
            Map<String, MPVariable> yVars = new LinkedHashMap<>();

            for (int patternIndex = 0; patternIndex < patternList.size(); patternIndex++) {
                PatternCandidate pattern = patternList.get(patternIndex);
                int usage = solution.get(pattern);

                for (int width : pattern.getPattern().keySet()) {
                    int kw = pattern.getPattern().get(width); // slots per car for this width
                    List<String> messages = messagesByWidth.getOrDefault(width, Collections.emptyList());
                    for (String message : messages) {
                        String varKey = patternIndex + "_" + width + "_" + message;
                        // Slot-level variable: a[p,w,m] counts SLOTS (not cars) assigned to message m.
                        // Upper bound = usage * kw (total slots for this width in this pattern).
                        aVars.put(varKey, solver.makeIntVar(0, usage * kw, "a_" + varKey));
                        yVars.put(varKey, solver.makeBoolVar("y_" + varKey));
                    }
                }
            }

            for (int patternIndex = 0; patternIndex < patternList.size(); patternIndex++) {
                PatternCandidate pattern = patternList.get(patternIndex);
                int usage = solution.get(pattern);

                for (int width : pattern.getPattern().keySet()) {
                    int kw = pattern.getPattern().get(width);
                    List<String> messages = messagesByWidth.getOrDefault(width, Collections.emptyList());
                    // Total slots for this width = usage * kw (hard equality)
                    MPConstraint usageConstraint = solver.makeConstraint(usage * kw, usage * kw,
                            "usage_" + patternIndex + "_" + width);
                    for (String message : messages) {
                        String varKey = patternIndex + "_" + width + "_" + message;
                        MPVariable aVar = aVars.get(varKey);
                        if (aVar != null) {
                            usageConstraint.setCoefficient(aVar, 1);
                        }
                    }
                }
            }

            for (Map.Entry<String, Integer> demandEntry : detailedDemands.entrySet()) {
                String key = demandEntry.getKey();
                int demand = demandEntry.getValue();
                String[] parts = key.split("_", 2);
                int width = Integer.parseInt(parts[0]);
                String message = parts[1];

                MPConstraint demandConstraint = solver.makeConstraint(demand, MPSolver.infinity(), "demand_" + key);
                for (int patternIndex = 0; patternIndex < patternList.size(); patternIndex++) {
                    PatternCandidate pattern = patternList.get(patternIndex);
                    int countInPattern = pattern.getPattern().getOrDefault(width, 0);
                    if (countInPattern > 0) {
                        String varKey = patternIndex + "_" + width + "_" + message;
                        MPVariable aVar = aVars.get(varKey);
                        if (aVar != null) {
                            demandConstraint.setCoefficient(aVar, 1); // a is slot count; 1 slot = 1 roll
                        }
                    }
                }
            }

            for (int patternIndex = 0; patternIndex < patternList.size(); patternIndex++) {
                PatternCandidate pattern = patternList.get(patternIndex);
                int usage = solution.get(pattern);

                for (int width : pattern.getPattern().keySet()) {
                    int kw = pattern.getPattern().get(width);
                    List<String> messages = messagesByWidth.getOrDefault(width, Collections.emptyList());
                    for (String message : messages) {
                        String varKey = patternIndex + "_" + width + "_" + message;
                        MPVariable aVar = aVars.get(varKey);
                        MPVariable yVar = yVars.get(varKey);
                        if (aVar != null && yVar != null) {
                            // a[p,w,m] <= (usage * kw) * y[p,w,m]
                            MPConstraint linkConstraint = solver.makeConstraint(
                                    -MPSolver.infinity(), 0, "link_" + varKey);
                            linkConstraint.setCoefficient(aVar, 1);
                            linkConstraint.setCoefficient(yVar, -(long) usage * kw);
                        }
                    }
                }
            }

            MPObjective objective = solver.objective();
            for (MPVariable yVar : yVars.values()) {
                objective.setCoefficient(yVar, 1);
            }
            objective.setMinimization();

            long timeLimit = Math.min(DEFAULT_STAGE5_TIME_LIMIT_MS, params.getTimeoutMs());
            solver.setTimeLimit(timeLimit);

            log.debug("Stage5 variables={} constraints={}", solver.numVariables(), solver.numConstraints());
            long startTime = System.currentTimeMillis();
            MPSolver.ResultStatus status = solver.solve();
            long elapsed = System.currentTimeMillis() - startTime;

            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
                log.warn("Stage5 failed: {} ({}ms)", status, elapsed);
                return null;
            }

            log.info("Stage5 completed: {} ({}ms), proxyBlocks={}", status, elapsed, (int) objective.value());

            Map<PatternCandidate, List<AssignmentBlock>> result = new LinkedHashMap<>();

            for (int patternIndex = 0; patternIndex < patternList.size(); patternIndex++) {
                PatternCandidate pattern = patternList.get(patternIndex);
                List<AssignmentBlock> blocks = new ArrayList<>();

                Map<Integer, List<Map.Entry<String, Integer>>> widthSlotAssignments = new LinkedHashMap<>();
                for (int width : pattern.getPattern().keySet()) {
                    List<String> messages = messagesByWidth.getOrDefault(width, Collections.emptyList());
                    // Collect slot counts from MIP solution
                    List<Map.Entry<String, Integer>> slotCounts = new ArrayList<>();
                    for (String message : messages) {
                        String varKey = patternIndex + "_" + width + "_" + message;
                        MPVariable aVar = aVars.get(varKey);
                        if (aVar != null) {
                            int slots = (int) Math.round(aVar.solutionValue());
                            if (slots > 0) {
                                slotCounts.add(Map.entry(message, slots));
                            }
                        }
                    }
                    widthSlotAssignments.put(width, slotCounts);
                }

                blocks.addAll(buildBlocksFromWidthSlotAssignments(
                        widthSlotAssignments, pattern.getPattern(), solution.get(pattern)));

                result.put(pattern, blocks);
            }

            int totalBlocks = result.values().stream().mapToInt(List::size).sum();
            log.info("Stage5 result: assignmentBlocks={} (proxy upper bound)", totalBlocks);

            return result;
        } catch (Exception e) {
            log.error("Stage5 failed", e);
            return null;
        }
    }

    private String demandKey(int width, String messageText) {
        return width + "_" + messageText;
    }

    static List<AssignmentBlock> buildBlocksFromWidthAssignments(
            Map<Integer, List<Map.Entry<String, Integer>>> widthAssignments,
            int usage) {
        if (widthAssignments == null || widthAssignments.isEmpty() || usage <= 0) {
            return Collections.emptyList();
        }

        Map<Integer, List<AssignmentSegment>> segmentsByWidth = new LinkedHashMap<>();
        Set<Integer> boundaries = new LinkedHashSet<>();
        boundaries.add(0);
        boundaries.add(usage);

        for (Map.Entry<Integer, List<Map.Entry<String, Integer>>> entry : widthAssignments.entrySet()) {
            int width = entry.getKey();
            List<Map.Entry<String, Integer>> assignments = new ArrayList<>(entry.getValue());
            assignments.removeIf(assignment -> assignment.getValue() == null || assignment.getValue() <= 0);
            assignments.sort(Comparator
                    .comparingInt((Map.Entry<String, Integer> assignment) -> assignment.getValue()).reversed()
                    .thenComparing(Map.Entry::getKey));

            List<AssignmentSegment> segments = new ArrayList<>();
            int cursor = 0;
            for (Map.Entry<String, Integer> assignment : assignments) {
                int count = assignment.getValue();
                if (cursor >= usage) {
                    break;
                }

                int end = Math.min(usage, cursor + count);
                if (end <= cursor) {
                    continue;
                }

                segments.add(new AssignmentSegment(cursor, end, assignment.getKey()));
                cursor = end;
                boundaries.add(cursor);
            }

            if (segments.isEmpty() && usage > 0) {
                throw new IllegalArgumentException("Width " + width + " has no positive assignment segments");
            }
            if (cursor < usage) {
                AssignmentSegment last = segments.get(segments.size() - 1);
                segments.set(segments.size() - 1, new AssignmentSegment(last.start(), usage, last.message()));
                boundaries.add(usage);
            }

            segmentsByWidth.put(width, segments);
        }

        List<Integer> sortedBoundaries = new ArrayList<>(boundaries);
        Collections.sort(sortedBoundaries);

        List<AssignmentBlock> rawBlocks = new ArrayList<>();
        for (int index = 0; index < sortedBoundaries.size() - 1; index++) {
            int start = sortedBoundaries.get(index);
            int end = sortedBoundaries.get(index + 1);
            if (end <= start) {
                continue;
            }

            Map<Integer, String> config = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<AssignmentSegment>> entry : segmentsByWidth.entrySet()) {
                config.put(entry.getKey(), findMessageAt(entry.getValue(), start));
            }
            rawBlocks.add(new AssignmentBlock(config, end - start));
        }

        return mergeAdjacentBlocks(rawBlocks);
    }

    static List<AssignmentBlock> buildBlocksFromWidthSlotAssignments(
            Map<Integer, List<Map.Entry<String, Integer>>> widthSlotAssignments,
            Map<Integer, Integer> pattern,
            int usage) {
        if (widthSlotAssignments == null || widthSlotAssignments.isEmpty()
                || pattern == null || pattern.isEmpty() || usage <= 0) {
            return Collections.emptyList();
        }

        Map<Integer, List<List<String>>> rollMessagesByWidth = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> cut : pattern.entrySet()) {
            int width = cut.getKey();
            int slotsPerRoll = cut.getValue();
            int totalSlots = usage * slotsPerRoll;
            List<String> flatMessages = materializeSlotMessages(
                    widthSlotAssignments.getOrDefault(width, Collections.emptyList()),
                    totalSlots);

            List<List<String>> rollMessages = new ArrayList<>();
            for (int roll = 0; roll < usage; roll++) {
                int start = roll * slotsPerRoll;
                int end = Math.min(start + slotsPerRoll, flatMessages.size());
                List<String> messages = new ArrayList<>(flatMessages.subList(start, end));
                while (messages.size() < slotsPerRoll) {
                    messages.add(messages.isEmpty() ? "" : messages.get(messages.size() - 1));
                }
                messages.sort(String::compareTo);
                rollMessages.add(List.copyOf(messages));
            }
            rollMessagesByWidth.put(width, rollMessages);
        }

        List<AssignmentBlock> rawBlocks = new ArrayList<>();
        for (int roll = 0; roll < usage; roll++) {
            Map<Integer, List<String>> stationConfig = new LinkedHashMap<>();
            for (int width : pattern.keySet()) {
                stationConfig.put(width, rollMessagesByWidth.get(width).get(roll));
            }
            rawBlocks.add(AssignmentBlock.fromStationConfig(stationConfig, 1));
        }

        return mergeAdjacentBlocks(rawBlocks);
    }

    private static List<String> materializeSlotMessages(
            List<Map.Entry<String, Integer>> slotCounts,
            int totalSlots) {
        List<Map.Entry<String, Integer>> assignments = new ArrayList<>(slotCounts);
        assignments.removeIf(assignment -> assignment.getValue() == null || assignment.getValue() <= 0);
        assignments.sort(Comparator
                .comparingInt((Map.Entry<String, Integer> assignment) -> assignment.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));

        List<String> messages = new ArrayList<>(Math.max(0, totalSlots));
        for (Map.Entry<String, Integer> assignment : assignments) {
            for (int slot = 0; slot < assignment.getValue() && messages.size() < totalSlots; slot++) {
                messages.add(assignment.getKey());
            }
            if (messages.size() >= totalSlots) {
                break;
            }
        }

        String fallback = messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        while (messages.size() < totalSlots) {
            messages.add(fallback);
        }
        return messages;
    }

    private static String findMessageAt(List<AssignmentSegment> segments, int rollIndex) {
        for (AssignmentSegment segment : segments) {
            if (rollIndex >= segment.start() && rollIndex < segment.end()) {
                return segment.message();
            }
        }
        throw new IllegalArgumentException("No assignment segment covers roll index " + rollIndex);
    }

    private static List<AssignmentBlock> mergeAdjacentBlocks(List<AssignmentBlock> rawBlocks) {
        if (rawBlocks.isEmpty()) {
            return rawBlocks;
        }

        List<AssignmentBlock> merged = new ArrayList<>();
        AssignmentBlock current = rawBlocks.get(0);
        for (int index = 1; index < rawBlocks.size(); index++) {
            AssignmentBlock next = rawBlocks.get(index);
            if (current.getStationConfig().equals(next.getStationConfig())) {
                current = new AssignmentBlock(
                        current.getConfig(), current.getStationConfig(), current.getCount() + next.getCount());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private record AssignmentSegment(int start, int end, String message) {
    }
}
