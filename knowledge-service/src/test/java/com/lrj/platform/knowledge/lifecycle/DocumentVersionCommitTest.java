package com.lrj.platform.knowledge.lifecycle;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

/** 可见版本只按顺序前进；并发/旧任务重放不能覆盖新元数据。 */
class DocumentVersionCommitTest {
    @Test
    void versionCommitIsMonotonicAndIdempotent() {
        var registry = new InMemoryDocumentRegistry();
        var first = info(1, "first");
        var second = info(2, "second");
        assertThat(registry.commitVersion(second)).isFalse();
        assertThat(registry.commitVersion(first)).isTrue();
        assertThat(registry.commitVersion(info(1, "late-first"))).isTrue();
        assertThat(registry.get("acme", "doc")).contains(first);
        assertThat(registry.commitVersion(second)).isTrue();
        assertThat(registry.commitVersion(first)).isFalse();
        assertThat(registry.get("acme", "doc")).contains(second);
        assertThat(registry.get("other", "doc")).isEmpty();
    }

    private DocumentInfo info(int version, String name) {
        return new DocumentInfo("doc", "acme", name, "text/plain", 5, 1, version,
                Instant.parse("2026-09-08T00:00:00Z"), null);
    }
}
