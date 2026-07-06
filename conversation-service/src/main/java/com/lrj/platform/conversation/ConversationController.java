package com.lrj.platform.conversation;

import com.lrj.platform.security.TenantContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * /chat 入口。租户身份由 {@code InternalTokenAuthFilter}（platform-security）从内部 JWT 重建，
 * 这里直接读 {@link TenantContext} 即拿到跨网络跳过来的租户 —— 验证租户传播闭环。
 */
@RestController
public class ConversationController {

    private final Assistant assistant;
    private final RagPromptAugmenter ragPromptAugmenter;

    public ConversationController(Assistant assistant, RagPromptAugmenter ragPromptAugmenter) {
        this.assistant = assistant;
        this.ragPromptAugmenter = ragPromptAugmenter;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestParam(value = "chatId", defaultValue = "default") String chatId,
                                    @RequestBody Map<String, String> body) {
        TenantContext.Tenant tenant = TenantContext.current();
        String message = body.getOrDefault("message", "");
        String reply = assistant.chat(ragPromptAugmenter.augment(message));
        return Map.of(
                "reply", reply,
                "chatId", chatId,
                "tenantId", tenant.tenantId(),
                "userId", tenant.userId());
    }
}
