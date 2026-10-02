package com.lrj.platform.channel.feishu;

import com.lrj.platform.channel.inbox.InboundInbox;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 飞书入站消息桥：把用户消息转成一次 {@code /chat} 并把回复发回飞书。
 *
 * <p>控制器需在 3s 内 ack 飞书，而 {@code /chat} 走 LLM 可能更久，故本桥<b>异步</b>处理，控制器解析后立即 ack。
 * 处理链：按 {@code messageId} 去重（飞书会重投）→ 设 {@link TenantContext}（飞书应用配置的租户）→
 * 调 conversation {@code /chat}（内部 JWT 由 RestTemplate 转发器铸发）→ 经 {@link HttpFeishuReplyClient} 回复。
 *
 * <p>ACK 前提交到渠道 inbox；后台领取与失败重试由租约驱动，进程退出后可恢复。
 * 远程回复属于至少一次；退款由原 messageId 在 workflow 侧幂等，不能用 ACK 当处理成功。
 *
 * <p><b>骨架说明</b>：回复需飞书应用凭据，{@code reply.enabled=false} 时只收不回，便于先验证入站链路。
 */
public class FeishuMessageBridge {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageBridge.class);

    private final HttpConversationClient conversation;
    private final HttpFeishuReplyClient reply;
    private final HttpWorkflowClient workflow;
    private final String tenantId;
    private final boolean intentRoutingEnabled;
    private final InboundInbox inbox;

    public FeishuMessageBridge(HttpConversationClient conversation, HttpFeishuReplyClient reply,
                               HttpWorkflowClient workflow,
                               FeishuProperties props,
                               InboundInbox inbox) {
        this.conversation = conversation;
        this.reply = reply;
        this.workflow = workflow;
        this.tenantId = props.getTenantId();
        this.intentRoutingEnabled = props.getIntentRouting().isEnabled();
        this.inbox = inbox;
        inbox.register(props.getTenantId(), "feishu", FeishuInboundMessage.class, this::process);
    }

    /** 异步处理一条入站消息（ACK 前写入收件箱）。 */
    public void handle(FeishuInboundMessage msg) {
        if (msg == null || msg.text() == null || msg.text().isBlank()) {
            return;
        }
        inbox.receive(tenantId, "feishu", msg.messageId(), msg);
    }

    /**
     * 同步处理（抽出便于单测：不经 executor 直接跑）。
     *
     * <p>失败<b>向上抛</b>：由 inbox 记录失败并执行有界重试，飞书重投才能重来。
     * 这里自己吞掉异常会让消息被永久标记为已处理。
     */
    void process(FeishuInboundMessage msg) {
        TenantContext.Tenant prev = TenantContext.captureRaw();
        try {
            TenantContext.set(new TenantContext.Tenant(tenantId, "feishu:" + nz(msg.openId()), Set.of("chat")));
            // 意图路由（默认关）：退款/投诉 → 起退款工作流并回执；其余 / 起单失败 → 走普通对话。
            String replyText = null;
            if (intentRoutingEnabled && FeishuIntent.classify(msg.text()) == FeishuIntent.Route.WORKFLOW) {
                replyText = routeToWorkflowOrNull(msg);
            }
            if (replyText == null) {
                replyText = conversation.chat(msg.text(), nz(msg.openId()));
            }
            if (replyText != null && !replyText.isBlank()) {
                reply.replyText(msg.openId(), replyText);
            }
        } finally {
            if (prev != null) TenantContext.set(prev); else TenantContext.clear();
        }
    }

    /**
     * 起退款工作流并生成回执文案；起单失败返回 {@code null} 让上游降级为普通对话（不因工作流不可用而丢消息）。
     * 挂起人工审批 → "已转人工审核（工单 …）"，终态由 workflow 终态事件经既有回推链送回；
     * 低风险自动受理 → 直接回流程给出的答复。
     */
    private String routeToWorkflowOrNull(FeishuInboundMessage msg) {
        try {
            HttpWorkflowClient.StartResult res = workflow.startRefund(
                    msg.text(), "feishu:" + nz(msg.openId()), msg.messageId());
            if (HttpWorkflowClient.STATUS_WAITING.equals(res.status())) {
                return "您的请求涉及退款/投诉，已转人工审核（工单 " + shortId(res.instanceId()) + "）。稍后为您答复。";
            }
            return res.reply() != null && !res.reply().isBlank()
                    ? res.reply() : "已受理您的请求，稍后为您答复。";
        } catch (Exception e) {
            log.warn("feishu workflow start failed, fallback to chat messageId={}: {}", msg.messageId(), e.toString());
            return null;
        }
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "-";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
