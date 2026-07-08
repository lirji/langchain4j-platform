package com.lrj.platform.conversation.memory.profile;

import java.util.List;

/**
 * 用户长期记忆存储，按 {@code (tenant, user)} 隔离。默认内存实现；redis 变体留待需要时补
 * （沿用平台「接口 + @ConditionalOnProperty 变体」约定）。
 */
public interface UserProfileStore {

    /** 追加一条（含去重与容量淘汰）。 */
    void add(String tenant, String user, MemoryItem item);

    /** 按插入顺序返回该用户的全部记忆项（最旧在前）。 */
    List<MemoryItem> list(String tenant, String user);

    /** 清空该用户的记忆，返回清除条数。 */
    int clear(String tenant, String user);
}
