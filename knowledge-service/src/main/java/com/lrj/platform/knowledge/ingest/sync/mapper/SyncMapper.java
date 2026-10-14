package com.lrj.platform.knowledge.ingest.sync.mapper;

import org.apache.ibatis.annotations.Param;

/** SQL仅在XML中；每次读取和条件写入都限定租户、来源和逻辑删除状态。 */
public interface SyncMapper {
    /** 建立来源检查点。 */
    int insert(@Param("tenant") String tenant, @Param("source") String source, @Param("config") String config);
    /** 查询来源恢复状态。 */
    SyncRow get(@Param("tenant") String tenant, @Param("source") String source);
    /** 到期且无有效租约时领取，配置变化不能接管旧来源。 */
    int claim(@Param("tenant") String tenant, @Param("source") String source, @Param("config") String config,
              @Param("owner") String owner, @Param("now") long now, @Param("until") long until);
    /** CAS保存状态；租约过期的执行器不能提交游标或进度。 */
    int save(@Param("row") SyncRow row, @Param("expected") long expected, @Param("owner") String owner, @Param("now") long now);
    /** 释放租约后在同事务内清空正文快照。 */
    int clearPage(@Param("row") SyncRow row);
    /** 控制操作持有来源行锁，与后台进度和运行记录串行提交。 */
    SyncRow lock(@Param("tenant") String tenant, @Param("source") String source);
    /** 暂停/恢复同时撤销执行租约，避免旧执行器越过暂停提交检查点。 */
    int control(@Param("tenant") String tenant, @Param("source") String source, @Param("expected") long expected, @Param("paused") boolean paused);
    /** 手动触发仅提前下次领取时间，不接管其他实例租约。 */
    int wake(@Param("tenant") String tenant, @Param("source") String source);
    /** 运行快照与检查点由同一事务写入。 */
    int insertRun(@Param("row") SyncRow row, @Param("body") String body);
    /** 隔离读取一个运行。 */
    SyncRunRow run(@Param("tenant") String tenant, @Param("source") String source, @Param("id") String id);
    /** 持有来源行锁后更新运行。 */
    int updateRun(@Param("row") SyncRow row, @Param("body") String body);
    /** 按序号分页，limit由应用限制。 */
    java.util.List<SyncRunRow> runs(@Param("tenant") String tenant, @Param("source") String source, @Param("before") long before, @Param("limit") int limit);
    /** 读取某来源记录最近成功版本。 */
    SyncRow document(@Param("tenant") String tenant, @Param("source") String source, @Param("external") String external);
    /** 只允许单调推进记录版本。 */
    int updateDocument(@Param("tenant") String tenant, @Param("source") String source, @Param("external") String external,
                       @Param("version") long version, @Param("hash") String hash, @Param("documentVersion") long documentVersion, @Param("documentId") String documentId, @Param("externalId") String externalId, @Param("sourceDeleted") boolean sourceDeleted);
    /** 首次成功后建立映射；唯一约束阻止重复记录。 */
    int insertDocument(@Param("tenant") String tenant, @Param("source") String source, @Param("external") String external,
                       @Param("version") long version, @Param("hash") String hash, @Param("documentVersion") long documentVersion, @Param("documentId") String documentId, @Param("externalId") String externalId, @Param("sourceDeleted") boolean sourceDeleted);
}
