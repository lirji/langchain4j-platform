package com.lrj.platform.channel.inbox;

import com.lrj.platform.security.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 受内部 JWT 权限约束的运维入口，只展示状态，不暴露用户消息内容。 */
@RestController
public final class InboundInboxController {
    private final InboundInboxStore store;

    public InboundInboxController(InboundInboxStore store) { this.store = store; }

    /** 租户来自验签上下文，调用者不能指定其他租户。 */
    @GetMapping("/channel/inbox")
    public ResponseEntity<?> list(@RequestParam(defaultValue = "50") int limit) {
        var tenant = TenantContext.current();
        if (!tenant.hasScope("channel")) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(store.list(tenant.tenantId(), limit).stream().map(m -> new Status(
                m.inboxId(), m.source(), m.status(), m.attempts(), m.leaseEpoch(), m.nextAttemptAt(), m.errorCode())).toList());
    }

    /** 重放属于管理员操作；远程效果可能已经发生，原消息键必须继续用于业务幂等。 */
    @PostMapping("/channel/inbox/{id}/replay")
    public ResponseEntity<?> replay(@PathVariable String id) {
        var tenant = TenantContext.current();
        if (!tenant.hasScope("channel.admin")) return ResponseEntity.status(403).build();
        boolean changed = store.replay(tenant.tenantId(), id, System.currentTimeMillis());
        return ResponseEntity.status(changed ? 202 : 409).body(Map.of("replayed", changed));
    }

    /** 状态协议只公开稳定代码与领取次数。 */
    public record Status(String inboxId, String source, String status, int attempts, long leaseEpoch,
                         long nextAttemptAt, String errorCode) {}
}
