package com.lrj.platform.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 飞书事件回调入口 {@code POST /channel/feishu/events}。仅 {@code app.channel.feishu.enabled=true} 时装配。
 *
 * <p>该端点<b>不带平台 api-key</b>（飞书不知道），需在 edge-gateway 免鉴权放行、靠飞书签名 + verification token 验真。
 * 处理：URL 验证 challenge → 原样返回 {@code challenge}；加密事件 → 验签 + AES 解密；消息事件 → 交
 * {@link FeishuMessageBridge} 异步处理并立即 ack（飞书要求 3s 内响应）。
 */
@RestController
@ConditionalOnProperty(prefix = "app.channel.feishu", name = "enabled", havingValue = "true")
public class FeishuInboundController {

    private static final Logger log = LoggerFactory.getLogger(FeishuInboundController.class);
    private static final Map<String, Object> ACK = Map.of();

    private final FeishuProperties props;
    private final FeishuEventCrypto crypto;
    private final FeishuMessageBridge bridge;
    private final ObjectMapper json;

    public FeishuInboundController(FeishuProperties props, FeishuEventCrypto crypto,
                                   FeishuMessageBridge bridge, ObjectMapper json) {
        this.props = props;
        this.crypto = crypto;
        this.bridge = bridge;
        this.json = json;
    }

    @PostMapping("/channel/feishu/events")
    public ResponseEntity<?> onEvent(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce) {
        try {
            JsonNode root = json.readTree(rawBody);

            // 加密事件：{"encrypt":"..."} → 验签 + 解密
            if (root.hasNonNull("encrypt") && crypto.hasEncryptKey()) {
                if (props.isVerifySignature()
                        && !crypto.signatureValid(timestamp, nonce, rawBody, signature)) {
                    log.warn("feishu event signature invalid");
                    return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
                }
                root = json.readTree(crypto.decrypt(root.get("encrypt").asText()));
            }

            if (!verificationTokenOk(root)) {
                log.warn("feishu event verification token mismatch");
                return ResponseEntity.status(401).body(Map.of("error", "invalid verification token"));
            }

            // URL 验证：原样回 challenge
            if ("url_verification".equals(text(root, "type"))) {
                return ResponseEntity.ok(Map.of("challenge", text(root, "challenge")));
            }

            // 消息事件：解析 → 桥异步处理 → 立即 ack
            FeishuInboundMessage msg = parseMessage(root);
            if (msg != null) {
                bridge.handle(msg);
            }
            return ResponseEntity.ok(ACK);
        } catch (Exception e) {
            log.warn("feishu event handling failed: {}", e.toString());
            return ResponseEntity.ok(ACK); // 对飞书恒 ack，避免重投风暴；错误已记日志
        }
    }

    /** 解析 im.message.receive_v1 的 text 消息为规范化入站消息；非该类型返回 null。 */
    FeishuInboundMessage parseMessage(JsonNode root) throws Exception {
        JsonNode header = root.path("header");
        if (!"im.message.receive_v1".equals(text(header, "event_type"))) {
            return null;
        }
        JsonNode message = root.path("event").path("message");
        if (!"text".equals(text(message, "message_type"))) {
            return null; // 骨架先只处理 text；富文本/图片后续扩展
        }
        String openId = text(root.path("event").path("sender").path("sender_id"), "open_id");
        String messageId = text(message, "message_id");
        String chatId = text(message, "chat_id");
        String content = text(message, "content"); // content 是 JSON 串 {"text":"..."}
        String textValue = content == null ? null : text(json.readTree(content), "text");
        if (textValue == null || textValue.isBlank()) {
            return null;
        }
        return new FeishuInboundMessage(messageId, openId, chatId, textValue.trim());
    }

    private boolean verificationTokenOk(JsonNode root) {
        String configured = props.getVerificationToken();
        if (configured == null || configured.isBlank()) {
            return true; // 未配则不校验（骨架/开发态）
        }
        String token = text(root.path("header"), "token"); // v2 在 header.token
        if (token == null) {
            token = text(root, "token"); // v1 兼容
        }
        return configured.equals(token);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
