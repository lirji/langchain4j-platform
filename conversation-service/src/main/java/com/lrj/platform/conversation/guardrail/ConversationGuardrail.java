package com.lrj.platform.conversation.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 对话护栏编排（移植单体 {@code PromptInjectionGuardrail} + {@code PiiGuardrail}）。在 controller 层
 * 前置扫描输入、后置脱敏输出——纯逻辑、确定性、可单测，且流式/非流式一致，不依赖 langchain4j guardrail SPI。
 *
 * <p>两档独立开关，默认全关（零回归、零依赖 dev/test）：
 * <ul>
 *   <li>{@code app.conversation.guardrail.injection.enabled}（默认 false）+ {@code .mode}：
 *       {@code block}（命中即拒答，默认）/ {@code sanitize}（剥离控制 token 后继续）/ {@code audit}（仅记日志放行）</li>
 *   <li>{@code app.conversation.guardrail.pii.enabled}（默认 false）：输出里的 email/手机号/身份证就地脱敏</li>
 * </ul>
 * 生产建议开启（安全合规）；因平台约定默认关，需显式打开。
 */
@Component
public class ConversationGuardrail {

    private static final Logger log = LoggerFactory.getLogger(ConversationGuardrail.class);

    static final String BLOCK_REPLY = "抱歉，你的请求疑似试图操纵系统指令或越过安全限制，已被拦截。请换一种正常的问法。";

    private final boolean injectionEnabled;
    private final String injectionMode;
    private final boolean piiEnabled;

    public ConversationGuardrail(
            @Value("${app.conversation.guardrail.injection.enabled:false}") boolean injectionEnabled,
            @Value("${app.conversation.guardrail.injection.mode:block}") String injectionMode,
            @Value("${app.conversation.guardrail.pii.enabled:false}") boolean piiEnabled) {
        this.injectionEnabled = injectionEnabled;
        this.injectionMode = injectionMode == null ? "block" : injectionMode.trim().toLowerCase();
        this.piiEnabled = piiEnabled;
        if (injectionEnabled) {
            log.info("Conversation guardrail: prompt-injection enabled (mode={})", this.injectionMode);
        }
        if (piiEnabled) {
            log.info("Conversation guardrail: PII output redaction enabled");
        }
    }

    /**
     * 前置输入扫描。未开启或未命中 → {@link InputDecision#allow(String)}（原消息）。
     * 命中时按 mode：block → 拦截；sanitize → 剥离控制 token 后放行；audit → 记日志放行。
     */
    public InputDecision inspectInput(String message) {
        if (!injectionEnabled || message == null || message.isBlank()) {
            return InputDecision.allow(message);
        }
        String hit = PromptInjectionRules.firstMatch(message);
        if (hit == null) {
            return InputDecision.allow(message);
        }
        return switch (injectionMode) {
            case "audit" -> {
                log.warn("prompt-injection audit: rule={} (allowed)", hit);
                yield InputDecision.allow(message);
            }
            case "sanitize" -> {
                log.warn("prompt-injection sanitize: rule={}", hit);
                yield InputDecision.allow(PromptInjectionRules.stripControlTokens(message));
            }
            default -> {
                log.warn("prompt-injection block: rule={}", hit);
                yield InputDecision.block(hit);
            }
        };
    }

    /** 后置输出脱敏。未开启 → 原样返回；开启 → email/手机号/身份证就地遮蔽。 */
    public String redactOutput(String reply) {
        if (!piiEnabled) {
            return reply;
        }
        return PiiRedactor.redact(reply);
    }

    /**
     * 输入护栏决策。{@code blocked=true} 时 controller 直接返回 {@link #BLOCK_REPLY}，不进 RAG/LLM/记忆。
     */
    public record InputDecision(boolean blocked, String reason, String message) {
        static InputDecision allow(String message) {
            return new InputDecision(false, null, message);
        }

        static InputDecision block(String reason) {
            return new InputDecision(true, reason, null);
        }

        public String blockReply() {
            return BLOCK_REPLY;
        }
    }
}
