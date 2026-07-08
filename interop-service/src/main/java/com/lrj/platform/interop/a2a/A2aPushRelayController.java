package com.lrj.platform.interop.a2a;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A push 中继回调端点：接收 agent-service 的任务终态 webhook（内网直连、不经 edge-gateway、不带内部 JWT），
 * 据头 {@code X-Agent-Task-Id} / {@code X-Tenant-Id} 交 {@link A2aPushForwarder} 判断是否回推 A2A 客户端。
 *
 * <p>入站 filter（{@code InternalTokenAuthFilter}）不拦截无 JWT 请求，仅按 JWT 绑定租户；此处租户由头显式还原，
 * 故无需白名单。未登记 push 的任务在 forwarder 内被忽略，端点始终返回 200（webhook 语义：收到即确认）。
 */
@RestController
public class A2aPushRelayController {

    private final A2aPushForwarder forwarder;

    public A2aPushRelayController(A2aPushForwarder forwarder) {
        this.forwarder = forwarder;
    }

    @PostMapping("/interop/a2a/push-callback")
    public ResponseEntity<Void> pushCallback(
            @RequestHeader(value = "X-Agent-Task-Id", required = false) String taskId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        forwarder.onTerminal(taskId, tenantId);
        return ResponseEntity.ok().build();
    }
}
