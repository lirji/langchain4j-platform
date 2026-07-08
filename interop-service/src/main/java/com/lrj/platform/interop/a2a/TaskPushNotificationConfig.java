package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lrj.platform.interop.a2a.MessageSendParams.PushNotificationConfig;

/**
 * {@code tasks/pushNotificationConfig/set|get} 的载体：把一个 {@link PushNotificationConfig}
 * 绑定到某个 {@code taskId} 上（供 send 之后再登记 push 回调）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskPushNotificationConfig(String taskId, PushNotificationConfig pushNotificationConfig) {
}
