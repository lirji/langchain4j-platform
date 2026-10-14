package com.lrj.platform.metering;

import com.lrj.platform.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * per-tenant 日 token 预算的<strong>请求前预检</strong>：租户今日已用满预算时，发起新生成工作的请求直接 429，
 * 不再进入模型调用。
 *
 * <p>为什么不在 {@link TokenBudgetChatModelListener} 里拦：langchain4j 会吞掉 listener 抛出的异常，
 * listener 只能记账、拦不住调用。预检放在入口，扣减仍由 listener 按真实 usage 回填——因此是
 * 「用满后的下一次请求被拒」，单次请求可以把当日用量推过预算（与按成功消耗计费的口径一致）。
 *
 * <p>只拦 {@code POST} 且命中 {@link TokenBudgetProperties.Guard#getPaths()} 的请求：任务状态查询、
 * 画像读取、检索等不花 token 的读操作不受影响。计数后端不可用（如 Redis 宕机）时放行并计数
 * {@code outcome=check_failed}——预算是成本控制，不应把存储故障放大成全站对话不可用。
 *
 * <p>必须排在 {@code InternalTokenAuthFilter} 之后（租户已绑定到 {@link TenantContext}）。
 */
public class TokenBudgetGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetGuardFilter.class);
    static final String METRIC = "platform.token_budget.guard";

    private final TokenBudgetTracker tracker;
    private final List<String> paths;
    private final MeterRegistry meters;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public TokenBudgetGuardFilter(TokenBudgetTracker tracker, List<String> paths, MeterRegistry meters) {
        this.tracker = tracker;
        this.paths = List.copyOf(paths);
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return paths.stream().noneMatch(p -> matcher.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = TenantContext.current().tenantId();
        boolean exceeded;
        try {
            exceeded = tracker.wouldExceed(tenantId);
        } catch (RuntimeException e) {
            log.warn("token-budget check unavailable, allowing request tenant={}: {}", tenantId, e.toString());
            count("check_failed");
            chain.doFilter(request, response);
            return;
        }
        if (!exceeded) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = tracker.secondsUntilReset();
        count("rejected");
        log.info("token-budget exhausted tenant={} path={} retryAfter={}s",
                tenantId, request.getRequestURI(), retryAfter);
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"token_budget_exhausted\",\"retryAfterSeconds\":" + retryAfter + "}");
    }

    private void count(String outcome) {
        if (meters != null) {
            meters.counter(METRIC, "outcome", outcome).increment();
        }
    }
}
