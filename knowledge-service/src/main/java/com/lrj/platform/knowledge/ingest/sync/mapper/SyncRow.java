package com.lrj.platform.knowledge.ingest.sync.mapper;

/** 持久化行使用显式字段，不将数据库对象直接暴露给接口。 */
public final class SyncRow {
    public String tenantKey;
    public String sourceKey;
    public String configHash;
    public String cursorValue;
    public String pendingJson;
    public String errorCode;
    public String jobId;
    public long revision;
    public String leaseOwner;
    public long leaseUntil;
    public long nextPollAt;
    public boolean paused;
    public long controlRevision;
    public Long lastSuccessAt;
    public String activeRunId;
    public long sourceRevision;
    public String contentHash;
    public long documentVersion;
    public String documentId;
    public String externalId;
    public boolean sourceDeleted;
}
