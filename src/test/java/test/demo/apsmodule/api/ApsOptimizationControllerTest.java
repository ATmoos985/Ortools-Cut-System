package test.demo.apsmodule.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import test.demo.apsmodule.api.dto.ApsOptimizationModels;
import test.demo.apsmodule.service.CuttingOptimizationResult;
import test.demo.apsmodule.service.OptimizationJob;
import test.demo.apsmodule.service.OptimizationJobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ApsOptimizationControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ApsOptimizationJobService jobService;

    @Mock
    private ApsJobResultAssembler resultAssembler;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ApsOptimizationController(jobService, resultAssembler)).build();
    }

    @Test
    void createJobReturnsValidationError() throws Exception {
        ApsOptimizationModels.CreateJobRequest request = new ApsOptimizationModels.CreateJobRequest(
                "REQ-1",
                null,
                List.of(new ApsOptimizationModels.Order("SO-1", 0, 0, 0, "", null, null, null)),
                null);

        mockMvc.perform(post("/api/aps/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void optimizeReturnsDirectResult() throws Exception {
        ApsOptimizationModels.CreateJobRequest request = validRequest();
        CuttingOptimizationResult result = new CuttingOptimizationResult();
        ApsOptimizationModels.OptimizationResultData resultData = new ApsOptimizationModels.OptimizationResultData(
                new ApsOptimizationModels.ResultSummary(2, 50, 98.2, 1200, 4600),
                List.of(new ApsOptimizationModels.Plan(
                        "1000m+T1", 4600, 1000, "T1", 2, Map.of(1200, 2), List.of())));

        when(jobService.optimizeDirect(any())).thenReturn(result);
        when(resultAssembler.toOptimizationResultData(eq(result))).thenReturn(resultData);

        mockMvc.perform(post("/api/aps/v1/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalRollsUsed").value(2))
                .andExpect(jsonPath("$.data.plans[0].groupKey").value("1000m+T1"))
                .andExpect(jsonPath("$.meta.requestId").value("REQ-1"));
    }

    @Test
    void createJobReturnsAcceptedWithLocation() throws Exception {
        ApsOptimizationModels.CreateJobRequest request = validRequest();
        OptimizationJob job = runningJob("job-123");
        ApsOptimizationModels.JobStatusData statusData = new ApsOptimizationModels.JobStatusData(
                "job-123", "RUNNING", Instant.now().toString(), Instant.now().toString(), "REQ-1", "IDEMP-1", null);

        when(jobService.submitJob(any())).thenReturn(job);
        when(resultAssembler.toJobStatusData(eq(job))).thenReturn(statusData);

        mockMvc.perform(post("/api/aps/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/aps/v1/jobs/job-123"))
                .andExpect(jsonPath("$.data.jobId").value("job-123"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.data.idempotencyKey").value("IDEMP-1"))
                .andExpect(jsonPath("$.meta.requestId").value("REQ-1"));
    }

    @Test
    void getJobStatusReturnsNotFound() throws Exception {
        when(jobService.getJob("missing")).thenReturn(null);

        mockMvc.perform(get("/api/aps/v1/jobs/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("job_not_found"));
    }

    @Test
    void getJobResultReturnsConflictWhenJobNotReady() throws Exception {
        when(jobService.getJob("job-123")).thenReturn(runningJob("job-123"));

        mockMvc.perform(get("/api/aps/v1/jobs/job-123/result"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("job_not_ready"));
    }

    @Test
    void getJobResultReturnsPayloadWhenReady() throws Exception {
        OptimizationJob job = succeededJob("job-123");
        ApsOptimizationModels.JobResultData resultData = new ApsOptimizationModels.JobResultData(
                "job-123",
                "SUCCEEDED",
                "REQ-1",
                "IDEMP-1",
                new ApsOptimizationModels.ResultSummary(2, 50, 98.2, 1200, 4600),
                List.of(new ApsOptimizationModels.Plan(
                        "1000m+T1", 4600, 1000, "T1", 2, Map.of(1200, 2), List.of())));

        when(jobService.getJob("job-123")).thenReturn(job);
        when(resultAssembler.toJobResultData(eq(job))).thenReturn(resultData);

        mockMvc.perform(get("/api/aps/v1/jobs/job-123/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value("job-123"))
                .andExpect(jsonPath("$.data.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.data.idempotencyKey").value("IDEMP-1"))
                .andExpect(jsonPath("$.data.summary.totalRollsUsed").value(2))
                .andExpect(jsonPath("$.data.plans[0].groupKey").value("1000m+T1"))
                .andExpect(jsonPath("$.meta.requestId").value("REQ-1"));
    }

    private static ApsOptimizationModels.CreateJobRequest validRequest() {
        return new ApsOptimizationModels.CreateJobRequest(
                "REQ-1",
                "IDEMP-1",
                List.of(new ApsOptimizationModels.Order("SO-1", 1200, 8, 1000, "T1", "Alice", "Desc", 20)),
                new ApsOptimizationModels.OptimizationConfig(
                        false, 4600, 4600, null, null, null, 30, 300, 120000L,
                        true, 3, 800, 4, 30000L, 1.0, 0.0, true, 1e6,
                        true, false, false));
    }

    private static OptimizationJob runningJob(String jobId) {
        OptimizationJob job = new OptimizationJob();
        job.setJobId(jobId);
        job.setStatus(OptimizationJobStatus.RUNNING);
        job.setRequestId("REQ-1");
        job.setIdempotencyKey("IDEMP-1");
        return job;
    }

    private static OptimizationJob succeededJob(String jobId) {
        OptimizationJob job = runningJob(jobId);
        job.setStatus(OptimizationJobStatus.SUCCEEDED);
        job.setOptimizationResult(new CuttingOptimizationResult());
        return job;
    }
}
