package com.lrj.platform.edge;

/**
 * 边缘免鉴权（open）路径的单一判定源，供 {@link SessionBearerAuthFilter}、
 * {@link ApiKeyToInternalTokenFilter}、{@link EdgeRateLimitFilter} 共用，避免各处 isOpen 漂移。
 *
 * <p>放行的都是"天然无平台凭证"的入口：健康检查/发现类、第三方签名验真的回调、以及登录本身
 * （{@code /auth/login|refresh|logout}——用户此刻还没有 Bearer；{@code /auth/me} 仍需 Bearer，不在此列）。
 */
final class EdgeOpenPaths {

    private EdgeOpenPaths() {}

    static boolean isOpen(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/actuator")
                || path.startsWith("/.well-known")
                || path.equals("/health")
                // 登录/注册/刷新/登出：用户尚无会话令牌，凭 cookie 或账号密码，不经边缘鉴权。
                || path.equals("/auth/login")
                || path.equals("/auth/register")
                || path.equals("/auth/refresh")
                || path.equals("/auth/logout")
                // 公开最小配置（注册开关/密码长度）：未登录前端渲染登录/注册页前拉取，非敏感。
                || path.equals("/auth/public-config")
                // 飞书事件回调不带平台 api-key，靠飞书签名验真（见 channel-service FeishuInboundController）
                || path.equals("/channel/feishu/events")
                // 钉钉机器人消息回调不带平台 api-key，靠钉钉 timestamp/sign 验真（见 channel-service DingtalkInboundController）
                || path.equals("/channel/dingtalk/events");
    }
}
