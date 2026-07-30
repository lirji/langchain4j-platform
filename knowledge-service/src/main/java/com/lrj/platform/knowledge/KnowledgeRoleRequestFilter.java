package com.lrj.platform.knowledge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 同一 artifact 的角色级 HTTP 边界。鉴权 filter 先执行，本 filter 再隐藏不属于该 Deployment 的 surface。
 */
public class KnowledgeRoleRequestFilter extends OncePerRequestFilter {

    private final KnowledgeRuntimeProperties.Role role;

    public KnowledgeRoleRequestFilter(KnowledgeRuntimeProperties.Role role) {
        this.role = role;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (allows(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    boolean allows(HttpServletRequest request) {
        if (role == KnowledgeRuntimeProperties.Role.COMBINED) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/actuator/") || path.equals("/actuator")
                || path.equals("/error")) {
            return true;
        }
        return switch (role) {
            case QUERY -> (isReadMethod(request.getMethod())
                    && path.startsWith("/rag/")
                    && !path.startsWith("/rag/ingestions"))
                    || isQueryPost(request.getMethod(), path);
            case INGEST_API -> path.startsWith("/rag/ingestions");
            case INGEST_WORKER -> false;
            case COMBINED -> true;
        };
    }

    private static boolean isReadMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    /**
     * 检索采用 POST 是因为查询参数是结构化 JSON；这些端点仍是无副作用的 query surface。
     * 只列白名单，不能因同属 /rag 前缀而放开文档/图片入库。
     */
    private static boolean isQueryPost(String method, String path) {
        if (!"POST".equals(method)) {
            return false;
        }
        return "/rag/query".equals(path)
                || "/knowledge/query".equals(path)
                || "/rag/graph/query".equals(path)
                || "/rag/image-search".equals(path);
    }
}
