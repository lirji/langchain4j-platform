package com.lrj.platform.security;

/** 当前请求的绝对 epoch-millis deadline；只承载控制信息，不保存业务状态。 */
public final class RequestDeadlineContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private RequestDeadlineContext() {}

    public static void set(long epochMillis) {
        CURRENT.set(epochMillis);
    }

    public static Long captureRaw() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
