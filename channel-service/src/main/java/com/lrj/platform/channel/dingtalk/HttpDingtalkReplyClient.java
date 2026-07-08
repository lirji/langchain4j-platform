package com.lrj.platform.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 经钉钉「机器人发消息 API」给群回消息。需应用凭据（app-key/app-secret）。凭据未配时 no-op——
 * 便于先只验证入站链路、后填凭据再开回复。
 *
 * <p>流程（token 缓存写法对齐 {@code HttpFeishuReplyClient}）：
 * {@code POST /v1.0/oauth2/accessToken}（{appKey,appSecret}）换 accessToken（内存缓存至过期前 60s）→
 * 带 {@code x-acs-dingtalk-access-token} 调 {@code POST /v1.0/robot/groupMessages/send} 发消息。
 * {@code msgParam} 是 JSON<b>字符串</b>（非对象），text 用 {@code sampleText}、@人 用 {@code sampleMarkdown}。
 *
 * <p><b>注意</b>：各 {@code msgKey} 的 {@code msgParam} 字段与 markdown 的 @ 语法在钉钉 API 版本间可能调整，
 * 以钉钉最新文档为准；改动集中在本类一处。
 */
public class HttpDingtalkReplyClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDingtalkReplyClient.class);
    private static final String ACCESS_TOKEN_HEADER = "x-acs-dingtalk-access-token";

    private final RestTemplate http;
    private final DingtalkProperties props;
    private final ObjectMapper json;

    private volatile String cachedToken;
    private volatile long tokenExpiresAt; // epoch millis

    public HttpDingtalkReplyClient(RestTemplate dingtalkReplyRestTemplate, DingtalkProperties props, ObjectMapper json) {
        this.http = dingtalkReplyRestTemplate;
        this.props = props;
        this.json = json;
    }

    /** 给某群发 text 回复。凭据未配时 no-op。 */
    public void replyText(String openConversationId, String text) {
        if (credentialsMissing()) {
            log.debug("dingtalk reply disabled or credentials missing; skip reply to {}", openConversationId);
            return;
        }
        try {
            String msgParam = json.writeValueAsString(Map.of("content", nz(text)));
            send(openConversationId, "sampleText", msgParam);
        } catch (Exception e) {
            log.warn("dingtalk reply failed to {}: {}", openConversationId, e.toString());
        }
    }

    /** 转人工：给某群发 markdown 话术并 @ 人工客服（userIds 为空则只发话术）。凭据未配时 no-op。 */
    public void replyAtUsers(String openConversationId, String text, List<String> userIds) {
        if (credentialsMissing()) {
            log.debug("dingtalk reply disabled or credentials missing; skip handoff to {}", openConversationId);
            return;
        }
        try {
            StringBuilder md = new StringBuilder(nz(text));
            if (userIds != null && !userIds.isEmpty()) {
                md.append("\n\n");
                for (String uid : userIds) {
                    if (uid != null && !uid.isBlank()) {
                        md.append("@").append(uid.trim()).append(' ');
                    }
                }
            }
            String msgParam = json.writeValueAsString(Map.of("title", "转人工", "text", md.toString()));
            send(openConversationId, "sampleMarkdown", msgParam);
        } catch (Exception e) {
            log.warn("dingtalk handoff reply failed to {}: {}", openConversationId, e.toString());
        }
    }

    private void send(String openConversationId, String msgKey, String msgParam) {
        String token = accessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(ACCESS_TOKEN_HEADER, token);
        Map<String, Object> body = Map.of(
                "robotCode", props.getAppKey(),
                "openConversationId", nz(openConversationId),
                "msgKey", msgKey,
                "msgParam", msgParam);
        http.postForEntity(props.getApiBaseUrl() + "/v1.0/robot/groupMessages/send",
                new HttpEntity<>(body, headers), Map.class);
    }

    private boolean credentialsMissing() {
        return props.getAppKey().isBlank() || props.getAppSecret().isBlank();
    }

    private String accessToken() {
        long now = System.currentTimeMillis();
        String t = cachedToken;
        if (t != null && now < tokenExpiresAt) {
            return t;
        }
        Map<String, Object> req = Map.of("appKey", props.getAppKey(), "appSecret", props.getAppSecret());
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = http.postForObject(
                props.getApiBaseUrl() + "/v1.0/oauth2/accessToken", req, Map.class);
        if (resp == null || resp.get("accessToken") == null) {
            throw new IllegalStateException("dingtalk accessToken response missing token");
        }
        String token = resp.get("accessToken").toString();
        long expireSec = resp.get("expireIn") instanceof Number n ? n.longValue() : 7200;
        cachedToken = token;
        tokenExpiresAt = now + Math.max(60, expireSec - 60) * 1000L; // 提前 60s 过期
        return token;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
