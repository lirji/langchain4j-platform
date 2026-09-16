package com.lrj.platform.edge;

import java.util.List;
import java.util.Map;

/**
 * 边缘免鉴权（open）路径的单一判定源，供 {@link SessionBearerAuthFilter}、
 * {@link ApiKeyToInternalTokenFilter}、{@link EdgeRateLimitFilter} 共用，避免各处 isOpen 漂移。
 *
 * <p>放行的都是"天然无平台凭证"的入口：健康检查/发现类、第三方签名验真的回调、以及登录本身
 * （{@code /auth/login|refresh|logout}——用户此刻还没有 Bearer；{@code /auth/me} 仍需 Bearer，不在此列）。
 *
 * <p><b>免鉴权 ≠ 免限流。</b>这些路径没有租户身份，无法进入 (tenant, family) 桶，早期因此被
 * {@link EdgeRateLimitFilter} 整体跳过——结果是公网上唯一完全无速率保护的面，恰好又是代价最高的两类：
 * 账号入口可被暴力破解/刷注册，渠道回调每条事件都会触发下游检索与 LLM 花费。因此业务类免鉴权路径
 * 一律有限流 family，按客户端 IP 限桶（见 {@link EdgeRateLimitFilter#clientBucketKey}）。
 *
 * <p>为此这里把免鉴权路径声明成**「路径 → family」的表**而不是一串 {@code ||}：新增回调只能往表里加，
 * 而 {@link Map#of} 不接受 null value，所以「加了免鉴权路径但忘了限流」在结构上不可能发生。
 * 只有探针/发现类端点走 {@link #OPEN_INFRA_PREFIXES}（限流它们会误伤存活检查）。
 */
final class EdgeOpenPaths {

    /** 登录/注册/刷新等无凭证账号入口的限流 family（暴力破解与刷注册的主要面）。 */
    static final String FAMILY_AUTH = "auth";

    /** 第三方渠道回调的限流 family（每条入站事件都会触发下游检索 + LLM 花费）。 */
    static final String FAMILY_CHANNEL_CALLBACK = "channel-callback";

    /** 免鉴权的**业务**入口 → 限流 family。 */
    private static final Map<String, String> OPEN_BUSINESS_PATHS = Map.of(
            // 登录/注册/刷新/登出：用户尚无会话令牌，凭 cookie 或账号密码，不经边缘鉴权。
            "/auth/login", FAMILY_AUTH,
            "/auth/register", FAMILY_AUTH,
            "/auth/refresh", FAMILY_AUTH,
            "/auth/logout", FAMILY_AUTH,
            // 公开最小配置（注册开关/密码长度）：未登录前端渲染登录/注册页前拉取，非敏感。
            "/auth/public-config", FAMILY_AUTH,
            // 飞书事件回调不带平台 api-key，靠飞书签名验真（见 channel-service FeishuInboundController）
            "/channel/feishu/events", FAMILY_CHANNEL_CALLBACK,
            // 钉钉机器人消息回调不带平台 api-key，靠钉钉 timestamp/sign 验真（见 channel-service DingtalkInboundController）
            "/channel/dingtalk/events", FAMILY_CHANNEL_CALLBACK);

    /** 免鉴权且**不限流**的探针/发现类前缀。 */
    private static final List<String> OPEN_INFRA_PREFIXES = List.of("/actuator", "/.well-known");

    /** 免鉴权且不限流的精确路径。 */
    private static final List<String> OPEN_INFRA_PATHS = List.of("/health");

    private EdgeOpenPaths() {}

    static boolean isOpen(String path) {
        if (path == null) {
            return false;
        }
        if (OPEN_BUSINESS_PATHS.containsKey(path) || OPEN_INFRA_PATHS.contains(path)) {
            return true;
        }
        return OPEN_INFRA_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * 免鉴权路径中<b>仍需限流</b>的业务入口 → 限流 family；返回 null 表示探针/发现类端点或非免鉴权路径
     * （后者走租户桶，不由本方法决定）。限额与 family 一起配在 {@code app.rate-limit.defaults}。
     */
    static String rateLimitFamilyOf(String path) {
        return path == null ? null : OPEN_BUSINESS_PATHS.get(path);
    }
}
