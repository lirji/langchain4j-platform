package com.lrj.platform.knowledge.ingest.job;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDocumentSourceStoreTest {

    @Test
    void storesImmutableTenantScopedSource() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        InMemoryDocumentSourceStore store = new InMemoryDocumentSourceStore();
        DocumentSourceRef source = store.put(new DocumentSourceStore.PutSource(
                "acme", "doc-1", 1, "text/plain", content.length, "sha256:abc",
                new ByteArrayInputStream(content)));

        assertThat(store.open("acme", source).readAllBytes()).isEqualTo(content);
        assertThatThrownBy(() -> store.open("globex", source))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void rejectsUnsafeTenantAndSizeMismatch() {
        InMemoryDocumentSourceStore store = new InMemoryDocumentSourceStore();

        assertThatThrownBy(() -> store.put(new DocumentSourceStore.PutSource(
                "../acme", "doc", 1, "text/plain", 1, "sha256:abc",
                new ByteArrayInputStream(new byte[]{1}))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put(new DocumentSourceStore.PutSource(
                "acme", "doc", 1, "text/plain", 2, "sha256:abc",
                new ByteArrayInputStream(new byte[]{1}))))
                .isInstanceOf(java.io.IOException.class);
    }
}
