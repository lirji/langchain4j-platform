package com.lrj.platform.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 vendored 的 agentscope-platform 契约副本没有被本仓私自改动。
 *
 * <p>上游 {@code agentscope-platform} 是这些契约的唯一权威，本仓只保存消费侧副本并固定
 * sha256。「副本 == 上游」由 {@code deploy/sync-agent-contracts.sh} 在能看到兄弟仓时校验；
 * 本测试负责「副本 == manifest 固定的 digest」，因此即使 CI 拿不到上游仓，本地误改仍会被拦住。
 */
class AgentScopeContractManifestTest {

    private static final Path VENDOR_DIR = Path.of("src/main/resources/contracts/agentscope");
    private static final Path MANIFEST = VENDOR_DIR.resolve("manifest.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void manifest_pins_the_digest_of_every_vendored_contract() throws IOException {
        Map<String, String> pinned = pinnedDigests();

        assertThat(pinned).isNotEmpty();
        for (Map.Entry<String, String> entry : pinned.entrySet()) {
            Path contract = VENDOR_DIR.resolve(entry.getKey());
            assertThat(contract).as("vendored contract %s", entry.getKey()).isRegularFile();
            assertThat(sha256(contract))
                    .as("digest of %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void manifest_covers_exactly_the_vendored_files_on_disk() throws IOException {
        // 多复制一个没人消费的 schema、或删掉副本却忘了改 manifest，都会在这里暴露。
        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> files = Files.walk(VENDOR_DIR)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.equals(MANIFEST))
                    .forEach(path -> onDisk.add(
                            VENDOR_DIR.relativize(path).toString().replace('\\', '/')));
        }

        assertThat(onDisk).isEqualTo(new TreeSet<>(pinnedDigests().keySet()));
    }

    @Test
    void manifest_declares_the_supported_upstream_format() throws IOException {
        JsonNode upstream = MAPPER.readTree(Files.readString(MANIFEST)).get("upstream");

        assertThat(upstream.get("repository").asText()).isEqualTo("agentscope-platform");
        assertThat(upstream.get("manifest").asText()).isEqualTo("contracts/manifest.json");
        assertThat(upstream.get("revision").asText()).matches("[0-9a-f]{40}");
        assertThat(upstream.get("manifest_digest").asText()).matches("sha256:[0-9a-f]{64}");
        // 上游 manifest 换代（例如改 digest 算法）必须显式处理，不能静默按老格式解读。
        assertThat(upstream.get("schema_version").asText()).isEqualTo("1");
    }

    private static Map<String, String> pinnedDigests() throws IOException {
        JsonNode files = MAPPER.readTree(Files.readString(MANIFEST)).get("files");
        Map<String, String> pinned = new TreeMap<>();
        files.fields().forEachRemaining(field -> pinned.put(field.getKey(), field.getValue().asText()));
        return pinned;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }
}
