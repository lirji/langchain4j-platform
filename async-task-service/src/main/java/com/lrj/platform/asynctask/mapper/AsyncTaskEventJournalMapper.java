package com.lrj.platform.asynctask.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface AsyncTaskEventJournalMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<AsyncTaskEventJournalRow> readInit1();
    /** 保留原readAppend1操作的条件与影响行数契约。 */
    List<AsyncTaskEventJournalRow> readAppend1(@Param("taskId") String taskId);
    /** 保留原readAppend2操作的条件与影响行数契约。 */
    List<Long> readAppend2(@Param("taskId") String taskId);
    /** 保留原writeAppend1操作的条件与影响行数契约。 */
    int writeAppend1(
        @Param("taskId") String taskId,
        @Param("sequence") Long sequence,
        @Param("eventKey") String eventKey,
        @Param("eventName") String eventName,
        @Param("dataJson") String dataJson,
        @Param("createdAt") Long createdAt,
        @Param("workerId") String workerId);
    /** 保留原readEventsAfter1操作的条件与影响行数契约。 */
    List<AsyncTaskEventJournalRow> readEventsAfter1(@Param("taskId") String taskId, @Param("sequence") Long sequence);
    /** 保留原readLatest1操作的条件与影响行数契约。 */
    List<AsyncTaskEventJournalRow> readLatest1(@Param("taskId") String taskId);
    /** 保留原writeCleanupBefore1操作的条件与影响行数契约。 */
    int writeCleanupBefore1(@Param("createdAt") Long createdAt);
    /** 保留原readFindByEventKey1操作的条件与影响行数契约。 */
    List<AsyncTaskEventJournalRow> readFindByEventKey1(@Param("taskId") String taskId, @Param("eventKey") String eventKey);
}
