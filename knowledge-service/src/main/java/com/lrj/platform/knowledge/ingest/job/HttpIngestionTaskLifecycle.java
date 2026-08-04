package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.protocol.asynctask.AsyncTaskCreateRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskLeaseRequest;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatusUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * async-task HTTP adapter。taskId 与 Knowledge jobId 相同；重复创建的 409 视为幂等成功。
 */
public class HttpIngestionTaskLifecycle implements IngestionTaskLifecycle {

    static final String KIND = "knowledge.ingestion";
    static final String WORKER_ID = "knowledge-service";

    private final RestTemplate http;
    private final String workerId;

    public HttpIngestionTaskLifecycle(RestTemplate http) {
        this(http, WORKER_ID + "." + UUID.randomUUID());
    }

    HttpIngestionTaskLifecycle(RestTemplate http, String workerId) {
        this.http = Objects.requireNonNull(http);
        this.workerId = Objects.requireNonNull(workerId);
    }

    @Override
    public void ensureTask(IngestionJob job) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("knowledgeJobId", job.jobId());
        input.put("documentId", job.documentId());
        input.put("documentVersion", job.documentVersion());
        input.put("sourceHash", job.source().contentHash());
        try {
            http.postForEntity(
                    "/async/tasks",
                    new AsyncTaskCreateRequest(job.jobId(), KIND, input, null),
                    Object.class);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() != HttpStatus.CONFLICT) {
                throw ex;
            }
            AsyncTask existing = http.getForObject(
                    "/async/tasks/{taskId}", AsyncTask.class, job.jobId());
            if (existing == null || !job.jobId().equals(existing.taskId())
                    || !KIND.equals(existing.kind())) {
                throw new IngestionJobConflictException(
                        "async task id is already owned by another task");
            }
        }
    }

    @Override
    public void synchronize(IngestionJob job) {
        AsyncTask leased = http.postForEntity(
                "/async/tasks/{taskId}/lease",
                new AsyncTaskLeaseRequest(workerId, 60L),
                AsyncTask.class,
                job.jobId()).getBody();
        if (leased == null || leased.leaseEpoch() <= 0) {
            throw new IllegalStateException("async task lease response is invalid");
        }
        AsyncTaskStatus status = job.status() == IngestionStatus.READY
                ? AsyncTaskStatus.SUCCEEDED
                : AsyncTaskStatus.RUNNING;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeJobId", job.jobId());
        result.put("knowledgeStatus", job.status().name());
        result.put("documentId", job.documentId());
        result.put("documentVersion", job.documentVersion());
        result.put("sinks", job.sinks());
        http.patchForObject(
                "/async/tasks/{taskId}/status",
                new AsyncTaskStatusUpdateRequest(
                        status, result, job.error(), workerId, leased.leaseEpoch()),
                Object.class,
                job.jobId());
    }
}
