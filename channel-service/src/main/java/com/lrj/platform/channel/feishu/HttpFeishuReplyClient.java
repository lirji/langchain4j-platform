package com.lrj.platform.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 经飞书应用 API 给用户回消息（{@code im/v1/messages}）。需应用凭据（app-id/app-secret）。
 * {@code app.channel.feishu.reply.enabled=false}（默认）时 no-op——便于先只验证入站链路、后填凭据再开回复。
 *
 * <p>流程：{@code auth/v3/tenant_access_token/internal} 换 tenant_access_token（内存缓存至过期前）→
 * {@code Bearer} 调 {@code im/v1/messages?receive_id_type=open_id} 发 text。
 */
public class HttpFeishuReplyClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFeishuReplyClient.class);

    private final RestTemplate http;
    private final FeishuProperties.Reply cfg;
    private final ObjectMapper json;

    private volatile String cachedToken;
    private volatile long tokenExpiresAt; // epoch millis

    public HttpFeishuReplyClient(RestTemplate feishuReplyRestTemplate, FeishuProperties props, ObjectMapper json) {
        this.http = feishuReplyRestTemplate;
        this.cfg = props.getReply();
        this.json = json;
    }

    /** 给某 open_id 发 text 回复。凭据未配 / reply 未开时 no-op。 */
    public void replyText(String openId, String text) {
        if (!cfg.isEnabled() || cfg.getAppId().isBlank() || cfg.getAppSecret().isBlank()) {
            log.debug("feishu reply disabled or credentials missing; skip reply to {}", openId);
            return;
        }
        try {
            String token = tenantAccessToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            String content = json.writeValueAsString(Map.of("text", text == null ? "" : text));
            Map<String, Object> body = Map.of("receive_id", openId, "msg_type", "text", "content", content);
            http.postForEntity(cfg.getBaseUrl() + "/open-apis/im/v1/messages?receive_id_type=open_id",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            log.warn("feishu reply failed to {}: {}", openId, e.toString());
        }
    }

    private String tenantAccessToken() {
        long now = System.currentTimeMillis();
        String t = cachedToken;
        if (t != null && now < tokenExpiresAt) {
            return t;
        }
        Map<String, Object> req = Map.of("app_id", cfg.getAppId(), "app_secret", cfg.getAppSecret());
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = http.postForObject(
                cfg.getBaseUrl() + "/open-apis/auth/v3/tenant_access_token/internal", req, Map.class);
        if (resp == null || resp.get("tenant_access_token") == null) {
            throw new IllegalStateException("feishu tenant_access_token response missing token");
        }
        String token = resp.get("tenant_access_token").toString();
        long expireSec = resp.get("expire") instanceof Number n ? n.longValue() : 7200;
        cachedToken = token;
        tokenExpiresAt = now + Math.max(60, expireSec - 60) * 1000L; // 提前 60s 过期
        return token;
    }
}
