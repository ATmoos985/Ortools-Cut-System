package test.demo.apsmodule.generator.NewSolver;

import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class SolverExperimentSnapshot {

    private static final String FORMAT = "solver-experiment-snapshot-v1";
    private static final String COLUMN_ARCHIVE_FORMAT = "solver-column-archive-v1";

    private SolverExperimentSnapshot() {
    }

    record Metrics(int groups, int oddGroups, int smallGroups, int cars, int waste) {
    }

    record Snapshot(String dataset, Metrics metrics, List<ColumnUse> uses) {
    }

    record ColumnArchive(String dataset, List<Column> columns) {
    }

    static List<ColumnUse> fromInstructions(List<CuttingInstruction> instructions) {
        List<ColumnUse> uses = new ArrayList<>();
        for (CuttingInstruction instruction : instructions) {
            Map<Integer, ArrayDeque<String>> buckets = new LinkedHashMap<>();
            for (StationAssignment assignment : instruction.getStationAssignments()) {
                buckets.computeIfAbsent(assignment.getWidth(), ignored -> new ArrayDeque<>())
                        .add(assignment.getMessageText() == null ? "" : assignment.getMessageText());
            }
            String previousSignature = null;
            Column previousColumn = null;
            int run = 0;
            for (int roll = 0; roll < instruction.getUsageCount(); roll++) {
                Map<Integer, List<String>> config = new TreeMap<>();
                for (Map.Entry<Integer, Integer> entry
                        : new TreeMap<>(instruction.getSubRolls()).entrySet()) {
                    List<String> messages = new ArrayList<>();
                    ArrayDeque<String> bucket = buckets.get(entry.getKey());
                    for (int station = 0; station < entry.getValue(); station++) {
                        messages.add(bucket == null || bucket.isEmpty() ? "" : bucket.poll());
                    }
                    config.put(entry.getKey(), messages);
                }
                Column column = Column.of(instruction.getSubRolls(), config);
                if (column.signature().equals(previousSignature)) {
                    run++;
                } else {
                    if (run > 0) {
                        uses.add(new ColumnUse(previousColumn, run));
                    }
                    previousSignature = column.signature();
                    previousColumn = column;
                    run = 1;
                }
            }
            if (run > 0) {
                uses.add(new ColumnUse(previousColumn, run));
            }
        }
        return List.copyOf(uses);
    }

    static Metrics metrics(List<ColumnUse> uses, int totalWidth) {
        int odd = 0;
        int small = 0;
        int cars = 0;
        int waste = 0;
        for (ColumnUse use : uses) {
            cars += use.count();
            waste += (totalWidth - use.column().patternWidth()) * use.count();
            if (use.count() % 2 != 0) {
                odd++;
            }
            if (use.count() <= 5) {
                small++;
            }
        }
        return new Metrics(uses.size(), odd, small, cars, waste);
    }

    static Map<String, Integer> demandOf(List<SolverOrderItem> items) {
        Map<String, Integer> demand = new TreeMap<>();
        for (SolverOrderItem item : items) {
            demand.merge(item.getWidth() + "|" + item.getMessageText(),
                    item.getDemand(), Integer::sum);
        }
        return demand;
    }

    static Map<String, Integer> producedBy(List<ColumnUse> uses) {
        Map<String, Integer> produced = new TreeMap<>();
        for (ColumnUse use : uses) {
            for (Map.Entry<String, Integer> entry : use.column().demandUse().entrySet()) {
                produced.merge(entry.getKey(), entry.getValue() * use.count(), Integer::sum);
            }
        }
        return produced;
    }

    static void write(Path path, String dataset, List<ColumnUse> uses, int totalWidth)
            throws IOException {
        Metrics metrics = metrics(uses, totalWidth);
        List<String> lines = new ArrayList<>();
        lines.add("# format=" + FORMAT);
        lines.add("# dataset=" + dataset);
        lines.add("# groups=" + metrics.groups());
        lines.add("# oddGroups=" + metrics.oddGroups());
        lines.add("# smallGroups=" + metrics.smallGroups());
        lines.add("# cars=" + metrics.cars());
        lines.add("# waste=" + metrics.waste());
        uses.stream()
                .sorted(Comparator.comparing(use -> use.column().signature()))
                .forEach(use -> lines.add(use.count() + ";" + use.column().signature()));
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    static Snapshot read(Path path) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        List<ColumnUse> uses = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("# ")) {
                int equals = line.indexOf('=');
                metadata.put(line.substring(2, equals), line.substring(equals + 1));
                continue;
            }
            int separator = line.indexOf(';');
            uses.add(new ColumnUse(
                    columnFromSignature(line.substring(separator + 1)),
                    Integer.parseInt(line.substring(0, separator))));
        }
        if (!FORMAT.equals(metadata.get("format"))) {
            throw new IOException("Unsupported snapshot format: " + metadata.get("format"));
        }
        Metrics metrics = new Metrics(
                integer(metadata, "groups"),
                integer(metadata, "oddGroups"),
                integer(metadata, "smallGroups"),
                integer(metadata, "cars"),
                integer(metadata, "waste"));
        if (!metrics.equals(metrics(uses, inferTotalWidth(uses, metrics.waste())))) {
            throw new IOException("Snapshot metadata does not match its columns: " + path);
        }
        return new Snapshot(metadata.get("dataset"), metrics, List.copyOf(uses));
    }

    static ColumnArchive mergeColumnArchive(Path path, String dataset,
            Iterable<Column> discovered) throws IOException {
        Map<String, Column> merged = new TreeMap<>();
        if (Files.exists(path)) {
            for (Column column : readColumnArchive(path).columns()) {
                merged.putIfAbsent(column.signature(), column);
            }
        }
        for (Column column : discovered) {
            if (column != null) {
                merged.putIfAbsent(column.signature(), column);
            }
        }
        writeColumnArchive(path, dataset, merged.values());
        return new ColumnArchive(dataset, List.copyOf(merged.values()));
    }

    static void writeColumnArchive(Path path, String dataset, Iterable<Column> columns)
            throws IOException {
        Map<String, Column> unique = new TreeMap<>();
        for (Column column : columns) {
            if (column != null) {
                unique.putIfAbsent(column.signature(), column);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("# format=" + COLUMN_ARCHIVE_FORMAT);
        lines.add("# dataset=" + dataset);
        lines.add("# columns=" + unique.size());
        lines.addAll(unique.keySet());
        writeAtomically(path, lines);
    }

    static ColumnArchive readColumnArchive(Path path) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        Map<String, Column> columns = new TreeMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("# ")) {
                int equals = line.indexOf('=');
                metadata.put(line.substring(2, equals), line.substring(equals + 1));
            } else {
                Column column = columnFromSignature(line);
                columns.putIfAbsent(column.signature(), column);
            }
        }
        if (!COLUMN_ARCHIVE_FORMAT.equals(metadata.get("format"))) {
            throw new IOException("Unsupported column archive format: " + metadata.get("format"));
        }
        if (integer(metadata, "columns") != columns.size()) {
            throw new IOException("Column archive metadata does not match its columns: " + path);
        }
        return new ColumnArchive(metadata.get("dataset"), List.copyOf(columns.values()));
    }

    private static void writeAtomically(Path path, List<String> lines) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int inferTotalWidth(List<ColumnUse> uses, int waste) throws IOException {
        int cars = uses.stream().mapToInt(ColumnUse::count).sum();
        int usedWidth = uses.stream()
                .mapToInt(use -> use.column().patternWidth() * use.count())
                .sum();
        if (cars <= 0 || (usedWidth + waste) % cars != 0) {
            throw new IOException("Cannot infer total width from snapshot metadata");
        }
        return (usedWidth + waste) / cars;
    }

    private static int integer(Map<String, String> metadata, String key) throws IOException {
        try {
            return Integer.parseInt(metadata.get(key));
        } catch (RuntimeException ex) {
            throw new IOException("Missing or invalid snapshot metadata: " + key, ex);
        }
    }

    private static Column columnFromSignature(String signature) {
        Map<Integer, List<String>> config = new TreeMap<>();
        Map<Integer, Integer> pattern = new TreeMap<>();
        for (String part : signature.split("\\|")) {
            int equals = part.indexOf('=');
            int width = Integer.parseInt(part.substring(0, equals));
            List<String> messages = new ArrayList<>(
                    List.of(part.substring(equals + 1).split(",")));
            config.put(width, messages);
            pattern.put(width, messages.size());
        }
        return Column.of(pattern, config);
    }
}
