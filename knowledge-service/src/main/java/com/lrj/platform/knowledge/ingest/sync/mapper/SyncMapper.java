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
    /** 读取某来源记录最近成功版本。 */
    SyncRow document(@Param("tenant") String tenant, @Param("source") String source, @Param("external") String external);
    /** 只允许单调推进记录版本。 */
    int updateDocument(@Param("tenant") String tenant, @Param("source") String source, @Param("external") String external,
                       @Param("version") long version, @Param("hash") String hash, @Param("documentVersion") long documentVersion, @Param("documentId") String documentId, @Param("externalId") String externalId, @Param("sourceDeleted") boolean sourceDeleted);
    /** 首次成功后建立映射；唯一约束阻止重复记录。 */
    int insertDocument(@Param("tenant") String tenant, @Param("source") String source, @Param("external") String external,
                       @Param("version") long version, @Param("hash") String hash, @Param("documentVersion") long documentVersion, @Param("documentId") String documentId, @Param("externalId") String externalId, @Param("sourceDeleted") boolean sourceDeleted);
}
