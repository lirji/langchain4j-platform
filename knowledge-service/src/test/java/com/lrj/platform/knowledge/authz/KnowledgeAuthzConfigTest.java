package com.lrj.platform.knowledge.authz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link KnowledgeAuthzConfig#validateStrictConsistency} 跨配置启动校验单测。
 * strict-tenant-only=true 时与 public.enabled / mode=disabled 互斥。
 */
class KnowledgeAuthzConfigTest {

    private static RagAuthzProperties props(boolean strict, AuthzMode mode) {
        RagAuthzProperties p = new RagAuthzProperties();
        p.setStrictTenantOnly(strict);
        p.setMode(mode);
        return p;
    }

    @Test
    void nonStrict_neverThrows_regardlessOfPublicOrMode() {
        assertDoesNotThrow(() -> KnowledgeAuthzConfig.validateStrictConsistency(props(false, AuthzMode.DISABLED), true));
        assertDoesNotThrow(() -> KnowledgeAuthzConfig.validateStrictConsistency(props(false, AuthzMode.ENFORCE), true));
    }

    @Test
    void strict_withPublicEnabled_throws() {
        assertThrows(IllegalStateException.class,
                () -> KnowledgeAuthzConfig.validateStrictConsistency(props(true, AuthzMode.ENFORCE), true));
    }

    @Test
    void strict_withModeDisabled_throws() {
        assertThrows(IllegalStateException.class,
                () -> KnowledgeAuthzConfig.validateStrictConsistency(props(true, AuthzMode.DISABLED), false));
    }

    @Test
    void strict_publicOff_enforce_ok() {
        assertDoesNotThrow(() -> KnowledgeAuthzConfig.validateStrictConsistency(props(true, AuthzMode.ENFORCE), false));
        assertDoesNotThrow(() -> KnowledgeAuthzConfig.validateStrictConsistency(props(true, AuthzMode.SHADOW), false));
    }
}
