package com.lrj.platform.knowledge.ingest.job;

public class IngestionJobNotFoundException extends RuntimeException {

    public IngestionJobNotFoundException(String jobId) {
        super("ingestion job not found: " + jobId);
    }
}
