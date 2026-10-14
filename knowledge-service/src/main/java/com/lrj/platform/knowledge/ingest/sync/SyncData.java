package com.lrj.platform.knowledge.ingest.sync;

import java.util.List;
import com.lrj.platform.protocol.knowledge.KnowledgeSourceFeed.Item;

/** 同步页和恢复意图；来源版本必须单调递增，内容快照使进程重启后不依赖来源保留旧版本。 */
public final class SyncData {
    private SyncData() {}
    public record Planned(Item item, String documentId, long documentVersion, String hash) {}
    public record Pending(List<Planned> items, String nextCursor, int index) {
        /** 单条已确认入库后推进页内位置；全页完成前不推进来源游标。 */
        public Pending advance() { return new Pending(items, nextCursor, index + 1); }
    }
    public record DocumentState(String sourceId, String externalId, long sourceRevision, String documentId,
                                long documentVersion, boolean deleted) {}
    public record Status(String sourceId, String status, String errorCode, int remaining,
                         String pendingJobId, long nextPollAt) {}
}
