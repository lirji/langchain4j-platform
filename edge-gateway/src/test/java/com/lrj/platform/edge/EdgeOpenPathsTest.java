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
}
