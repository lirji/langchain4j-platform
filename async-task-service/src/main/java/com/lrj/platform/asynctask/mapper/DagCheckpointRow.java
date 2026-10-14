package com.lrj.platform.asynctask.mapper;
/** 检查点持久化投影，与对外状态分离。 */
public class DagCheckpointRow {
    public Long revision;
    public String stateJson;
}
