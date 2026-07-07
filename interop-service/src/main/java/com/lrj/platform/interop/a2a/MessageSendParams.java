package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * {@code message/send} 的 params。{@code configuration.pushNotificationConfig.url} 给异步任务登记
 * webhook —— 直接透传给 agent-service 作为异步任务完成回调地址。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageSendParams(A2aMessage message, Configuration configuration, Map<String, Object> metadata) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Configuration(PushNotificationConfig pushNotificationConfig,
                                Boolean blocking,
                                List<String> acceptedOutputModes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PushNotificationConfig(String url, String token, String id) {
    }
}
