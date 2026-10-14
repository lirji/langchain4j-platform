package com.lrj.platform.workflow.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface WorkflowReplyStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<WorkflowReplyStoreRow> readInit1();
    /** 保留原writeSave1操作的条件与影响行数契约。 */
    int writeSave1(
        @Param("instanceId") String instanceId,
        @Param("reply") String reply,
        @Param("degraded") Integer degraded,
        @Param("updatedAt") Long updatedAt);
    /** 保留原readFind1操作的条件与影响行数契约。 */
    List<WorkflowReplyStoreRow> readFind1(@Param("instanceId") String instanceId);
    /** 保留原writeDelete1操作的条件与影响行数契约。 */
    int writeDelete1(@Param("instanceId") String instanceId);
}
