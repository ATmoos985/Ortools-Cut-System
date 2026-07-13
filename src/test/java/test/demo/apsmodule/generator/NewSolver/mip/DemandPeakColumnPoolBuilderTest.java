package test.demo.apsmodule.generator.NewSolver.mip;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakColumnPoolBuilder.Config;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemandPeakColumnPoolBuilderTest {

    @Test
    void findsDemandSupportedCenterAndKeepsBothSidesInOnePool() {
        Column left = column(4330, "L");
        Column center = column(4350, "C");
        Column right = column(4370, "R");
        Column far = column(4400, "F");

        DemandPeakColumnPoolBuilder.Result result = new DemandPeakColumnPoolBuilder().select(
                List.of(left, center, right, far),
                Map.of("4330|L", 2, "4350|C", 12, "4370|R", 4, "4400|F", 3),
                List.of(),
                new Config(2, 4, 8, 10, 1, 0, 10));

        assertEquals(4350, result.peaks().get(0).patternWidth());
        assertEquals(List.of("4330=L", "4350=C", "4370=R", "4400=F"),
                result.columns().stream().map(Column::signature).toList());
        assertEquals(3, result.stats().distanceBands());
    }

    @Test
    void protectsKnownGoodBoundaryColumnsWhenCenterBandIsCapped() {
        Column protectedBoundary = column(4400, "B");
        Column centerBest = column(4350, "C1");
        Column centerOther = column(4350, "C2");

        DemandPeakColumnPoolBuilder.Result result = new DemandPeakColumnPoolBuilder().select(
                List.of(centerBest, centerOther),
                Map.of("4400|B", 1, "4350|C1", 10, "4350|C2", 8),
                List.of(protectedBoundary),
                new Config(2, 4, 8, 1, 1, 0, 10));

        assertTrue(result.columns().stream()
                .map(Column::signature)
                .anyMatch("4400=B"::equals));
        assertEquals(2, result.columns().size());
        assertEquals(1, result.stats().protectedColumns());
    }

    private static Column column(int width, String message) {
        return Column.of(Map.of(width, 1), Map.of(width, List.of(message)));
    }
}
