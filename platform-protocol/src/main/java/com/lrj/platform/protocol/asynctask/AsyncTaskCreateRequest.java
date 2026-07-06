package com.lrj.platform.protocol.asynctask;

import java.util.Map;

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
