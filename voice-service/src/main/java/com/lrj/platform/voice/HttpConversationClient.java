package com.lrj.platform.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * HTTP 委托到 conversation-service（RestTemplate 带租户/trace 转发）。
 * <ul>
 *   <li>{@link #chat} → 一元 {@code POST /chat}，取响应 {@code reply}；</li>
 *   <li>{@link #chatStream} → 消费 {@code POST /chat/stream} 的 SSE 逐 token 回调，用于语音真 token 流式。</li>
 * </ul>
 * 用 RestTemplate 的 {@code execute} + 流式 {@code ResponseExtractor} 消费 SSE（而非 JDK HttpClient），
 * 以复用同一 RestTemplate 上已装的租户/trace 拦截器（免手搓内部 JWT）。失败抛出，由上层语音编排兜底。
 */
public class HttpConversationClient implements ConversationClient {

    private static final Logger log = LoggerFactory.getLogger(HttpConversationClient.class);

    private final RestTemplate conversationRestTemplate;
    private final ObjectMapper json = new ObjectMapper();

    public HttpConversationClient(RestTemplate conversationRestTemplate) {
        this.conversationRestTemplate = conversationRestTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String chat(String chatId, String message) {
        Map<String, Object> reply = conversationRestTemplate.postForObject(
                "/chat?chatId={chatId}", Map.of("message", message == null ? "" : message), Map.class, chatId);
        if (reply == null) {
            return "";
        }
        Object text = reply.get("reply");
        return text == null ? "" : text.toString();
    }

    @Override
    public void chatStream(String chatId, String message,
                           Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError) {
        String[] streamError = new String[1];
        try {
            conversationRestTemplate.execute(
                    "/chat/stream?chatId={chatId}",
                    HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        request.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                        json.writeValue(request.getBody(), Map.of("message", message == null ? "" : message));
                    },
                    response -> {
                        parseSse(response.getBody(), onToken, streamError);
                        return null;
                    },
                    chatId);
        } catch (Exception e) {
            onError.accept(e);
            return;
        }
        if (streamError[0] != null) {
            onError.accept(new RuntimeException(streamError[0]));
        } else {
            onDone.run();
        }
    }

    /**
     * 逐行解析 conversation {@code /chat/stream} 的 SSE：
     * 默认（无 name）{@code data:} 事件 = 一个 token；{@code event:blocked} 的拒答话术也念出来；
     * {@code event:error} 记录错误；{@code event:done}/{@code grounding-warning} 不产生 token。
     */
    private static void parseSse(InputStream body, Consumer<String> onToken, String[] streamError) throws IOException {
        if (body == null) {
            return;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        String eventName = null;
        StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) { // 空行 = 事件边界，分派
                dispatch(eventName, data.toString(), onToken, streamError);
                eventName = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                // Spring MVC SseEmitter 写 "data:" 后直接跟内容（不加空格），故不 strip 前导空格以保 token 原样。
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line, "data:".length(), line.length());
            }
            // 其它行（id: / retry: / 注释 ":") 忽略
        }
        // 末事件若无 trailing 空行（少见）：兜底分派
        if (eventName != null || data.length() > 0) {
            dispatch(eventName, data.toString(), onToken, streamError);
        }
    }

    private static void dispatch(String eventName, String data, Consumer<String> onToken, String[] streamError) {
        if (eventName == null || eventName.isEmpty()) {
            if (!data.isEmpty()) {
                onToken.accept(data); // 默认事件 = 一个 token
            }
            return;
        }
        switch (eventName) {
            case "blocked" -> {
                if (!data.isEmpty()) {
                    onToken.accept(data); // 拒答话术也念给用户
                }
            }
            case "error" -> streamError[0] = data.isEmpty() ? "stream error" : data;
            default -> { /* done / grounding-warning / 未知具名事件：不产生 token */ }
        }
    }
}
