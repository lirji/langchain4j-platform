package com.lrj.platform.knowledge.ingest.job;

/** Ingestion 提交阶段的领域授权拒绝；controller 映射为 403。 */
public class IngestionAuthorizationException extends RuntimeException {

    public IngestionAuthorizationException(String message) {
        super(message);
    }
}
