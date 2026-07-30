package com.lrj.platform.knowledge.ingest.job;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class S3DocumentSourceStoreTest {

    private final S3Client client = mock(S3Client.class);
    private final S3DocumentSourceStore store =
            new S3DocumentSourceStore(client, "knowledge-sources", "/documents/");

    @Test
    void putsSourceUnderTenantAndVersionPrefixWithImmutableMetadata() throws Exception {
        byte[] content = "source".getBytes(StandardCharsets.UTF_8);

        DocumentSourceRef ref = store.put(new DocumentSourceStore.PutSource(
                "tenant-a",
                "document-1",
                2,
                "text/plain",
                content.length,
                "sha256:abc",
                new ByteArrayInputStream(content)));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(
                request.capture(),
                any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("knowledge-sources");
        assertThat(request.getValue().key())
                .isEqualTo("documents/tenant-a/document-1/v2/sha256-abc/source");
        assertThat(request.getValue().metadata())
                .containsEntry("tenant-id", "tenant-a")
                .containsEntry("content-hash", "sha256:abc");
        assertThat(ref.objectKey()).isEqualTo(request.getValue().key());
    }

    @Test
    void rejectsCrossTenantAndUnsafeTenantBeforeCallingS3() {
        DocumentSourceRef ref = new DocumentSourceRef(
                "knowledge-sources",
                "documents/tenant-a/document-1/v1/source",
                "sha256:abc",
                "text/plain",
                6);

        assertThatThrownBy(() -> store.delete("tenant-b", ref))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.delete("../tenant-a", ref))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).deleteObject(
                any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }
}
