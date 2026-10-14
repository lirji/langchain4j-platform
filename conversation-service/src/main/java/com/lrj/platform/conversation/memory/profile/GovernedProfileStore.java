package com.lrj.platform.conversation.memory.profile;

import java.util.List;

/** 持久化治理契约；开发用内存实现不冒充支持跨实例代次与审计。 */
public interface GovernedProfileStore extends UserProfileStore {
    /** 在对话/抽取前捕获代次，停用返回 -1。 */
    long generation(String tenant, String user);
    /** 原子校验代次并写入待确认候选；过期任务或容量不足返回 false。 */
    boolean offer(String tenant, String user, MemoryItem item, long generation);
    /** 读取当前用户治理视图，包括尚未确认和已过期的条目。 */
    Snapshot snapshot(String tenant, String user);
    /** 在当前用户范围内进行版本受控的状态变更。 */
    void decide(String tenant, String user, long recordId, Decision decision);
    /** 使用用户代次作为开关变更的并发条件。 */
    void settings(String tenant, String user, long expectedGeneration, boolean enabled);
    /** 稳定游标分页；身份来自调用上下文，不能从客户端覆盖。 */
    List<Audit> audit(String tenant, String user, long beforeId, int limit);

    enum Action { APPROVE, REJECT, FORGET, UPDATE, PIN, UNPIN }
    record Decision(Long expectedVersion, Action action, String text, String type, Long expiresAtEpochMs) {}
    record Snapshot(boolean enabled, long generation, List<Entry> items) {}
    record Entry(long recordId, String id, String text, String type, long createdAtEpochMs,
                 String sourceChatId, String state, long version, boolean pinned, Long expiresAtEpochMs) {
        /** 旧外部 DTO 保持不变；治理字段只由新接口提供。 */
        MemoryItem legacy() { return new MemoryItem(id, text, type, createdAtEpochMs, sourceChatId); }
    }
    record Audit(long auditId, long recordId, String action, long version, long occurredAtEpochMs) {}
}
