package com.lrj.platform.security;

import java.util.Set;

/**
 * Per-request 的租户 / 用户 / scopes 持有者（ThreadLocal）。
 *
 * <p>从原单体 {@code com.lrj.langchain4j.security.TenantContext} 移植。语义不变：入口注入、出口清理，
 * 未设置时返回 {@link #ANONYMOUS} 兜底（单测 / 启动期 / 未挂 filter 的内部调用）。
 *
 * <p>微服务化后的关键变化：ThreadLocal 本身不跨网络。跨服务调用时由 {@link OutboundTenantForwarder}
 * 把当前 Tenant 编成内部 JWT 注入出站请求头，下游 {@link InternalTokenAuthFilter} 校验后重建 Tenant。
 */
public final class TenantContext {

    public static final Tenant ANONYMOUS = new Tenant("anonymous", "anonymous", Set.of());

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    public static Tenant current() {
        Tenant t = CURRENT.get();
        return t == null ? ANONYMOUS : t;
    }

    /** 供跨线程拷贝（task decorator）拿原始值，可能为 null。 */
    public static Tenant captureRaw() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * @param department 用户所属部门 id（{@code <tenantId>_<deptId>}，来自 Casdoor 嵌套 group）；一人一部门。
     *                   可为 null——非知识写路径 / legacy / api-key / 缺 group 的身份不要求部门，仅知识写路径按 mode 处理。
     */
    public record Tenant(String tenantId, String userId, Set<String> scopes, String department) {
        /** 向后兼容：无部门的三参构造（既有构造点默认 {@code department=null}，行为不变）。 */
        public Tenant(String tenantId, String userId, Set<String> scopes) {
            this(tenantId, userId, scopes, null);
        }

        public boolean hasScope(String scope) {
            return scopes != null && scopes.contains(scope);
        }
    }
}
