package com.lrj.platform.asynctask.mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
/** SQL集中在XML；先锁父任务，再进行检查点CAS。 */
public interface DagCheckpointMapper {
    /** 锁住身份及租约权威行，避免取消/接管与写入交错。 */
    List<AsyncTaskStoreRow> lockTask(@Param("id") String id);
    /** 读取当前已提交版本。 */
    List<DagCheckpointRow> read(@Param("id") String id);
    /** 首次创建，主键拒绝重复。 */
    int insert(@Param("id") String id, @Param("json") String json, @Param("now") long now);
    /** 更新必须检查CAS影响行数。 */
    int update(@Param("id") String id, @Param("revision") long revision, @Param("json") String json, @Param("now") long now);
}
