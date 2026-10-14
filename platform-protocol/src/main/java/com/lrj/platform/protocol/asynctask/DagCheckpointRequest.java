package com.lrj.platform.protocol.asynctask;
import java.util.Map;
/** 同一租约读取或 CAS 保存；expectedRevision=null 仅允许读取。 */
public record DagCheckpointRequest(long leaseEpoch, Long expectedRevision, Map<String, Object> state) { }
