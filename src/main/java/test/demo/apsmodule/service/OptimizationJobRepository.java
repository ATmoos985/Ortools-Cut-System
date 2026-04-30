package test.demo.apsmodule.service;

import java.util.Optional;

public interface OptimizationJobRepository {

    Optional<OptimizationJob> findById(String jobId);

    Optional<OptimizationJob> findByIdempotencyKey(String idempotencyKey);

    void save(OptimizationJob job);
}
