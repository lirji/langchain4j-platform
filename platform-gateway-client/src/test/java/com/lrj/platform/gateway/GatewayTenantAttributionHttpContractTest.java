package com.lrj.platform.gateway;

import com.lrj.platform.gateway.tenant.TenantAttributionMode;
import com.lrj.platform.gateway.tenant.TenantIdentityProvider;
import com.lrj.platform.gateway.tenant.TenantVirtualKeyResolver;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>customHeaders(Supplier) 契约测试（FINAL_PLAN §14 要求的第一优先验证）</strong>：
 * 用 JDK 内置 HttpServer 冒充 OpenAI 端点，从真实 HTTP 报文断言 ——
 *
 * <ol>
 *   <li>Supplier 每次发送求值（两次调用换租户 → Authorization 跟着换）；</li>
 *   <li>动态 Authorization <strong>覆盖</strong>静态 apiKey（有且仅有一个 Authorization 值，
 *       等于租户 virtual key，master key 不出现）；</li>
 *   <li>归因的 {@code user} 真实落到 JSON body。</li>
 * </ol>
 *
 * <p>若本测试失败（如 langchain4j 变更 header 合并语义为「追加」而非「覆盖」），按 FINAL_PLAN
 * §6 切方案 B（per-tenant 固定 delegate 池）回退路径，勿带病上线 virtual-key 档。
 */
class GatewayTenantAttributionHttpContractTest {

    private static final String MASTER_KEY = "sk-master-should-never-appear";

    record Captured(List<String> authorization, String body) { }

    /** body 是 pretty JSON（{@code "user" : "x"}）；按「键」断言，避免误匹配 {@code "role" : "user"} 的值。 */
    private static boolean hasTopLevelUser(String body, String tenantId) {
        return Pattern.compile("\"user\"\\s*:\\s*\"" + Pattern.quote(tenantId) + "\"").matcher(body).find();
    }

    private static boolean hasUserField(String body) {
        return Pattern.compile("\"user\"\\s*:").matcher(body).find();
    }

    private HttpServer server;
    private final ConcurrentLinkedQueue<Captured> captured = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(8)); // 并发契约测试需要服务端并行处理
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.add(new Captured(exchange.getRequestHeaders().get("Authorization"), body));
            byte[] response = ("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,"
                    + "\"model\":\"chat-default\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        currentTenant.remove();
    }

    private GatewayChatModelFactory factory(TenantAttributionMode mode) {
        GatewayClientProperties props = new GatewayClientProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        props.setApiKey(MASTER_KEY);
        props.setTenantAttribution(mode);
        TenantIdentityProvider identities = currentTenant::get;
        TenantVirtualKeyResolver keys = tenantId -> Optional.of("sk-vk-" + tenantId);
        GatewayRequestHeadersSupplier headers = new GatewayRequestHeadersSupplier(
                mode, identities, keys, null, null);
        return new GatewayChatModelFactory(props, List.of(), identities, headers);
    }

    @Test
    void virtualKey_overridesAuthorization_perRequest_andAttributesUserInBody() {
        ChatModel model = factory(TenantAttributionMode.VIRTUAL_KEY).build();

        currentTenant.set("tenant-a");
        model.chat("hello");
        currentTenant.set("tenant-b");
        model.chat("hello");

        assertThat(captured).hasSize(2);
        Captured first = captured.poll();
        Captured second = captured.poll();

        // ① 覆盖而非追加：有且仅有一个 Authorization 值；② master key 绝不触达网络
        assertThat(first.authorization()).containsExactly("Bearer sk-vk-tenant-a");
        assertThat(second.authorization()).containsExactly("Bearer sk-vk-tenant-b");
        assertThat(first.body() + second.body()).doesNotContain(MASTER_KEY);

        // ③ user 落 JSON body（LiteLLM end-user 记账依据）
        assertThat(hasTopLevelUser(first.body(), "tenant-a")).isTrue();
        assertThat(hasTopLevelUser(second.body(), "tenant-b")).isTrue();
    }

    @Test
    void user_keepsMasterKey_butAttributesUserInBody() {
        ChatModel model = factory(TenantAttributionMode.USER).build();

        currentTenant.set("tenant-u");
        model.chat("hello");

        Captured only = captured.poll();
        assertThat(only.authorization()).containsExactly("Bearer " + MASTER_KEY); // 共享 key 不变
        assertThat(hasTopLevelUser(only.body(), "tenant-u")).isTrue();
    }

    /**
     * 并发 Authorization 隔离（真实 HTTP）：多线程各持不同租户同时调用，逐请求断言
     * Authorization 里的 virtual key 与 body 里的 user <strong>配对一致</strong> —— 任何一次
     * 串号（A 的请求带了 B 的 key）都会被逐对校验抓住。
     */
    @Test
    void virtualKey_concurrentTenants_neverCrossAuthorization() throws Exception {
        ChatModel model = factory(TenantAttributionMode.VIRTUAL_KEY).build();

        int tasks = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            String tenant = "ct-" + i;
            pool.submit(() -> {
                try {
                    currentTenant.set(tenant);
                    model.chat("hello-" + tenant);
                } finally {
                    currentTenant.remove();
                    done.countDown();
                }
            });
        }
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(captured).hasSize(tasks);
        Pattern userPattern = Pattern.compile("\"user\"\\s*:\\s*\"(ct-\\d+)\"");
        for (Captured c : captured) {
            Matcher m = userPattern.matcher(c.body());
            assertThat(m.find()).as("body 应含归因 user").isTrue();
            // 逐请求配对：该请求的 Authorization 必须正是其 body user 对应租户的 key
            assertThat(c.authorization()).containsExactly("Bearer sk-vk-" + m.group(1));
        }
        captured.clear();
    }

    @Test
    void none_behavesExactlyAsBefore() {
        ChatModel model = factory(TenantAttributionMode.NONE).build();

        currentTenant.set("tenant-x");
        model.chat("hello");

        Captured only = captured.poll();
        assertThat(only.authorization()).containsExactly("Bearer " + MASTER_KEY);
        assertThat(hasUserField(only.body())).isFalse(); // 不注入 user 键（"role":"user" 是消息角色值，不算）
    }
}
