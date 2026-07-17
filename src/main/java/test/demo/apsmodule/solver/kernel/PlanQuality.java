package test.demo.apsmodule.solver.kernel;

/**
 * Backend-neutral business quality vector used for deterministic plan selection.
 */
public record PlanQuality(
        int totalOverProduction,
        int totalRolls,
        int sequenceGroups,
        int oddCarGroups,
        int oneCarGroups,
        int smallCarGroups,
        int patternCount,
        int totalWaste,
        int sourceOrder) {
}
