package com.lrj.platform.workflow.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface WorkflowOutboxMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<WorkflowOutboxRow> readInit1();
    /** 保留原writeEnqueue1操作的条件与影响行数契约。 */
    int writeEnqueue1(
        @Param("instanceId") String instanceId,
        @Param("tenantId") String tenantId,
        @Param("targetUrl") String targetUrl,
        @Param("nextAttemptAt") Long nextAttemptAt,
        @Param("createdAt") Long createdAt,
        @Param("updatedAt") Long updatedAt);
    /** 保留原readClaimDue1操作的条件与影响行数契约。 */
    List<String> readClaimDue1(
        @Param("nextAttemptAt") Long nextAttemptAt,
        @Param("claimedUntil") Long claimedUntil,
        @Param("limit") Integer limit);
    /** 保留原writeMarkDelivered1操作的条件与影响行数契约。 */
    int writeMarkDelivered1(
        @Param("updatedAt") Long updatedAt,
        @Param("instanceId") String instanceId,
        @Param("claimedBy") String claimedBy,
        @Param("claimedUntil") Long claimedUntil);
    /** 保留原writeMarkRetry1操作的条件与影响行数契约。 */
    int writeMarkRetry1(
        @Param("attempts") Integer attempts,
        @Param("nextAttemptAt") Long nextAttemptAt,
        @Param("lastError") String lastError,
        @Param("updatedAt") Long updatedAt,
        @Param("instanceId") String instanceId,
        @Param("claimedBy") String claimedBy,
        @Param("claimedUntil") Long claimedUntil);
    /** 保留原writeMarkDead1操作的条件与影响行数契约。 */
    int writeMarkDead1(
        @Param("attempts") Integer attempts,
        @Param("lastError") String lastError,
        @Param("updatedAt") Long updatedAt,
        @Param("instanceId") String instanceId,
        @Param("claimedBy") String claimedBy,
        @Param("claimedUntil") Long claimedUntil);
    /** 保留原writeDelete1操作的条件与影响行数契约。 */
    int writeDelete1(@Param("instanceId") String instanceId);
    /** 条件领取；竞争者只能更新仍可领取的行。 */
    int claim(@Param("ids") List<String> ids, @Param("owner") String owner, @Param("until") long until, @Param("now") long now);
    /** 仅返回当前执行器实际领取成功的行。 */
    List<WorkflowOutboxRow> claimed(@Param("ids") List<String> ids, @Param("owner") String owner, @Param("limit") int limit);
}
