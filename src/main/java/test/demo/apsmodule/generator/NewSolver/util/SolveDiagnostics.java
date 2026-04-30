package test.demo.apsmodule.generator.NewSolver.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes structured per-run diagnostics so two executions can be diffed.
 */
public final class SolveDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(SolveDiagnostics.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ThreadLocal<RunContext> CURRENT = new ThreadLocal<>();

    private SolveDiagnostics() {
    }

    public static RunHandle beginRun(String solverName) {
        if (!isEnabled()) {
            return new RunHandle(null);
        }

        String runId = FILE_TIME.format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = Path.of("logs", "newsolver-diagnostics");
        Path file = directory.resolve(runId + ".csv");
        try {
            Files.createDirectories(directory);
            Files.writeString(file,
                    "time,runId,groupKey,stage,name,status,elapsedMs,objective,bestBound,groups,waste,selected,details%n"
                            .formatted(),
                    StandardCharsets.UTF_8);
            RunContext context = new RunContext(runId, file);
            CURRENT.set(context);
            record("GLOBAL", "run", solverName, "START", -1, null, null, null, null, null,
                    "file=" + file.toAbsolutePath());
            return new RunHandle(context);
        } catch (IOException e) {
            log.warn("NewSolver diagnostics disabled because trace file could not be created", e);
            return new RunHandle(null);
        }
    }

    public static void recordMip(String groupKey,
            String stage,
            String name,
            Object status,
            long elapsedMs,
            double objective,
            double bestBound,
            String details) {
        record(groupKey, stage, name, Objects.toString(status, ""), elapsedMs, objective, bestBound,
                null, null, null, details);
    }

    public static void recordCandidate(String groupKey,
            String stage,
            String name,
            Integer groups,
            Integer waste,
            Boolean selected,
            String details) {
        record(groupKey, stage, name, "", -1, null, null, groups, waste, selected, details);
    }

    public static void record(String groupKey,
            String stage,
            String name,
            String status,
            long elapsedMs,
            Double objective,
            Double bestBound,
            Integer groups,
            Integer waste,
            Boolean selected,
            String details) {
        RunContext context = CURRENT.get();
        if (context == null) {
            return;
        }

        String row = String.join(",",
                csv(ROW_TIME.format(LocalDateTime.now())),
                csv(context.runId()),
                csv(groupKey),
                csv(stage),
                csv(name),
                csv(status),
                csv(elapsedMs >= 0 ? Long.toString(elapsedMs) : ""),
                csv(objective == null ? "" : String.format("%.6f", objective)),
                csv(bestBound == null ? "" : String.format("%.6f", bestBound)),
                csv(groups == null ? "" : groups.toString()),
                csv(waste == null ? "" : waste.toString()),
                csv(selected == null ? "" : selected.toString()),
                csv(details)) + System.lineSeparator();

        synchronized (SolveDiagnostics.class) {
            try {
                Files.writeString(context.file(), row, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.debug("Failed to append NewSolver diagnostic row", e);
            }
        }
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("newsolver.diagnostics.enabled", "true"));
    }

    private static String csv(Object value) {
        String text = Objects.toString(value, "");
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    public record RunHandle(RunContext context) implements AutoCloseable {

        @Override
        public void close() {
            if (context != null) {
                record("GLOBAL", "run", "NewSolver", "END", -1, null, null, null, null, null, "");
                CURRENT.remove();
            }
        }
    }

    public record RunContext(String runId, Path file) {
    }
}
