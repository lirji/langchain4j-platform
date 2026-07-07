package com.lrj.platform.channel.feishu;

import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 飞书入站消息桥：把用户消息转成一次 {@code /chat} 并把回复发回飞书。
 *
 * <p>控制器需在 3s 内 ack 飞书，而 {@code /chat} 走 LLM 可能更久，故本桥<b>异步</b>处理，控制器解析后立即 ack。
 * 处理链：按 {@code messageId} 去重（飞书会重投）→ 设 {@link TenantContext}（飞书应用配置的租户）→
 * 调 conversation {@code /chat}（内部 JWT 由 RestTemplate 转发器铸发）→ 经 {@link HttpFeishuReplyClient} 回复。
 *
 * <p><b>骨架说明</b>：去重目前是进程内 best-effort；生产多副本需换 Redis/JDBC 去重。回复需飞书应用凭据，
 * {@code reply.enabled=false} 时只收不回，便于先验证入站链路。
 */
public class FeishuMessageBridge {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageBridge.class);

    private final HttpConversationClient conversation;
    private final HttpFeishuReplyClient reply;
    private final Executor executor;
    private final String tenantId;
    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();

    public FeishuMessageBridge(HttpConversationClient conversation, HttpFeishuReplyClient reply,
                               Executor feishuBridgeExecutor, FeishuProperties props) {
        this.conversation = conversation;
        this.reply = reply;
        this.executor = feishuBridgeExecutor;
        this.tenantId = props.getTenantId();
    }

    /** 异步处理一条入站消息（控制器已 ack）。 */
    public void handle(FeishuInboundMessage msg) {
        if (msg == null || msg.text() == null || msg.text().isBlank()) {
            return;
        }
        if (msg.messageId() != null && seen.putIfAbsent(msg.messageId(), Boolean.TRUE) != null) {
            log.debug("feishu message deduplicated messageId={}", msg.messageId());
            return;
        }
        executor.execute(() -> process(msg));
    }

    /** 同步处理（抽出便于单测：不经 executor 直接跑）。 */
    void process(FeishuInboundMessage msg) {
        TenantContext.Tenant prev = TenantContext.captureRaw();
        try {
            TenantContext.set(new TenantContext.Tenant(tenantId, "feishu:" + nz(msg.openId()), Set.of("chat")));
            String replyText = conversation.chat(msg.text(), nz(msg.openId()));
            if (replyText != null && !replyText.isBlank()) {
                reply.replyText(msg.openId(), replyText);
            }
        } catch (Exception e) {
            log.warn("feishu bridge process failed messageId={}: {}", msg.messageId(), e.toString());
        } finally {
            if (prev != null) TenantContext.set(prev); else TenantContext.clear();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
