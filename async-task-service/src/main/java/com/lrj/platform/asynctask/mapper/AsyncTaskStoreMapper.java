package com.lrj.platform.asynctask.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface AsyncTaskStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<AsyncTaskStoreRow> readInit1();
    /** 保留原writePut1操作的条件与影响行数契约。 */
    int writePut1(
        @Param("taskId") String taskId,
        @Param("tenantId") String tenantId,
        @Param("userId") String userId,
        @Param("kind") String kind,
        @Param("status") String status,
        @Param("inputJson") String inputJson,
        @Param("resultJson") String resultJson,
        @Param("errorText") String errorText,
        @Param("webhookUrl") String webhookUrl,
        @Param("createdAt") Long createdAt,
        @Param("updatedAt") Long updatedAt,
        @Param("finishedAt") Long finishedAt,
        @Param("leaseOwnerId") String leaseOwnerId,
        @Param("leaseExpiresAt") Long leaseExpiresAt,
        @Param("leaseEpoch") Long leaseEpoch);
    /** 保留原readGet1操作的条件与影响行数契约。 */
    List<AsyncTaskStoreRow> readGet1(@Param("taskId") String taskId);
    /** 保留原writeUpdate1操作的条件与影响行数契约。 */
    int writeUpdate1(
        @Param("status") String status,
        @Param("resultJson") String resultJson,
        @Param("errorText") String errorText,
        @Param("updatedAt") Long updatedAt,
        @Param("finishedAt") Long finishedAt,
        @Param("leaseOwnerId") String leaseOwnerId,
        @Param("leaseExpiresAt") Long leaseExpiresAt,
        @Param("taskId") String taskId);
    /** 保留原readListByTenant1操作的条件与影响行数契约。 */
    List<AsyncTaskStoreRow> readListByTenant1(@Param("tenantId") String tenantId);
    /** 保留原readListByOwner1操作的条件与影响行数契约。 */
    List<AsyncTaskStoreRow> readListByOwner1(@Param("tenantId") String tenantId, @Param("userId") String userId);
    /** 保留原readCountByStatus1操作的条件与影响行数契约。 */
    List<Long> readCountByStatus1(@Param("status") String status);
    /** 保留原writeCleanup1操作的条件与影响行数契约。 */
    int writeCleanup1(@Param("finishedAt") Long finishedAt);
    /** 保留原readGetForUpdate1操作的条件与影响行数契约。 */
    List<AsyncTaskStoreRow> readGetForUpdate1(@Param("taskId") String taskId);
    /** 保留原writePersistMutable1操作的条件与影响行数契约。 */
    int writePersistMutable1(
        @Param("status") String status,
        @Param("resultJson") String resultJson,
        @Param("errorText") String errorText,
        @Param("updatedAt") Long updatedAt,
        @Param("finishedAt") Long finishedAt,
        @Param("leaseOwnerId") String leaseOwnerId,
        @Param("leaseExpiresAt") Long leaseExpiresAt,
        @Param("leaseEpoch") Long leaseEpoch,
        @Param("taskId") String taskId);
    /** 按租户和非终态统计排空库存；空集合由调用端短路。 */
    List<AsyncTaskStoreRow> drainInventory(
        @Param("tenant") String tenant,
        @Param("kinds") java.util.Set<String> kinds,
        @Param("statuses") List<String> statuses);
    /** 只读任务有独立租约恢复链路，不参与普通孤儿任务失败处理。 */
    List<String> orphanIds(
        @Param("kinds") java.util.Set<String> kinds,
        @Param("pending") long pending,
        @Param("running") long running,
        @Param("limit") int limit);
    /** 与任务清理同事务处理原外键级联的调度行。 */
    /** 检查点沿用父任务保留期限，逻辑删除不代表物理擦除。 */
    int cleanupCheckpoints(@Param("cutoff") long cutoff);
    int cleanupDispatch(@Param("cutoff") long cutoff);
}
