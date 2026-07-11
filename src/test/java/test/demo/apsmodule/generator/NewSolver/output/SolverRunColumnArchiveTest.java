package test.demo.apsmodule.generator.NewSolver.output;

import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.StationAssignment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SolverRunColumnArchiveTest {

    @Test
    void capturesAndDeduplicatesExecutableColumnsForOneRun() {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setSubRolls(new LinkedHashMap<>(Map.of(1000, 1)));
        instruction.setUsageCount(2);
        instruction.setStationAssignments(List.of(
                new StationAssignment(1000, "A"),
                new StationAssignment(1000, "A")));
        Column extra = Column.of(Map.of(1000, 1), Map.of(1000, List.of("B")));

        SolverRunColumnArchive.Captured<String> captured = SolverRunColumnArchive.capture(() -> {
            SolverRunColumnArchive.recordInstructions(List.of(instruction));
            SolverRunColumnArchive.recordInstructions(List.of(instruction));
            SolverRunColumnArchive.recordColumns(List.of(extra));
            return "done";
        });

        assertEquals("done", captured.value());
        assertEquals(List.of("1000=A", "1000=B"),
                captured.columns().stream().map(Column::signature).toList());
    }
}
