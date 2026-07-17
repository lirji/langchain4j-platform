package com.lrj.platform.gateway;

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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatewayChatModelFactory#buildJsonMode()} 契约测试：用 JDK 内置 HttpServer 冒充 OpenAI 端点，
 * 从真实 HTTP 报文断言 {@code response_format=json_object} 真实落到请求 body ——
 * ReAct 决策核心依赖它从模型侧强制合法 JSON（字段值裸英文引号曾打断 AgentDecision 解析）。
 * 同时断言默认 {@link GatewayChatModelFactory#build()} 不带 response_format，行为与历史一致。
 */
class GatewayJsonModeHttpContractTest {

    /** body 是 pretty JSON；容忍任意空白，匹配 {@code "response_format": {"type": "json_object"}}。 */
    private static final Pattern JSON_OBJECT_FORMAT = Pattern.compile(
            "\"response_format\"\\s*:\\s*\\{\\s*\"type\"\\s*:\\s*\"json_object\"");

    private HttpServer server;
    private final ConcurrentLinkedQueue<String> capturedBodies = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,"
                    + "\"model\":\"chat-default\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"{}\"},\"finish_reason\":\"stop\"}],"
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
    }

    private GatewayChatModelFactory factory() {
        GatewayClientProperties props = new GatewayClientProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        props.setApiKey("sk-test");
        return new GatewayChatModelFactory(props, List.of());
    }

    @Test
    void jsonMode_sendsResponseFormatJsonObject() {
        ChatModel model = factory().buildJsonMode();

        model.chat("hello");

        String body = capturedBodies.poll();
        assertThat(body).isNotNull();
        assertThat(JSON_OBJECT_FORMAT.matcher(body).find())
                .as("json mode 请求 body 应含 response_format=json_object，实际: %s", body)
                .isTrue();
    }

    @Test
    void defaultBuild_doesNotSendResponseFormat() {
        ChatModel model = factory().build();

        model.chat("hello");

        String body = capturedBodies.poll();
        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("\"response_format\"");
    }
}
