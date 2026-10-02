package com.lrj.platform.conversation.budget;

import com.lrj.platform.security.InternalTokenAuthFilter;
import com.lrj.platform.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

/** 预算 RPC 不接受普通用户 JWT 结算用量，先校验专用服务令牌再交内部身份过滤器。 */
final class BudgetServiceAuthFilter extends OncePerRequestFilter {
    static final String CLAIMS_ATTRIBUTE = BudgetServiceAuthFilter.class.getName() + ".claims";
    private final BudgetServiceTokens tokens;
    BudgetServiceAuthFilter(BudgetServiceTokens tokens) { this.tokens = tokens; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI();
        String action = "/internal/metering/reservations".equals(path) ? "reserve"
                : "/internal/metering/settlements".equals(path) ? "settle" : null;
        var c = action == null || !"POST".equals(request.getMethod()) ? null
                : tokens.verify(request.getHeader("Authorization"), action);
        if (c == null) {
            response.setStatus(401); response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"valid budget service authentication required\"}"); return;
        }
        request.setAttribute(CLAIMS_ATTRIBUTE, c);
        request.setAttribute(InternalTokenAuthFilter.PREAUTHENTICATED_TENANT_ATTRIBUTE,
                new TenantContext.Tenant(c.get("tenant", String.class), c.get("actor_uid", String.class), Set.of("budget.rpc")));
        chain.doFilter(request, response);
    }
}
