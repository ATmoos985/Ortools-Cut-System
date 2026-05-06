package test.demo.apsmodule.generator.NewSolver.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.generator.NewSolver.model.SolverResult;

import java.io.Closeable;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes one shareable, human-readable report for each NewSolver request.
 */
public class SolveReportWriter implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SolveReportWriter.class);
    private static final String LOG_DIR = "logs";
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PrintWriter writer;
    private final long requestStartMs;
    private final String filePath;

    private int startedGroups;
    private int solvedGroups;
    private int failedGroups;
    private int totalSeqGroups;
    private long totalWasteMm;
    private int totalRolls;

    private SolveReportWriter(PrintWriter writer, String filePath) {
        this.writer = writer;
        this.filePath = filePath;
        this.requestStartMs = System.currentTimeMillis();
    }

    public static SolveReportWriter create() {
        try {
            Files.createDirectories(Paths.get(LOG_DIR));
            String timestamp = LocalDateTime.now().format(FILE_TS);
            Path path = Paths.get(LOG_DIR, "solve-report-" + timestamp + ".txt");
            PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
            SolveReportWriter report = new SolveReportWriter(writer, path.toString());
            report.writeHeader();
            return report;
        } catch (Exception e) {
            log.warn("Cannot create solve report file, reporting is disabled: {}", e.getMessage());
            return new SolveReportWriter(new PrintWriter(OutputStream.nullOutputStream()), "disabled");
        }
    }

    public void beginGroup(String groupKey,
            List<?> items,
            Map<Integer, Integer> demands,
            Set<Integer> allowOverSet,
            int totalGroups) {
        startedGroups++;
        line('=');
        writeln("GROUP %d/%d: %s", startedGroups, totalGroups, groupKey);
        line('=');
        writeln("Order rows: %d", items == null ? 0 : items.size());
        writeln("Width demand summary (%d widths):", demands.size());
        demands.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> writeln("  %4d mm x %d", entry.getKey(), entry.getValue()));
        writeln("Allow-over widths: %s", formatWidthSet(allowOverSet));
        writeln("");
    }

    public void writeGroupResult(List<CandidateRow> candidates,
            String selectedName,
            Map<PatternCandidate, Integer> selectedSolution,
            int selectedSeqGroups,
            int totalWidth,
            long groupElapsedMs) {
        solvedGroups++;

        writeln("Candidate comparison:");
        writeln("  %-22s %10s %10s %10s %10s %10s",
                "name", "waste_mm", "util_pct", "patterns", "rolls", "seq_groups");
        writeln("  %-22s %10s %10s %10s %10s %10s",
                "----------------------", "----------", "--------", "--------", "-----", "----------");
        for (CandidateRow row : candidates) {
            String marker = row.name().equals(selectedName) ? "  <- selected" : "";
            writeln("  %-22s %10d %9.2f%% %10d %10d %10d%s",
                    row.name(),
                    row.waste(),
                    row.utilization(),
                    row.patterns(),
                    row.rolls(),
                    row.seqGroups(),
                    marker);
        }
        writeln("");

        if (selectedSolution != null && !selectedSolution.isEmpty()) {
            writeln("Selected plan: %s", selectedName);
            writeln("Group elapsed: %d ms", groupElapsedMs);
            writeln("Selected sequence groups: %d", selectedSeqGroups);
            writeln("Selected pattern details (%d patterns):", selectedSolution.size());

            selectedSolution.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<PatternCandidate, Integer>>comparingInt(Map.Entry::getValue)
                            .reversed()
                            .thenComparing(entry -> entry.getKey().signature()))
                    .forEach(entry -> {
                        PatternCandidate pattern = entry.getKey();
                        int usage = entry.getValue();
                        writeln("  %4d rolls | rollWidth=%4d mm | waste=%4d mm/roll | %s",
                                usage,
                                pattern.getRollWidth(),
                                pattern.getRealWaste(totalWidth),
                                formatPattern(pattern));
                    });
        }
        writeln("");

        candidates.stream()
                .filter(row -> row.name().equals(selectedName))
                .findFirst()
                .ifPresent(row -> {
                    totalSeqGroups += row.seqGroups();
                    totalWasteMm += row.waste();
                    totalRolls += row.rolls();
                });
    }

    public void writeGroupFailure(String groupKey, String reason, long groupElapsedMs) {
        failedGroups++;
        writeln("Group failed: %s", groupKey);
        writeln("Reason: %s", reason);
        writeln("Group elapsed: %d ms", groupElapsedMs);
        writeln("");
    }

    public void writeSummary(int totalInstructions) {
        line('=');
        writeln("REQUEST SUMMARY");
        line('=');
        long elapsedMs = System.currentTimeMillis() - requestStartMs;
        writeln("Groups started:   %d", startedGroups);
        writeln("Groups solved:    %d", solvedGroups);
        writeln("Groups failed:    %d", failedGroups);
        writeln("Total seq groups: %d", totalSeqGroups);
        writeln("Total waste:      %,d mm", totalWasteMm);
        writeln("Total rolls:      %d", totalRolls);
        writeln("Instructions:     %d", totalInstructions);
        writeln("Elapsed:          %d ms (%.1f s)", elapsedMs, elapsedMs / 1000.0);
        writeln("Report file:      %s", filePath);
        writer.flush();
    }

    public String getFilePath() {
        return filePath;
    }

    @Override
    public void close() {
        writer.flush();
        writer.close();
    }

    private void writeHeader() {
        line('=');
        writeln("SOLARTRON-CUT NEW SOLVER REPORT");
        writeln("Generated at: %s", LocalDateTime.now().format(DISPLAY_TS));
        line('=');
        writeln("");
    }

    private String formatWidthSet(Set<Integer> widths) {
        if (widths == null || widths.isEmpty()) {
            return "-";
        }
        return widths.stream()
                .sorted()
                .map(width -> width + "mm")
                .collect(Collectors.joining(", "));
    }

    private String formatPattern(PatternCandidate pattern) {
        return pattern.getPattern().entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByKey().reversed())
                .map(entry -> entry.getValue() == 1
                        ? entry.getKey() + "mm"
                        : entry.getKey() + "mm x " + entry.getValue())
                .collect(Collectors.joining(" + "));
    }

    private void line(char ch) {
        writeln(String.valueOf(ch).repeat(80));
    }

    private void writeln(String format, Object... args) {
        writer.println(args.length == 0 ? format : String.format(format, args));
    }

    public record CandidateRow(
            String name,
            int waste,
            int patterns,
            int rolls,
            int seqGroups,
            int totalWidth) {

        public CandidateRow(String name, SolverResult result, int seqGroups, int totalWidth) {
            this(name,
                    result.getTotalWaste(),
                    result.getPatternCount(),
                    result.getTotalRolls(),
                    seqGroups,
                    totalWidth);
        }

        public double utilization() {
            if (rolls <= 0 || totalWidth <= 0) {
                return 0.0;
            }
            return 100.0 * (totalWidth * rolls - waste) / (double) (totalWidth * rolls);
        }
    }
}
