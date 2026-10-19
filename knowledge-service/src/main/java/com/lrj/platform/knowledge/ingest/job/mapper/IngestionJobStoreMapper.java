package com.lrj.platform.knowledge.ingest.job.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface IngestionJobStoreMapper {
    /** 保留原writeCreateOrGet1操作的条件与影响行数契约。 */
    int writeCreateOrGet1(
        @Param("tenantId") String tenantId,
        @Param("jobId") String jobId,
        @Param("idempotencyKey") String idempotencyKey,
        @Param("userId") String userId,
        @Param("scopesJson") String scopesJson,
        @Param("department") String department,
        @Param("traceId") String traceId,
        @Param("documentId") String documentId,
        @Param("displayName") String displayName,
        @Param("category") String category,
        @Param("documentVersion") Long documentVersion,
        @Param("newDocument") Boolean newDocument,
        @Param("revision") Long revision,
        @Param("sourceBucket") String sourceBucket,
        @Param("sourceObjectKey") String sourceObjectKey,
        @Param("sourceHash") String sourceHash,
        @Param("sourceContentType") String sourceContentType,
        @Param("sourceSize") Long sourceSize,
        @Param("status") String status,
        @Param("sinksJson") String sinksJson,
        @Param("requiredSinksJson") String requiredSinksJson,
        @Param("errorText") String errorText,
        @Param("createdAt") java.sql.Timestamp createdAt,
        @Param("updatedAt") java.sql.Timestamp updatedAt,
        @Param("retryCount") Integer retryCount,
        @Param("nextRetryAt") java.sql.Timestamp nextRetryAt,
        @Param("errorCode") String errorCode,
        @Param("failedStage") String failedStage,
        @Param("leaseOwner") String leaseOwner,
        @Param("leaseUntil") java.sql.Timestamp leaseUntil,
        @Param("preparationJson") String preparationJson);
    /** 保留原readFind1操作的条件与影响行数契约。 */
    List<IngestionJobStoreRow> readFind1(@Param("tenantId") String tenantId, @Param("jobId") String jobId);
    /** 保留原writeSave1操作的条件与影响行数契约。 */
    int writeSave1(
        @Param("revision") Long revision,
        @Param("status") String status,
        @Param("sinksJson") String sinksJson,
        @Param("requiredSinksJson") String requiredSinksJson,
        @Param("errorText") String errorText,
        @Param("updatedAt") java.sql.Timestamp updatedAt,
        @Param("retryCount") Integer retryCount,
        @Param("nextRetryAt") java.sql.Timestamp nextRetryAt,
        @Param("errorCode") String errorCode,
        @Param("failedStage") String failedStage,
        @Param("leaseOwner") String leaseOwner,
        @Param("leaseUntil") java.sql.Timestamp leaseUntil,
        @Param("tenantId") String tenantId,
        @Param("jobId") String jobId,
        @Param("revision2") Long revision2,
        @Param("preparationJson") String preparationJson,
        @Param("pendingPreparationJson") String pendingPreparationJson);
    /** 保留原readFindRunnable1操作的条件与影响行数契约。 */
    List<IngestionJobStoreRow> readFindRunnable1(@Param("limit") Integer limit);
    /** 保留原readFindRecoverable1操作的条件与影响行数契约。 */
    List<IngestionJobStoreRow> readFindRecoverable1(
        @Param("nextRetryAt") java.sql.Timestamp nextRetryAt,
        @Param("leaseUntil") java.sql.Timestamp leaseUntil,
        @Param("updatedAt") java.sql.Timestamp updatedAt,
        @Param("limit") Integer limit);
    /** 保留原readFindByIdempotency1操作的条件与影响行数契约。 */
    List<IngestionJobStoreRow> readFindByIdempotency1(@Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);
    /** 保留原readOldestPending1操作的条件与影响行数契约。 */
    List<java.sql.Timestamp> readOldestPending1();
    /** 保留原readInitialize1操作的条件与影响行数契约。 */
    List<IngestionJobStoreRow> readInitialize1();
    /** 状态统计由数据库聚合，避免拉取任务明细。 */
    List<IngestionJobStoreRow> countsByStatus();
}
