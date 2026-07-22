package com.lrj.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 下游服务的入站 filter：校验内部 JWT（由边缘签发、{@link OutboundTenantForwarder} 转发），
 * 校验通过则把 {@link TenantContext} + MDC 绑上，请求结束清理；业务路径缺失/伪造令牌时默认 401，
 * 防止绕过 edge 以 anonymous 身份调用模型或访问租户数据。
 *
 * <p>本地直连调试时也接受边缘同款 {@code X-Api-Key} → 租户映射（allowApiKeyFallback=true），
 * 方便不经网关单跑一个服务。生产可关闭 fallback，只信 JWT。
 */
public class InternalTokenAuthFilter extends OncePerRequestFilter {

    public static final String MDC_TENANT = "tenantId";
    public static final String MDC_USER = "userId";

    private final InternalToken tokens;
    private final InternalSecurityProperties props;
    private final boolean allowApiKeyFallback;

    public InternalTokenAuthFilter(InternalToken tokens,
                                   InternalSecurityProperties props,
                                   boolean allowApiKeyFallback) {
        this.tokens = tokens;
        this.props = props;
        this.allowApiKeyFallback = allowApiKeyFallback;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        TenantContext.Tenant tenant = resolve(request);
        if (tenant == null && props.isAuthenticationRequired()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"valid internal authentication is required\"}");
            return;
        }
        boolean bound = false;
        if (tenant != null) {
            TenantContext.set(tenant);
            MDC.put(MDC_TENANT, tenant.tenantId());
            MDC.put(MDC_USER, tenant.userId());
            bound = true;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (bound) {
                TenantContext.clear();
                MDC.remove(MDC_TENANT);
                MDC.remove(MDC_USER);
            }
        }
    }

    /** 容器编排与探针不持业务身份；只开放最小健康/信息面，不开放模型或数据端点。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/actuator/health".equals(path)
                || (path != null && path.startsWith("/actuator/health/"))
                || "/actuator/info".equals(path);
    }

    private TenantContext.Tenant resolve(HttpServletRequest request) {
        String jwt = request.getHeader(props.getInternalHeader());
        TenantContext.Tenant t = tokens.verify(jwt);
        if (t != null) return t;
        if (allowApiKeyFallback) {
            String apiKey = request.getHeader(props.getApiKeyHeader());
            if (apiKey != null) {
                InternalSecurityProperties.KeyBinding b = props.getApiKeys().get(apiKey);
                if (b != null) {
                    return new TenantContext.Tenant(b.getTenant(), b.getUser(),
                            b.getScopes() == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(b.getScopes()));
                }
            }
        }
        return null;
    }
}
