package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.ExecutableColumnPool.Normalized;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutableColumnPoolTest {

    @Test
    void normalizesInStableOrderAndReportsEverySafeRejection() {
        Column validB = column(1000, "B");
        Column validA = column(1000, "A");
        Column unsupported = column(1000, "missing");
        Column overWaste = column(500, "A");
        Column tooWide = column(2100, "A");

        Normalized normalized = ExecutableColumnPool.normalize(
                List.of(validB, validA, validA, unsupported, overWaste, tooWide),
                Map.of("1000|A", 2, "1000|B", 2, "500|A", 2, "2100|A", 2),
                1000,
                2000);

        assertEquals(List.of("1000=A", "1000=B"),
                normalized.columns().stream().map(Column::signature).toList());
        assertEquals(6, normalized.stats().inputColumns());
        assertEquals(2, normalized.stats().retainedColumns());
        assertEquals(1, normalized.stats().duplicateColumns());
        assertEquals(1, normalized.stats().unsupportedColumns());
        assertEquals(1, normalized.stats().invalidWidthColumns());
        assertEquals(1, normalized.stats().overWasteCapColumns());
        assertEquals(4, normalized.stats().rejectedColumns());
    }

    @Test
    void rejectsInconsistentPatternAndConfiguration() {
        Column inconsistent = new Column(
                Map.of(1000, 2), Map.of(1000, List.of("A")), 2000);

        Normalized normalized = ExecutableColumnPool.normalize(
                List.of(inconsistent), Map.of("1000|A", 2), 0, 2000);

        assertEquals(0, normalized.stats().retainedColumns());
        assertEquals(1, normalized.stats().invalidWidthColumns());
    }

    private static Column column(int width, String message) {
        return Column.of(Map.of(width, 1), Map.of(width, List.of(message)));
    }
}
