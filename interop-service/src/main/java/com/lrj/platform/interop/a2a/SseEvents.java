package com.lrj.platform.interop.a2a;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 极简 SSE 逐行解析器：读一个 {@code text/event-stream} 响应体，按空行分帧，
 * 每帧回调 {@code (eventName, data)}（无 {@code event:} 行时 eventName 为 {@code null}）。
 *
 * <p>移植自 voice-service {@code HttpConversationClient.parseSse} 的解析逻辑并抽成共享工具，
 * 供 conversation token 流与 agent 任务流两个网关复用。多行 {@code data:} 以 {@code \n} 拼接。
 */
final class SseEvents {

    private SseEvents() {
    }

    interface Handler {
        /** eventName 为 SSE {@code event:} 行的值；无该行时为 {@code null}（默认事件）。 */
        void on(String eventName, String data);
    }

    static void read(InputStream body, Handler handler) throws IOException {
        if (body == null) {
            return;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        String eventName = null;
        StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) { // 空行 = 事件边界，分派
                if (eventName != null || data.length() > 0) {
                    handler.on(eventName, data.toString());
                }
                eventName = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                // Spring MVC SseEmitter 写 "data:" 后直接跟内容（不加空格），故不 strip 前导空格以保原样。
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line, "data:".length(), line.length());
            }
            // 其它行（id: / retry: / 注释 ":") 忽略
        }
        // 末事件若无 trailing 空行（少见）：兜底分派
        if (eventName != null || data.length() > 0) {
            handler.on(eventName, data.toString());
        }
    }
}
