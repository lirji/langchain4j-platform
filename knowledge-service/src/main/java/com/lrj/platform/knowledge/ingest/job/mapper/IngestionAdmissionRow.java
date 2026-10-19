package com.lrj.platform.knowledge.ingest.job.mapper;

/** 领取事务持有的守卫行，额度由首个一致配置绑定，混合配置失败关闭。 */
public final class IngestionAdmissionRow {
    public long grantSequence;
    public Integer globalLimit;
    public Integer tenantLimit;
}
