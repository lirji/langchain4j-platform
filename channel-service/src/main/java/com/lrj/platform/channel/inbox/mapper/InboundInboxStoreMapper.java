package com.lrj.platform.channel.inbox.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface InboundInboxStoreMapper {
    /** 保留原readContract1操作的条件与影响行数契约。 */
    List<InboundInboxStoreRow> readContract1();
    /** 保留原writeReceive1操作的条件与影响行数契约。 */
    int writeReceive1(
        @Param("inboxId") String inboxId,
        @Param("tenantId") String tenantId,
        @Param("sourceName") String sourceName,
        @Param("messageId") String messageId,
        @Param("payloadHash") String payloadHash,
        @Param("payloadJson") String payloadJson,
        @Param("traceId") String traceId,
        @Param("nextAttemptAt") Long nextAttemptAt,
        @Param("createdAt") Long createdAt,
        @Param("updatedAt") Long updatedAt);
    /** 保留原readReceive1操作的条件与影响行数契约。 */
    List<InboundInboxStoreRow> readReceive1(
        @Param("inboxId") String inboxId,
        @Param("tenantId") String tenantId,
        @Param("sourceName") String sourceName);
    /** 保留原readClaimDue1操作的条件与影响行数契约。 */
    List<InboundInboxStoreRow> readClaimDue1(
        @Param("tenantId") String tenantId,
        @Param("sourceName") String sourceName,
        @Param("nextAttemptAt") Long nextAttemptAt,
        @Param("leaseUntil") Long leaseUntil,
        @Param("limit") Integer limit);
    /** 保留原writeClaimDue1操作的条件与影响行数契约。 */
    int writeClaimDue1(
        @Param("leaseOwner") String leaseOwner,
        @Param("leaseUntil") Long leaseUntil,
        @Param("updatedAt") Long updatedAt,
        @Param("inboxId") String inboxId,
        @Param("tenantId") String tenantId,
        @Param("leaseEpoch") Long leaseEpoch,
        @Param("nextAttemptAt") Long nextAttemptAt,
        @Param("leaseUntil2") Long leaseUntil2);
    /** 保留原writeRenew1操作的条件与影响行数契约。 */
    int writeRenew1(
        @Param("leaseUntil") Long leaseUntil,
        @Param("updatedAt") Long updatedAt,
        @Param("inboxId") String inboxId,
        @Param("tenantId") String tenantId,
        @Param("leaseOwner") String leaseOwner,
        @Param("leaseEpoch") Long leaseEpoch,
        @Param("leaseUntil2") Long leaseUntil2);
    /** 保留原writeFinish1操作的条件与影响行数契约。 */
    int writeFinish1(
        @Param("statusCode") String statusCode,
        @Param("nextAttemptAt") Long nextAttemptAt,
        @Param("updatedAt") Long updatedAt,
        @Param("errorCode") String errorCode,
        @Param("inboxId") String inboxId,
        @Param("tenantId") String tenantId,
        @Param("leaseOwner") String leaseOwner,
        @Param("leaseEpoch") Long leaseEpoch,
        @Param("leaseUntil") Long leaseUntil);
    /** 保留原readList1操作的条件与影响行数契约。 */
    List<InboundInboxStoreRow> readList1(@Param("tenantId") String tenantId, @Param("limit") Integer limit);
    /** 保留原writeReplay1操作的条件与影响行数契约。 */
    int writeReplay1(
        @Param("nextAttemptAt") Long nextAttemptAt,
        @Param("updatedAt") Long updatedAt,
        @Param("inboxId") String inboxId,
        @Param("tenantId") String tenantId);
    /** 保留原readCleanupSucceeded1操作的条件与影响行数契约。 */
    List<InboundInboxStoreRow> readCleanupSucceeded1(@Param("updatedAt") Long updatedAt, @Param("limit") Integer limit);
    /** 保留原writeCleanupSucceeded1操作的条件与影响行数契约。 */
    int writeCleanupSucceeded1(@Param("inboxId") String inboxId, @Param("updatedAt") Long updatedAt);
}
