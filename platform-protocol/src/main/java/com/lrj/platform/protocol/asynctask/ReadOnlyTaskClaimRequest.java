package com.lrj.platform.protocol.asynctask;

/** 独立只读worker单条领取请求, 身份必须与专用JWT一致。 */
public record ReadOnlyTaskClaimRequest(String workerId) { }
