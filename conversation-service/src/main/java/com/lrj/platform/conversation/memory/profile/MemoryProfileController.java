package com.lrj.platform.conversation.memory.profile;

import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆端点（对齐单体 {@code MemoryController}，默认关）。租户+用户取自内部 JWT（{@link TenantContext}），
 * 天然只操作自己的画像。未启用 → 明确禁用提示。
 */
@RestController
public class MemoryProfileController {

    private final ObjectProvider<UserProfileChatService> chatProvider;
    private final ObjectProvider<UserProfileService> serviceProvider;

    public MemoryProfileController(ObjectProvider<UserProfileChatService> chatProvider,
                                   ObjectProvider<UserProfileService> serviceProvider) {
        this.chatProvider = chatProvider;
        this.serviceProvider = serviceProvider;
    }

    /** 带长期记忆的对话。 */
    @PostMapping("/chat/memory")
    public Map<String, Object> chatWithMemory(
            @RequestParam(value = "chatId", defaultValue = "default") String chatId,
            @RequestBody Map<String, String> body) {
        TenantContext.Tenant tenant = TenantContext.current();
        UserProfileChatService svc = chatProvider.getIfAvailable();
        if (svc == null) {
            return disabled(tenant);
        }
        String reply = svc.chat(tenant.tenantId(), tenant.userId(), chatId, body.getOrDefault("message", ""));
        return Map.of("reply", reply, "chatId", chatId,
                "tenantId", tenant.tenantId(), "userId", tenant.userId());
    }

    /** 查看当前用户画像。 */
    @GetMapping("/memory/profile")
    public Map<String, Object> profile() {
        TenantContext.Tenant tenant = TenantContext.current();
        UserProfileService svc = serviceProvider.getIfAvailable();
        if (svc == null) {
            return disabled(tenant);
        }
        List<MemoryItem> items = svc.list(tenant.tenantId(), tenant.userId());
        return Map.of("count", items.size(), "items", items, "tenantId", tenant.tenantId());
    }

    /** 清空当前用户画像（合规删除）。 */
    @DeleteMapping("/memory/profile")
    public Map<String, Object> clearProfile() {
        TenantContext.Tenant tenant = TenantContext.current();
        UserProfileService svc = serviceProvider.getIfAvailable();
        if (svc == null) {
            return disabled(tenant);
        }
        int removed = svc.clear(tenant.tenantId(), tenant.userId());
        return Map.of("removed", removed, "tenantId", tenant.tenantId());
    }

    private static Map<String, Object> disabled(TenantContext.Tenant tenant) {
        return Map.of("error", "User profile memory not enabled. Set app.conversation.memory.profile.enabled=true.",
                "tenantId", tenant.tenantId());
    }
}
