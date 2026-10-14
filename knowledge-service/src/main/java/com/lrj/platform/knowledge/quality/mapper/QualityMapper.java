package com.lrj.platform.knowledge.quality.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 所有SQL集中在XML，租户与版本作为绑定参数，不接受动态SQL片段。 */
public interface QualityMapper {
    /** 有界读取评测集列表。 */
    List<QualityRow> datasets(@Param("tenant") String tenant, @Param("before") String before, @Param("limit") int limit);
    /** 租户内读取定义。 */
    QualityRow dataset(@Param("tenant") String tenant, @Param("id") String id);
    /** 查询创建幂等意图。 */
    QualityRow datasetRequest(@Param("tenant") String tenant, @Param("key") String key);
    /** 原子插入新评测集。 */
    int insertDataset(@Param("tenant") String tenant, @Param("id") String id, @Param("key") String key,
                      @Param("hash") String hash, @Param("body") String body);
    /** 空闲评测集才允许CAS编辑或切换基线。 */
    int updateDataset(@Param("tenant") String tenant, @Param("id") String id, @Param("expected") long expected, @Param("body") String body);
    /** 同一评测集只能有一个活动运行；网络请求不持有数据库事务。 */
    int claim(@Param("tenant") String tenant, @Param("id") String id, @Param("expected") long expected, @Param("run") String run);
    /** 只释放属于本运行的租约。 */
    int release(@Param("tenant") String tenant, @Param("id") String id, @Param("run") String run);
    /** 运行幂等查询。 */
    QualityRow runRequest(@Param("tenant") String tenant, @Param("dataset") String dataset, @Param("key") String key);
    /** 运行详情始终限定租户。 */
    QualityRow run(@Param("tenant") String tenant, @Param("id") String id);
    /** 运行历史有界分页。 */
    List<QualityRow> runs(@Param("tenant") String tenant, @Param("dataset") String dataset, @Param("before") String before, @Param("limit") int limit);
    /** 保存不可变运行意图。 */
    int insertRun(@Param("tenant") String tenant, @Param("id") String id, @Param("dataset") String dataset,
                  @Param("key") String key, @Param("hash") String hash, @Param("deadline") long deadline, @Param("body") String body);
    /** 只有RUNNING可提交终态，旧执行器不能覆盖已终止记录。 */
    int finish(@Param("tenant") String tenant, @Param("id") String id, @Param("status") String status, @Param("body") String body);
    /** 与业务更新同事务记录操作者。 */
    int audit(@Param("tenant") String tenant, @Param("dataset") String dataset, @Param("actor") String actor,
              @Param("action") String action, @Param("subject") String subject, @Param("at") long at);
}
