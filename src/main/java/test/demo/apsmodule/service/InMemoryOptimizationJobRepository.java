package test.demo.apsmodule.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryOptimizationJobRepository implements OptimizationJobRepository {

    private final ConcurrentMap<String, OptimizationJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> jobsByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public Optional<OptimizationJob> findById(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public Optional<OptimizationJob> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        String jobId = jobsByIdempotencyKey.get(idempotencyKey);
        return jobId != null ? Optional.ofNullable(jobs.get(jobId)) : Optional.empty();
    }

    @Override
    public void save(OptimizationJob job) {
        jobs.put(job.getJobId(), job);
        if (job.getIdempotencyKey() != null && !job.getIdempotencyKey().isBlank()) {
            jobsByIdempotencyKey.put(job.getIdempotencyKey(), job.getJobId());
        }
    }
}
