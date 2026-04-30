package test.demo.rest.context;

import org.springframework.stereotype.Component;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.OptimizationJob;
import test.demo.apsmodule.service.OptimizationJobRepository;
import test.demo.apsmodule.service.OptimizationJobStatus;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.excel.ExcelImportService;

import java.util.List;
import java.util.UUID;

@Component
public class OptimizationContext {

    public record JobCreationResult(OptimizationJob job, boolean created) {
    }

    private final OptimizationJobRepository jobRepository;
    private String lastJobId;

    public OptimizationContext(OptimizationJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public synchronized String createJob() {
        return createJob(null, null).job().getJobId();
    }

    public synchronized JobCreationResult createJob(String requestId, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            OptimizationJob existingJob = jobRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existingJob != null) {
                lastJobId = existingJob.getJobId();
                return new JobCreationResult(existingJob, false);
            }
        }

        OptimizationJob job = new OptimizationJob();
        job.setJobId(generateJobId());
        job.setPlanId(job.getJobId());
        job.setRequestId(requestId);
        job.setIdempotencyKey(idempotencyKey);
        job.setStatus(OptimizationJobStatus.RUNNING);
        jobRepository.save(job);
        lastJobId = job.getJobId();
        return new JobCreationResult(job, true);
    }

    public synchronized void completeJob(String jobId,
            CuttingOptimizationResult result,
            List<ExcelImportService.OrderItem> orderItems,
            int totalWidth,
            SolverConfig solverConfig) {
        OptimizationJob job = getOrCreateJob(jobId);
        job.setOptimizationResult(result);
        job.setOrderItems(orderItems);
        job.setTotalWidth(totalWidth);
        job.setSolverConfig(solverConfig);
        job.setErrorMessage(null);
        if (!hasText(job.getPlanId())) {
            job.setPlanId(job.getJobId());
        }
        if (job.getRevisionNumber() <= 0) {
            job.setRevisionNumber(1);
        }
        job.setStatus(OptimizationJobStatus.SUCCEEDED);
        jobRepository.save(job);
        lastJobId = jobId;
    }

    public synchronized void failJob(String jobId, String errorMessage) {
        OptimizationJob job = getOrCreateJob(jobId);
        job.setErrorMessage(errorMessage);
        job.setStatus(OptimizationJobStatus.FAILED);
        jobRepository.save(job);
        lastJobId = jobId;
    }

    public synchronized OptimizationJob getJob(String jobId) {
        return jobRepository.findById(jobId).orElse(null);
    }

    public synchronized OptimizationJob getJobByIdempotencyKey(String idempotencyKey) {
        if (!hasText(idempotencyKey)) {
            return null;
        }
        return jobRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
    }

    public synchronized String getLastJobId() {
        return lastJobId;
    }

    public synchronized String getCurrentPlanId() {
        OptimizationJob job = getLastJob();
        if (job == null) {
            return null;
        }
        if (!hasText(job.getPlanId())) {
            job.setPlanId(job.getJobId());
            jobRepository.save(job);
        }
        return job.getPlanId();
    }

    public synchronized String getCurrentRevisionId() {
        OptimizationJob job = getLastJob();
        if (job == null || job.getRevisionNumber() <= 0) {
            return null;
        }
        return formatRevisionId(job.getRevisionNumber());
    }

    public synchronized String advanceRevision() {
        OptimizationJob job = ensureLatestJob();
        if (!hasText(job.getPlanId())) {
            job.setPlanId(job.getJobId());
        }
        int nextRevision = Math.max(job.getRevisionNumber(), 0) + 1;
        job.setRevisionNumber(nextRevision);
        jobRepository.save(job);
        return formatRevisionId(nextRevision);
    }

    public synchronized CuttingOptimizationResult getLastOptimizationResult() {
        OptimizationJob job = getLastJob();
        return job != null ? job.getOptimizationResult() : null;
    }

    public synchronized void setLastOptimizationResult(CuttingOptimizationResult lastOptimizationResult) {
        OptimizationJob job = ensureLatestJob();
        job.setOptimizationResult(lastOptimizationResult);
        jobRepository.save(job);
    }

    public synchronized List<ExcelImportService.OrderItem> getLastOrderItems() {
        OptimizationJob job = getLastJob();
        return job != null ? job.getOrderItems() : null;
    }

    public synchronized void setLastOrderItems(List<ExcelImportService.OrderItem> lastOrderItems) {
        OptimizationJob job = ensureLatestJob();
        job.setOrderItems(lastOrderItems);
        jobRepository.save(job);
    }

    public synchronized int getLastTotalWidth() {
        OptimizationJob job = getLastJob();
        return job != null ? job.getTotalWidth() : 4600;
    }

    public synchronized void setLastTotalWidth(int lastTotalWidth) {
        OptimizationJob job = ensureLatestJob();
        job.setTotalWidth(lastTotalWidth);
        jobRepository.save(job);
    }

    public synchronized boolean hasResult() {
        CuttingOptimizationResult result = getLastOptimizationResult();
        return result != null && result.getCuttingInstructions() != null;
    }

    public synchronized SolverConfig getLastSolverConfig() {
        OptimizationJob job = getLastJob();
        return job != null ? job.getSolverConfig() : null;
    }

    public synchronized void setLastSolverConfig(SolverConfig lastSolverConfig) {
        OptimizationJob job = ensureLatestJob();
        job.setSolverConfig(lastSolverConfig);
        jobRepository.save(job);
    }

    private OptimizationJob ensureLatestJob() {
        if (lastJobId == null) {
            createJob();
        }
        return getOrCreateJob(lastJobId);
    }

    private OptimizationJob getLastJob() {
        return lastJobId != null ? jobRepository.findById(lastJobId).orElse(null) : null;
    }

    private OptimizationJob getOrCreateJob(String jobId) {
        OptimizationJob existingJob = jobRepository.findById(jobId).orElse(null);
        if (existingJob != null) {
            return existingJob;
        }

        OptimizationJob job = new OptimizationJob();
        job.setJobId(jobId);
        job.setPlanId(jobId);
        jobRepository.save(job);
        return job;
    }

    private String generateJobId() {
        return "job-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String formatRevisionId(int revisionNumber) {
        return "rev-" + revisionNumber;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
