package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelCallbackRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回调载荷映射工具：把 async-task / workflow 等下游异步系统 POST 回来的裸 JSON 载荷，
 * 归一化为 {@link ChannelCallbackRequest}。从顶层字段、嵌套 {@code result} 及请求头中
 * 择优提取 sourceId、status、channel、target、message，并把原始载荷保留进 metadata。
 * 供 {@link ChannelController} 的 {@code /channel/callbacks/async-task}、{@code /channel/callbacks/workflow} 使用。
 */
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
