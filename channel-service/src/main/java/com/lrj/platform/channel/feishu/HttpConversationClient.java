package com.lrj.platform.channel.feishu;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 桥 → conversation-service 的 {@code /chat} 客户端。用带 {@code OutboundTenantForwarder}
 * （从 {@code TenantContext} 铸发内部 JWT）+ {@code OutboundTraceForwarder} 的 RestTemplate，
 * 故调用前需先在当前线程 set 好 {@code TenantContext}（桥用飞书应用配置的租户）。
 */
public class HttpConversationClient {

    private final RestTemplate restTemplate;

    public HttpConversationClient(RestTemplate feishuConversationRestTemplate) {
        this.restTemplate = feishuConversationRestTemplate;
    }

    /** 调 /chat 拿回复。chatId 用于会话记忆隔离（这里用飞书 open_id）。 */
    public String chat(String message, String chatId) {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/chat?chatId={chatId}", Map.of("message", message == null ? "" : message), Map.class, chatId);
        Object reply = resp.getBody() == null ? null : resp.getBody().get("reply");
        return reply == null ? "" : reply.toString();
    }
}
