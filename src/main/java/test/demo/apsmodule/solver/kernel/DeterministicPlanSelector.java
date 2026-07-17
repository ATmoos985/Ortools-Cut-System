package test.demo.apsmodule.solver.kernel;

import java.util.List;
import java.util.Optional;

/**
 * Selects candidates with the production lexicographic business ordering.
 */
public final class DeterministicPlanSelector {

    public <T> Optional<SolverCandidate<T>> selectBest(List<SolverCandidate<T>> candidates) {
        SolverCandidate<T> best = null;
        for (SolverCandidate<T> candidate : candidates) {
            if (best == null || isBetter(candidate.quality(), best.quality())) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    public boolean isBetter(PlanQuality candidate, PlanQuality currentBest) {
        int comparison = compare(candidate, currentBest);
        return comparison < 0;
    }

    public int compare(PlanQuality left, PlanQuality right) {
        int comparison = Integer.compare(left.totalOverProduction(), right.totalOverProduction());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.totalRolls(), right.totalRolls());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.sequenceGroups(), right.sequenceGroups());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.oddCarGroups(), right.oddCarGroups());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.oneCarGroups(), right.oneCarGroups());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.smallCarGroups(), right.smallCarGroups());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.patternCount(), right.patternCount());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.totalWaste(), right.totalWaste());
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(left.sourceOrder(), right.sourceOrder());
    }
}
