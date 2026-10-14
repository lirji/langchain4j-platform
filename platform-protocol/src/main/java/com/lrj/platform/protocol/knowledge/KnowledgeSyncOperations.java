package com.lrj.platform.protocol.knowledge;

import java.util.List;

/** 数据接入运营契约：时间为UTC毫秒，摘要不可用于还原游标，不包含来源凭据和正文。 */
public final class KnowledgeSyncOperations {
    private KnowledgeSyncOperations() {}
    /** 稳定协议code不依赖ordinal；新增未知状态需显式升级消费者。 */
    public enum RunState {
        RUNNING("RUNNING"), WAITING("WAITING"), BLOCKED("BLOCKED"), PAUSED("PAUSED"), SUCCEEDED("SUCCEEDED");
        private final String code;
        RunState(String code) { this.code = code; }
        public String code() { return code; }
        public boolean matches(String value) { return code.equals(value); }
    }
    public enum RecordState {
        WAITING("WAITING"), FAILED("FAILED"), SUCCEEDED("SUCCEEDED");
        private final String code;
        RecordState(String code) { this.code = code; }
        public String code() { return code; }
        public boolean matches(String value) { return code.equals(value); }
    }
    public enum Action {
        CREATED("CREATED"), UPDATED("UPDATED"), DELETED("DELETED"), SKIPPED("SKIPPED"), LEGACY_CONFIRMED("LEGACY_CONFIRMED");
        private final String code;
        Action(String code) { this.code = code; }
        public String code() { return code; }
        public boolean matches(String value) { return code.equals(value); }
    }
    public record Status(String sourceId, String status, String errorCode, int remaining,
                         String pendingJobId, long nextPollAt, boolean paused, long controlRevision,
                         Long lastSuccessAt, String activeRunId, String executionUserId) {}
    public record Control(Long expectedRevision) {
        /** 必须显式提交读到的控制版本，缺失字段不能退化为版本0。 */
        public Control {
            if (expectedRevision == null || expectedRevision < 0) throw new IllegalArgumentException("expectedRevision is required and non-negative");
        }
    }
    public record RecordResult(String externalId, long sourceRevision, String documentId,
                               String action, String status, String errorCode, String jobId) {}
    public record Run(String id, String sequence, String sourceId, String status, String triggeredBy,
                      String executionUserId, long startedAt, long updatedAt, Long finishedAt,
                      String configHash, String checkpointBeforeHash, String checkpointAfterHash,
                      Integer fetchedCount, int remaining, String errorCode, List<RecordResult> records) {
        /** 数量从每条记录的最新结果推导，重试不能重复累计成功或失败。 */
        public long getCreated() { return count(Action.CREATED); }
        public long getUpdated() { return count(Action.UPDATED); }
        public long getDeleted() { return count(Action.DELETED); }
        public long getSkipped() { return count(Action.SKIPPED); }
        /** 升级前快照缺少操作分类时保留事实，不推断为新增或更新。 */
        public long getLegacyConfirmed() { return count(Action.LEGACY_CONFIRMED); }
        public long getFailed() { return records.stream().filter(r -> RecordState.FAILED.matches(r.status())).count(); }
        private long count(Action action) { return records.stream().filter(r -> action.matches(r.action()) && RecordState.SUCCEEDED.matches(r.status())).count(); }
    }
    public record Runs(List<Run> items, String nextCursor) {}
}
