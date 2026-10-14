package com.lrj.platform.knowledge.ingest.sync.mapper;

/** 运行记录存储快照；序号用于稳定分页，正文仅含脱敏运营结果。 */
public final class SyncRunRow {
    public long seq;
    public String id;
    public String bodyJson;
}
