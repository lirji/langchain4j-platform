package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.ingest.job.IngestionJob;
import com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService;
import com.lrj.platform.observability.TraceIdFilter;
import com.lrj.platform.security.TenantContext;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/** Durable Knowledge ingestion v2 API；旧同步 `/rag/documents` 契约保持不变。 */
@RestController
@RequestMapping("/rag/ingestions")
@ConditionalOnExpression(
        "'${app.rag.runtime.role:combined}' == 'combined'"
                + " || '${app.rag.runtime.role:combined}' == 'ingest-api'")
public class IngestionController {

    private final IngestionSubmissionService submissions;

    public IngestionController(IngestionSubmissionService submissions) {
        this.submissions = submissions;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionJobView> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam String documentId,
            @RequestParam long documentVersion,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String category,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        TenantContext.Tenant tenant = requireIngest();
        if (file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        IngestionJob job = submissions.submit(new IngestionSubmissionService.SubmitCommand(
                idempotencyKey,
                tenant.tenantId(),
                tenant.userId(),
                tenant.scopes(),
                tenant.department(),
                MDC.get(TraceIdFilter.MDC_KEY),
                documentId,
                displayName == null || displayName.isBlank()
                        ? (file.getOriginalFilename() == null
                        ? documentId
                        : file.getOriginalFilename())
                        : displayName,
                category,
                documentVersion,
                file.getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                        : file.getContentType(),
                file.getBytes()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(IngestionJobView.from(job));
    }

    @GetMapping("/{jobId}")
    public IngestionJobView get(@PathVariable String jobId) {
        TenantContext.Tenant tenant = TenantContext.current();
        return IngestionJobView.from(submissions.get(tenant.tenantId(), jobId));
    }

    private static TenantContext.Tenant requireIngest() {
        TenantContext.Tenant tenant = TenantContext.current();
        if (!tenant.hasScope("ingest")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ingest scope required");
        }
        return tenant;
    }

    public record IngestionJobView(
            String jobId,
            String documentId,
            long documentVersion,
            String status,
            Map<String, String> sinks,
            String contentHash,
            String traceId
    ) {
        static IngestionJobView from(IngestionJob job) {
            return new IngestionJobView(
                    job.jobId(),
                    job.documentId(),
                    job.documentVersion(),
                    job.status().name(),
                    job.sinks().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            entry -> entry.getKey().name(),
                            entry -> entry.getValue().name())),
                    job.source().contentHash(),
                    job.traceId());
        }
    }
}
