package com.lrj.platform.asynctask.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface ReadOnlyTaskDispatchMapper {
    /** 保留原readContract1操作的条件与影响行数契约。 */
    List<ReadOnlyTaskDispatchRow> readContract1();
    /** 保留原readCreate1操作的条件与影响行数契约。 */
    List<Integer> readCreate1();
    /** 保留原readCreate2操作的条件与影响行数契约。 */
    List<Long> readCreate2();
    /** 保留原readCreate3操作的条件与影响行数契约。 */
    List<Long> readCreate3(@Param("tenantId") String tenantId);
    /** 保留原writeCreate1操作的条件与影响行数契约。 */
    int writeCreate1(
        @Param("taskId") String taskId,
        @Param("department") String department,
        @Param("traceId") String traceId,
        @Param("createdAt") Long createdAt);
    /** 保留原readClaim1操作的条件与影响行数契约。 */
    List<String> readClaim1();
    /** 保留原readClaim2操作的条件与影响行数契约。 */
    List<Long> readClaim2(@Param("leaseExpiresAt") Long leaseExpiresAt);
    /** 保留原readClaim3操作的条件与影响行数契约。 */
    List<String> readClaim3(
        @Param("leaseExpiresAt") Long leaseExpiresAt,
        @Param("leaseExpiresAt2") Long leaseExpiresAt2,
        @Param("limit") Integer limit,
        @Param("tenantId") String tenantId,
        @Param("checkpointVersion") Integer checkpointVersion);
    /** 保留原readClaim4操作的条件与影响行数契约。 */
    List<Integer> readClaim4(@Param("taskId") String taskId);
    /** 保留原writeClaim1操作的条件与影响行数契约。 */
    int writeClaim1(@Param("taskId") String taskId);
    /** 保留原writeClaim2操作的条件与影响行数契约。 */
    int writeClaim2(@Param("lastClaimAt") Long lastClaimAt, @Param("lastTenant") String lastTenant);
    /** 保留原readClaim5操作的条件与影响行数契约。 */
    List<ReadOnlyTaskDispatchRow> readClaim5(@Param("taskId") String taskId);
}
