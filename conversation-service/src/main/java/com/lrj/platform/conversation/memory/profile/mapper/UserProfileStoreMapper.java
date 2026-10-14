package com.lrj.platform.conversation.memory.profile.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface UserProfileStoreMapper {
    /** 保留原readContract1操作的条件与影响行数契约。 */
    List<UserProfileStoreRow> readContract1();
    /** 保留原readContract2操作的条件与影响行数契约。 */
    List<UserProfileStoreRow> readContract2();
    /** 保留原readContract3操作的条件与影响行数契约。 */
    List<UserProfileStoreRow> readContract3();
    /** 保留原writeInsert1操作的条件与影响行数契约。 */
    int writeInsert1(
        @Param("ownerKey") String ownerKey,
        @Param("memoryId") String memoryId,
        @Param("memoryText") String memoryText,
        @Param("memoryType") String memoryType,
        @Param("createdAt") Long createdAt,
        @Param("sourceChatId") String sourceChatId,
        @Param("memoryState") String memoryState);
    /** 保留原readInsert1操作的条件与影响行数契约。 */
    List<Long> readInsert1(@Param("ownerKey") String ownerKey);
    /** 保留原writeClear1操作的条件与影响行数契约。 */
    int writeClear1(@Param("ownerKey") String ownerKey);
    /** 保留原writeDecide1操作的条件与影响行数契约。 */
    int writeDecide1(
        @Param("memoryText") String memoryText,
        @Param("memoryType") String memoryType,
        @Param("pinned") Boolean pinned,
        @Param("expiresAt") Long expiresAt,
        @Param("ownerKey") String ownerKey,
        @Param("recordId") Long recordId,
        @Param("revision") Long revision);
    /** 保留原writeSettings1操作的条件与影响行数契约。 */
    int writeSettings1(@Param("memoryEnabled") Boolean memoryEnabled, @Param("ownerKey") String ownerKey);
    /** 保留原readAudit1操作的条件与影响行数契约。 */
    List<UserProfileStoreRow> readAudit1(
        @Param("ownerKey") String ownerKey,
        @Param("auditId") Long auditId,
        @Param("limit") Integer limit);
    /** 保留原writeLockOwner1操作的条件与影响行数契约。 */
    int writeLockOwner1(@Param("ownerKey") String ownerKey, @Param("tenantId") String tenantId, @Param("userId") String userId);
    /** 保留原readLockOwner1操作的条件与影响行数契约。 */
    List<UserProfileStoreRow> readLockOwner1(@Param("ownerKey") String ownerKey);
    /** 保留原writeBump1操作的条件与影响行数契约。 */
    int writeBump1(@Param("ownerKey") String ownerKey);
    /** 保留原writeDelete1操作的条件与影响行数契约。 */
    int writeDelete1(@Param("ownerKey") String ownerKey, @Param("recordId") Long recordId, @Param("revision") Long revision);
    /** 保留原writeAudit1操作的条件与影响行数契约。 */
    int writeAudit1(
        @Param("ownerKey") String ownerKey,
        @Param("recordId") Long recordId,
        @Param("actionCode") String actionCode,
        @Param("revision") Long revision,
        @Param("occurredAt") Long occurredAt);
    /** 所有者锁内读取有界记忆，锁模式只控制固定FOR UPDATE子句。 */
    List<UserProfileStoreRow> entries(@Param("owner") String owner, @Param("limit") int limit, @Param("locked") boolean locked);
}
