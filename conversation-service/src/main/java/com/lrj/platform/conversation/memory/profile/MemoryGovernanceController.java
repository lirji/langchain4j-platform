package com.lrj.platform.conversation.memory.profile;

import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 用户只治理自己的记忆；身份不出现在命令 DTO 中，杜绝客户端覆盖范围。 */
@RestController
@RequestMapping("/memory/governance")
public class MemoryGovernanceController {
    private final ObjectProvider<UserProfileStore> stores;

    public MemoryGovernanceController(ObjectProvider<UserProfileStore> stores) { this.stores = stores; }

    /** 包含候选与已过期条目，便于用户解释为何某条记忆未被召回。 */
    @GetMapping
    public GovernedProfileStore.Snapshot snapshot() {
        var identity = TenantContext.current();
        return store().snapshot(identity.tenantId(), identity.userId());
    }

    /** 所有状态变更都经持久化版本条件校验。 */
    @PostMapping("/{recordId}/decision")
    public ResponseEntity<Void> decide(@PathVariable long recordId, @RequestBody GovernedProfileStore.Decision decision) {
        var identity = TenantContext.current();
        store().decide(identity.tenantId(), identity.userId(), recordId, decision);
        return ResponseEntity.noContent().build();
    }

    /** 开关变化也使旧抽取失效；省略并发版本的请求不能默认当作版本0。 */
    @PutMapping("/settings")
    public ResponseEntity<Void> settings(@RequestBody Settings command) {
        if (command.enabled() == null || command.expectedGeneration() == null || command.expectedGeneration() < 0)
            throw new IllegalArgumentException("enabled and expectedGeneration required");
        var identity = TenantContext.current();
        store().settings(identity.tenantId(), identity.userId(), command.expectedGeneration(), command.enabled());
        return ResponseEntity.noContent().build();
    }

    /** 游标列表不复制记忆正文，历史动作不会成为删除后的内容副本。 */
    @GetMapping("/audit")
    public List<GovernedProfileStore.Audit> audit(
            @RequestParam(defaultValue = "9223372036854775807") long beforeId,
            @RequestParam(defaultValue = "50") int limit) {
        var identity = TenantContext.current();
        return store().audit(identity.tenantId(), identity.userId(), beforeId, limit);
    }

    private GovernedProfileStore store() {
        if (stores.getIfAvailable() instanceof GovernedProfileStore governed) return governed;
        throw new MemoryGovernanceException("unavailable", "persistent memory governance is not enabled");
    }

    /** 可恢复的业务错误保留稳定代码，系统错误只返回通用消息。 */
    @ExceptionHandler(MemoryGovernanceException.class)
    public ResponseEntity<Map<String, String>> business(MemoryGovernanceException error) {
        int status = switch (error.code()) { case "not_found" -> 404; case "unavailable" -> 503; default -> 409; };
        return ResponseEntity.status(status).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "bad_request", "message", error.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, String>> storageUnavailable() {
        return ResponseEntity.status(503).body(Map.of("code", "storage_unavailable", "message", "memory storage unavailable"));
    }

    /** 不接受 tenant/user 字段，所有范围由认证上下文决定。 */
    public record Settings(Boolean enabled, Long expectedGeneration) {}
}
