package com.lrj.platform.protocol.asynctask;

import java.util.Map;

/**
 * 创建通用异步任务的请求（{@code POST /async/tasks}）：指定任务类型 {@code kind}、输入 {@code input}
 * 与可选回调 {@code webhookUrl}；{@code taskId} 可选，留空由服务端生成。
 */
public record AsyncTaskCreateRequest(String taskId,
                                     String kind,
                                     Map<String, Object> input,
                                     String webhookUrl) {

    public AsyncTaskCreateRequest(String kind, Map<String, Object> input, String webhookUrl) {
        this(null, kind, input, webhookUrl);
    }

    public AsyncTaskCreateRequest {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
