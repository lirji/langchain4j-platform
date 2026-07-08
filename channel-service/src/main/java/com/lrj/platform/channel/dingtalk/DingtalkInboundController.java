package com.lrj.platform.channel.dingtalk;

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
 * 钉钉机器人消息回调入口 {@code POST /channel/dingtalk/events}。仅 {@code app.channel.dingtalk.enabled=true} 时装配。
 *
 * <p>该端点<b>不带平台 api-key</b>（钉钉不知道），需在 edge-gateway 免鉴权放行、靠钉钉 timestamp/sign 验真。
 * 处理：验签 → 解析群内 @机器人 的 text 消息 → 交 {@link DingtalkMessageBridge} 异步处理并立即 ack
 * （钉钉要求 3s 内响应）。非 text 消息忽略。对钉钉恒 ack，避免验签外的异常触发重投风暴。
 */
@RestController
@ConditionalOnProperty(prefix = "app.channel.dingtalk", name = "enabled", havingValue = "true")
public class DingtalkInboundController {

    private static final Logger log = LoggerFactory.getLogger(DingtalkInboundController.class);
    private static final Map<String, Object> ACK = Map.of();

    private final DingtalkProperties props;
    private final DingtalkEventCrypto crypto;
    private final DingtalkMessageBridge bridge;
    private final ObjectMapper json;

    public DingtalkInboundController(DingtalkProperties props, DingtalkEventCrypto crypto,
                                     DingtalkMessageBridge bridge, ObjectMapper json) {
        this.props = props;
        this.crypto = crypto;
        this.bridge = bridge;
        this.json = json;
    }

    @PostMapping("/channel/dingtalk/events")
    public ResponseEntity<?> onEvent(
            @RequestBody String rawBody,
            @RequestHeader(value = "timestamp", required = false) String timestamp,
            @RequestHeader(value = "sign", required = false) String sign) {
        // 验签在最外层：签名不对直接 401，不进入解析/桥接
        if (props.isVerifySignature() && crypto.hasSecret()
                && !crypto.signatureValid(timestamp, sign)) {
            log.warn("dingtalk callback signature invalid");
            return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
        }
        try {
            DingtalkInboundMessage msg = parseMessage(json.readTree(rawBody));
            if (msg != null) {
                bridge.handle(msg);
            }
            return ResponseEntity.ok(ACK);
        } catch (Exception e) {
            log.warn("dingtalk event handling failed: {}", e.toString());
            return ResponseEntity.ok(ACK); // 已验签，解析/处理异常只记日志，对钉钉恒 ack
        }
    }

    /** 解析群内 @机器人 的 text 消息为规范化入站消息；非 text 返回 null。 */
    DingtalkInboundMessage parseMessage(JsonNode root) {
        if (!"text".equals(text(root, "msgtype"))) {
            return null; // 骨架先只处理 text；富文本/图片后续扩展
        }
        String content = text(root.path("text"), "content");
        if (content == null || content.isBlank()) {
            return null;
        }
        String msgId = text(root, "msgId");
        String conversationId = text(root, "conversationId");
        String senderStaffId = text(root, "senderStaffId");
        String senderNick = text(root, "senderNick");
        return new DingtalkInboundMessage(msgId, conversationId, senderStaffId, senderNick, content.trim());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
