package com.lrj.platform.workflow.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface WorkflowIdempotencyStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<WorkflowIdempotencyStoreRow> readInit1();
    /** 保留原writeClaim1操作的条件与影响行数契约。 */
    int writeClaim1(
        @Param("tenantId") String tenantId,
        @Param("operationName") String operationName,
        @Param("idempotencyKeyHash") String idempotencyKeyHash,
        @Param("requestHash") String requestHash,
        @Param("businessKey") String businessKey,
        @Param("createdAt") Long createdAt,
        @Param("updatedAt") Long updatedAt);
    /** 保留原readClaim1操作的条件与影响行数契约。 */
    List<WorkflowIdempotencyStoreRow> readClaim1(
        @Param("tenantId") String tenantId,
        @Param("operationName") String operationName,
        @Param("idempotencyKeyHash") String idempotencyKeyHash);
    /** 保留原writeAttachInstance1操作的条件与影响行数契约。 */
    int writeAttachInstance1(
        @Param("instanceId") String instanceId,
        @Param("updatedAt") Long updatedAt,
        @Param("tenantId") String tenantId,
        @Param("operationName") String operationName,
        @Param("idempotencyKeyHash") String idempotencyKeyHash,
        @Param("requestHash") String requestHash);
    /** 保留原readFindCommitted1操作的条件与影响行数契约。 */
    List<WorkflowIdempotencyStoreRow> readFindCommitted1(
        @Param("tenantId") String tenantId,
        @Param("operationName") String operationName,
        @Param("idempotencyKeyHash") String idempotencyKeyHash);
    /** 保留原writeDeleteByInstance1操作的条件与影响行数契约。 */
    int writeDeleteByInstance1(@Param("instanceId") String instanceId);
}
