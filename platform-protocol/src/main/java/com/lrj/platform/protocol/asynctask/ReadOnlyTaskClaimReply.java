package com.lrj.platform.protocol.asynctask;

/** 调度回执仅在领取时携带短时工具凭据, 凭据不落盘。 */
public record ReadOnlyTaskClaimReply(AsyncTask task, String internalToken, String traceId) { }
