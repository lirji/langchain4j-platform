package com.lrj.platform.knowledge.authz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RagAuthzProperties} 自校验单测：默认合法、非法上限启动期抛出（不静默退化）。
 * 纯单元，不起 Spring 上下文。
 */
class RagAuthzPropertiesTest {

    private static RagAuthzProperties props(int candidateMultiplier, int maxCandidates, int bulkSize) {
        RagAuthzProperties p = new RagAuthzProperties();
        p.setCandidateMultiplier(candidateMultiplier);
        p.setMaxCandidates(maxCandidates);
        p.setBulkSize(bulkSize);
        return p;
    }

    @Test
    void defaultsAreValidAndDisabled() {
        RagAuthzProperties p = new RagAuthzProperties();
        assertEquals(AuthzMode.DISABLED, p.getMode());
        assertEquals(3, p.getCandidateMultiplier());
        assertEquals(200, p.getMaxCandidates());
        assertEquals(100, p.getBulkSize());
        assertDoesNotThrow(p::afterPropertiesSet);
    }

    @Test
    void rejectsCandidateMultiplierBelowOne() {
        assertThrows(IllegalStateException.class, () -> props(0, 200, 100).afterPropertiesSet());
    }

    @Test
    void rejectsMaxCandidatesBelowOne() {
        assertThrows(IllegalStateException.class, () -> props(3, 0, 1).afterPropertiesSet());
    }

    @Test
    void rejectsBulkSizeBelowOne() {
        assertThrows(IllegalStateException.class, () -> props(3, 200, 0).afterPropertiesSet());
    }

    @Test
    void rejectsBulkSizeAboveMaxCandidates() {
        assertThrows(IllegalStateException.class, () -> props(3, 100, 101).afterPropertiesSet());
    }

    @Test
    void acceptsBulkSizeEqualMaxCandidates() {
        assertDoesNotThrow(() -> props(3, 100, 100).afterPropertiesSet());
    }
}
