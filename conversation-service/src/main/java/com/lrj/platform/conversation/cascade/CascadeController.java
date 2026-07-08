package com.lrj.platform.conversation.cascade;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Model Cascade 入口（{@code app.chat.cascade.enabled=true} 才装配）。走 {@code /chat} 同套鉴权链
 * （内部 JWT + 多租户 + 限流 + 配额）——底层 cheap/strong 两个模型都挂了 {@code ChatModelListener}，
 * token 照常计入当前租户配额。
 *
 * <p>{@code POST /chat/cascade} body {@code {"message":"..."}} → 便宜模型先答、低置信才升级强模型，
 * 返回 {@link CascadeResult}（answer / served=cheap|strong / cheapConfident）。
 */
@RestController
@ConditionalOnProperty(name = "app.chat.cascade.enabled", havingValue = "true")
public class CascadeController {

    private final CascadeService cascade;

    public CascadeController(CascadeService cascade) {
        this.cascade = cascade;
    }

    @PostMapping("/chat/cascade")
    public ResponseEntity<?> cascade(@RequestBody Map<String, Object> body) {
        Object m = body == null ? null : body.get("message");
        String message = m == null ? "" : m.toString();
        if (message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }
        return ResponseEntity.ok(cascade.ask(message));
    }
}
