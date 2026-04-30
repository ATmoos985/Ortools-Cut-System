package test.demo.apsmodule.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import test.demo.apsmodule.api.dto.ApsApiModels;
import test.demo.apsmodule.api.dto.ApsOptimizationModels;
import test.demo.apsmodule.service.OptimizationJob;
import test.demo.apsmodule.service.OptimizationJobStatus;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/aps/v1")
@CrossOrigin(origins = "*")
public class ApsOptimizationController {

    private final ApsOptimizationJobService jobService;
    private final ApsJobResultAssembler resultAssembler;

    public ApsOptimizationController(
            ApsOptimizationJobService jobService,
            ApsJobResultAssembler resultAssembler) {
        this.jobService = jobService;
        this.resultAssembler = resultAssembler;
    }

    @PostMapping("/optimize")
    public ResponseEntity<?> optimize(@RequestBody ApsOptimizationModels.CreateJobRequest request) {
        List<ApsApiModels.FieldError> validationErrors = validate(request);
        if (!validationErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    ApsApiModels.error("validation_error", "Request validation failed", validationErrors));
        }

        return ResponseEntity.ok(ApsApiModels.success(
                resultAssembler.toOptimizationResultData(jobService.optimizeDirect(request)),
                request.requestId()));
    }

    @PostMapping("/jobs")
    public ResponseEntity<?> createJob(@RequestBody ApsOptimizationModels.CreateJobRequest request) {
        List<ApsApiModels.FieldError> validationErrors = validate(request);
        if (!validationErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    ApsApiModels.error("validation_error", "Request validation failed", validationErrors));
        }

        OptimizationJob job = jobService.submitJob(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/aps/v1/jobs/" + job.getJobId()))
                .body(ApsApiModels.success(resultAssembler.toJobStatusData(job), request.requestId()));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
        OptimizationJob job = jobService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApsApiModels.error("job_not_found", "Job not found"));
        }

        return ResponseEntity.ok(ApsApiModels.success(resultAssembler.toJobStatusData(job), job.getRequestId()));
    }

    @GetMapping("/jobs/{jobId}/result")
    public ResponseEntity<?> getJobResult(@PathVariable String jobId) {
        OptimizationJob job = jobService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApsApiModels.error("job_not_found", "Job not found"));
        }
        if (job.getStatus() == OptimizationJobStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApsApiModels.error("job_failed", "Job failed"));
        }
        if (job.getStatus() != OptimizationJobStatus.SUCCEEDED || job.getOptimizationResult() == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApsApiModels.error("job_not_ready", "Job result is not ready"));
        }

        return ResponseEntity.ok(ApsApiModels.success(resultAssembler.toJobResultData(job), job.getRequestId()));
    }

    private List<ApsApiModels.FieldError> validate(ApsOptimizationModels.CreateJobRequest request) {
        List<ApsApiModels.FieldError> errors = new ArrayList<>();
        if (request == null) {
            errors.add(new ApsApiModels.FieldError("request", "must not be null"));
            return errors;
        }
        if (request.orders() == null || request.orders().isEmpty()) {
            errors.add(new ApsApiModels.FieldError("orders", "must not be empty"));
            return errors;
        }

        for (int i = 0; i < request.orders().size(); i++) {
            ApsOptimizationModels.Order order = request.orders().get(i);
            if (order.width() == null || order.width() <= 0) {
                errors.add(new ApsApiModels.FieldError("orders[" + i + "].width", "must be greater than 0"));
            }
            if (order.quantity() == null || order.quantity() <= 0) {
                errors.add(new ApsApiModels.FieldError("orders[" + i + "].quantity", "must be greater than 0"));
            }
            if (order.length() == null || order.length() <= 0) {
                errors.add(new ApsApiModels.FieldError("orders[" + i + "].length", "must be greater than 0"));
            }
            if (order.surfaceTreatment() == null || order.surfaceTreatment().isBlank()) {
                errors.add(new ApsApiModels.FieldError("orders[" + i + "].surfaceTreatment", "must not be blank"));
            }
        }

        return errors;
    }
}
