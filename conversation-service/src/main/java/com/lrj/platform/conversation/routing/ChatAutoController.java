package com.lrj.platform.conversation.routing;

import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /chat/auto}：LLM-as-Router 分类分派对话（默认关）。
 * 记忆键与 {@code /chat} 一致（{@code <tenantId>::<chatId>}），与普通对话共享多轮记忆。
 * 未启用（{@code app.conversation.router.enabled=false}）→ 返回明确禁用提示。
 */
@RestController
public class ChatAutoController {

    private final ObjectProvider<QueryRouterService> routerProvider;

    public ChatAutoController(ObjectProvider<QueryRouterService> routerProvider) {
        this.routerProvider = routerProvider;
    }

    @PostMapping("/chat/auto")
    public Map<String, Object> chatAuto(@RequestParam(value = "chatId", defaultValue = "default") String chatId,
                                        @RequestBody Map<String, String> body) {
        QueryRouterService router = routerProvider.getIfAvailable();
        TenantContext.Tenant tenant = TenantContext.current();
        if (router == null) {
            return Map.of("error", "Query router not enabled. Set app.conversation.router.enabled=true.",
                    "chatId", chatId, "tenantId", tenant.tenantId());
        }
        String message = body.getOrDefault("message", "");
        String memoryKey = tenant.tenantId() + "::" + chatId;
        RoutedReply routed = router.route(memoryKey, message);
        return Map.of(
                "reply", routed.reply(),
                "route", routed.decision().kind().name(),
                "reason", routed.decision().reason(),
                "classifyMs", routed.classifyMs(),
                "answerMs", routed.answerMs(),
                "chatId", chatId,
                "tenantId", tenant.tenantId(),
                "userId", tenant.userId());
    }
}
