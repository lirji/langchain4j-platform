package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelCallbackRequest;

import java.util.LinkedHashMap;
import java.util.Map;

final class ChannelCallbackMapper {

    private ChannelCallbackMapper() {
    }

    static ChannelCallbackRequest fromPayload(String source,
                                              Map<String, Object> payload,
                                              String idHeader,
                                              String statusHeader) {
        Map<String, Object> body = payload == null ? Map.of() : payload;
        Map<String, Object> result = map(body.get("result"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", source);
        metadata.put("payload", body);
        String sourceId = firstString(idHeader, body.get("taskId"), body.get("instanceId"), result.get("taskId"), result.get("instanceId"));
        String status = firstString(statusHeader, body.get("status"), result.get("status"));
        if (sourceId != null) {
            metadata.put("sourceId", sourceId);
        }
        if (status != null) {
            metadata.put("status", status);
        }
        return new ChannelCallbackRequest(
                source,
                sourceId,
                status,
                firstString(body.get("channel"), result.get("channel"), map(body.get("metadata")).get("channel")),
                firstString(body.get("target"), result.get("target"), map(body.get("metadata")).get("target")),
                firstString(body.get("message"), body.get("text"), result.get("message"), result.get("reply"), result.get("answer")),
                metadata);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> copy = new LinkedHashMap<>();
            raw.forEach((key, item) -> {
                if (key instanceof String name) {
                    copy.put(name, item);
                }
            });
            return copy;
        }
        return Map.of();
    }

    private static String firstString(Object... values) {
        for (Object value : values) {
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }
}
