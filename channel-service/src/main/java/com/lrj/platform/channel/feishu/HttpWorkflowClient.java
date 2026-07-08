package com.lrj.platform.channel.feishu;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 桥 → workflow-service 的 {@code /workflow/refund/start} 客户端。用带 {@code OutboundTenantForwarder}
 * （从 {@code TenantContext} 铸发内部 JWT）+ {@code OutboundTraceForwarder} 的 RestTemplate，
 * 故调用前需先在当前线程 set 好 {@code TenantContext}（桥用飞书应用配置的租户）。
 *
 * <p>意图路由命中退款/投诉时起退款审批流程：低风险自动受理（{@code COMPLETED} + reply），
 * 高风险挂起等人工审批（{@code WAITING_APPROVAL}）。只解析渠道回推需要的三个字段，
 * 不引入 workflow-service 内部类型（响应按 Map 读，与 {@link HttpConversationClient} 一致）。
 */
public class HttpWorkflowClient {

    public static final String STATUS_WAITING = "WAITING_APPROVAL";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private final RestTemplate restTemplate;

    public HttpWorkflowClient(RestTemplate feishuWorkflowRestTemplate) {
        this.restTemplate = feishuWorkflowRestTemplate;
    }

    /** 起退款流程。dedupeId 用渠道消息 id 做幂等（飞书重投同一诉求只起一个流程）。 */
    public StartResult startRefund(String message, String chatId, String dedupeId) {
        Map<String, String> body = new HashMap<>();
        body.put("message", message == null ? "" : message);
        body.put("chatId", chatId == null || chatId.isBlank() ? "default" : chatId);
        if (dedupeId != null && !dedupeId.isBlank()) {
            body.put("dedupeId", dedupeId);
        }
        ResponseEntity<Map> resp = restTemplate.postForEntity("/workflow/refund/start", body, Map.class);
        Map<?, ?> b = resp.getBody();
        if (b == null) {
            return new StartResult(null, null, null);
        }
        return new StartResult(str(b.get("instanceId")), str(b.get("status")), str(b.get("reply")));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** workflow 起单结果（只取渠道回推需要的三个字段）。 */
    public record StartResult(String instanceId, String status, String reply) {
    }
}
