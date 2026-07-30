package com.lrj.platform.knowledge.ingest.job;

/**
 * Knowledge durable job 到通用 async-task 生命周期的窄端口。原文和逐 sink 业务状态仍归
 * Knowledge；async-task 拥有跨服务任务信封、SSE 和 webhook 生命周期。
 */
public interface IngestionTaskLifecycle {

    void ensureTask(IngestionJob job);

    void synchronize(IngestionJob job);
}
