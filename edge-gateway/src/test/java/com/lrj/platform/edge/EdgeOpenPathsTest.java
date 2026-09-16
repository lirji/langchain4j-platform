package com.lrj.platform.edge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 边缘免鉴权路径判定。关键 RBAC 断言：{@code /auth/register} 放行（用户尚无凭证），但
 * {@code /auth/admin/**} 与 {@code /auth/me} <b>不</b>放行——管理面必须经会话 Bearer/api-key 鉴权。
 */
class EdgeOpenPathsTest {

    @Test
    void loginAndRegistrationEntrypointsAreOpen() {
        assertThat(EdgeOpenPaths.isOpen("/auth/login")).isTrue();
        assertThat(EdgeOpenPaths.isOpen("/auth/register")).isTrue();
        assertThat(EdgeOpenPaths.isOpen("/auth/refresh")).isTrue();
        assertThat(EdgeOpenPaths.isOpen("/auth/logout")).isTrue();
    }

    @Test
    void adminAndMeRequireAuth() {
        assertThat(EdgeOpenPaths.isOpen("/auth/admin/users")).isFalse();
        assertThat(EdgeOpenPaths.isOpen("/auth/admin/roles")).isFalse();
        assertThat(EdgeOpenPaths.isOpen("/auth/me")).isFalse();
    }

    @Test
    void healthAndCallbacksAreOpen() {
        assertThat(EdgeOpenPaths.isOpen("/actuator/health")).isTrue();
        assertThat(EdgeOpenPaths.isOpen("/health")).isTrue();
        assertThat(EdgeOpenPaths.isOpen("/.well-known/agent.json")).isTrue();
        assertThat(EdgeOpenPaths.isOpen("/channel/feishu/events")).isTrue();
        assertThat(EdgeOpenPaths.isOpen("/channel/dingtalk/events")).isTrue();
    }

    @Test
    void businessPathsAndNullAreClosed() {
        assertThat(EdgeOpenPaths.isOpen("/chat")).isFalse();
        assertThat(EdgeOpenPaths.isOpen("/rag/query")).isFalse();
        assertThat(EdgeOpenPaths.isOpen("/auth/registered-lookalike")).isFalse();
        assertThat(EdgeOpenPaths.isOpen(null)).isFalse();
    }

    @Test
    void openBusinessEntrypointsStillGetARateLimitFamily() {
        // 免鉴权 ≠ 免限流：账号入口与渠道回调必须有 family，否则是公网上唯一无速率保护的面
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/auth/login")).isEqualTo(EdgeOpenPaths.FAMILY_AUTH);
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/auth/register")).isEqualTo(EdgeOpenPaths.FAMILY_AUTH);
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/auth/refresh")).isEqualTo(EdgeOpenPaths.FAMILY_AUTH);
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/auth/public-config")).isEqualTo(EdgeOpenPaths.FAMILY_AUTH);
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/channel/feishu/events"))
                .isEqualTo(EdgeOpenPaths.FAMILY_CHANNEL_CALLBACK);
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/channel/dingtalk/events"))
                .isEqualTo(EdgeOpenPaths.FAMILY_CHANNEL_CALLBACK);
    }

    @Test
    void probeEndpointsAndClosedPathsHaveNoOpenFamily() {
        // 探针被限流会误伤存活检查
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/actuator/health")).isNull();
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/health")).isNull();
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/.well-known/agent.json")).isNull();
        // 非 open 路径走租户桶，不该被误判成 open family
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/chat")).isNull();
        assertThat(EdgeOpenPaths.rateLimitFamilyOf("/channel/messages")).isNull();
        assertThat(EdgeOpenPaths.rateLimitFamilyOf(null)).isNull();
    }
}
