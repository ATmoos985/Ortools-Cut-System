package test.demo.apsmodule.generator.NewSolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SolverExperimentSnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsStableColumnsAndMetrics() throws Exception {
        List<ColumnUse> uses = List.of(
                new ColumnUse(column("B"), 6),
                new ColumnUse(column("A"), 3));
        Path path = tempDir.resolve("snapshot.csv");

        SolverExperimentSnapshot.write(path, "fixture", uses, 1200);
        SolverExperimentSnapshot.Snapshot snapshot = SolverExperimentSnapshot.read(path);

        assertEquals("fixture", snapshot.dataset());
        assertEquals(new SolverExperimentSnapshot.Metrics(2, 1, 1, 9, 1800),
                snapshot.metrics());
        assertEquals(List.of("1000=A", "1000=B"),
                snapshot.uses().stream().map(use -> use.column().signature()).toList());
    }

    @Test
    void mergesDurableColumnArchiveWithoutDuplicates() throws Exception {
        Path path = tempDir.resolve("columns.csv");

        SolverExperimentSnapshot.mergeColumnArchive(path, "fixture",
                List.of(column("B"), column("A")));
        SolverExperimentSnapshot.ColumnArchive merged =
                SolverExperimentSnapshot.mergeColumnArchive(path, "fixture",
                        List.of(column("A"), column("C")));
        SolverExperimentSnapshot.ColumnArchive reloaded =
                SolverExperimentSnapshot.readColumnArchive(path);

        assertEquals(List.of("1000=A", "1000=B", "1000=C"),
                merged.columns().stream().map(Column::signature).toList());
        assertEquals(merged, reloaded);
    }

    private static Column column(String message) {
        return Column.of(Map.of(1000, 1), Map.of(1000, List.of(message)));
    }
}
