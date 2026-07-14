package test.demo.apsmodule.generator.NewSolver.mip;

import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakColumnPoolBuilder.Config;
import test.demo.apsmodule.generator.NewSolver.mip.DemandPeakColumnPoolBuilder.Result;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.Column;
import test.demo.apsmodule.generator.NewSolver.mip.UnifiedSetPartitionSolver.ColumnUse;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Experimental constructive initial-solution path.
 *
 * <p>It builds one demand-peak guided executable-column pool and gives that
 * pool to exactly one set-partition MIP. No per-width solve and no LNS/SPR
 * repair are performed here. Callers can compare its result with the existing
 * pipeline before deciding whether it is safe to expose in production.</p>
 */
public final class DemandPeakInitialSolutionSolver {

    public record InitialSolution(Result pool,
                                  UnifiedSetPartitionSolver.Result solution) {

        public boolean feasible() {
            return solution != null && ("OPTIMAL".equals(solution.status())
                    || "FEASIBLE".equals(solution.status()));
        }
    }

    private final DemandPeakColumnPoolBuilder poolBuilder;
    private final UnifiedSetPartitionSolver masterSolver;

    public DemandPeakInitialSolutionSolver() {
        this(new DemandPeakColumnPoolBuilder(), new UnifiedSetPartitionSolver());
    }

    DemandPeakInitialSolutionSolver(DemandPeakColumnPoolBuilder poolBuilder,
                                   UnifiedSetPartitionSolver masterSolver) {
        this.poolBuilder = poolBuilder;
        this.masterSolver = masterSolver;
    }

    public InitialSolution solve(Collection<Column> generatedColumns,
                                 Map<String, Integer> demand,
                                 Collection<Column> protectedColumns,
                                 Config poolConfig,
                                 int exactCars,
                                 int wasteCap,
                                 int totalWidth,
                                 long timeLimitMs) {
        return solve(generatedColumns, demand, protectedColumns, poolConfig,
                exactCars, wasteCap, totalWidth, timeLimitMs, List.of());
    }

    public InitialSolution solve(Collection<Column> generatedColumns,
                                 Map<String, Integer> demand,
                                 Collection<Column> protectedColumns,
                                 Config poolConfig,
                                 int exactCars,
                                 int wasteCap,
                                 int totalWidth,
                                 long timeLimitMs,
                                 List<ColumnUse> feasibleWarmStart) {
        Result pool = poolBuilder.select(generatedColumns, demand, protectedColumns, poolConfig);
        List<ColumnUse> warmStart = feasibleWarmStart == null || feasibleWarmStart.isEmpty()
                ? UnifiedSetPartitionSolver.greedyAlignedWarmStart(
                        pool.columns(), demand, exactCars, wasteCap, totalWidth)
                : List.copyOf(feasibleWarmStart);
        UnifiedSetPartitionSolver.Result solution = masterSolver.solve(
                pool.columns(), demand, exactCars, wasteCap, totalWidth,
                timeLimitMs, 0.02, warmStart);
        return new InitialSolution(pool, solution);
    }
}
