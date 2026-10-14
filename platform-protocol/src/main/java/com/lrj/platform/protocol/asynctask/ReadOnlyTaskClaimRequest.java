package com.lrj.platform.protocol.asynctask;
/** checkpointVersion 缺省为0，旧 worker 不可领取节点恢复任务。 */
public record ReadOnlyTaskClaimRequest(String workerId, Integer checkpointVersion) {
    public ReadOnlyTaskClaimRequest(String workerId) { this(workerId, 0); }
}
