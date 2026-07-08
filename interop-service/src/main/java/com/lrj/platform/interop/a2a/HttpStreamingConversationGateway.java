package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link StreamingConversationGateway} 的 HTTP 实现。用 RestTemplate 的 {@code execute} + 流式
 * {@code ResponseExtractor} 消费 conversation {@code /chat/stream} 的 SSE（复用同一 RestTemplate 上已装的
 * 租户/trace 拦截器，免手搓内部 JWT）。解析范式与 voice-service {@code HttpConversationClient.chatStream} 一致：
 * 默认（无名）{@code data:} 事件 = 一个 token；{@code event:blocked} 的拒答话术也当 token 念出；
 * {@code event:error} 记为错误；{@code event:done}/{@code grounding-warning} 不产生 token。
 */
@Component
public class HttpStreamingConversationGateway implements StreamingConversationGateway {

    private final RestTemplate interopConversationRestTemplate;
    private final ObjectMapper json;

    public HttpStreamingConversationGateway(RestTemplate interopConversationRestTemplate, ObjectMapper json) {
        this.interopConversationRestTemplate = interopConversationRestTemplate;
        this.json = json;
    }

    @Override
    public void streamChat(String chatId, String message,
                           Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError) {
        String[] streamError = new String[1];
        try {
            interopConversationRestTemplate.execute(
                    "/chat/stream?chatId={chatId}",
                    HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        request.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                        json.writeValue(request.getBody(), Map.of("message", message == null ? "" : message));
                    },
                    response -> {
                        SseEvents.read(response.getBody(), (eventName, data) ->
                                dispatch(eventName, data, onToken, streamError));
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
                    onToken.accept(data); // 拒答话术也念给客户端
                }
            }
            case "error" -> streamError[0] = data.isEmpty() ? "stream error" : data;
            default -> { /* done / grounding-warning / 未知具名事件：不产生 token */ }
        }
    }
}
