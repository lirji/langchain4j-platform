package com.lrj.platform.knowledge.ingest.job;

public class NoopIngestionTaskLifecycle implements IngestionTaskLifecycle {

    @Override
    public void ensureTask(IngestionJob job) {
    }

    @Override
    public void synchronize(IngestionJob job) {
    }
}
