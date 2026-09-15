package com.lrj.platform.knowledge.ingest.job;

public enum IngestionStatus {
    RECEIVED,
    PROCESSING,
    READY,
    PARTIAL,
    FAILED,
    MANUAL_REVIEW,
    DELETING,
    DELETED
}
