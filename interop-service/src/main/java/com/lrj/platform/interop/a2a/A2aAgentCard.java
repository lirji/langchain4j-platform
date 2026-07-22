package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 真 A2A Agent Card —— 服务发现元数据，发布在 {@code /.well-known/agent-card.json}。
 * 声明 endpoint / 协议能力 / 技能清单 / 认证方式，客户端据此决定怎么调。
 *
 * <p>刻意与 {@code com.lrj.platform.protocol.interop.AgentCard}（平台自有互操作卡）区分：
 * 后者服务于 {@code /interop/agent-card} 与 {@code /interop/a2a/agent-card}，本类服务于真 A2A 发现。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aAgentCard(String name,
                           String description,
                           String url,
                           String version,
                           String protocolVersion,
                           Capabilities capabilities,
                           List<String> defaultInputModes,
                           List<String> defaultOutputModes,
                           List<Skill> skills,
                           Map<String, SecurityScheme> securitySchemes,
                           List<Map<String, List<String>>> security) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capabilities(boolean streaming, boolean pushNotifications, boolean stateTransitionHistory) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Skill(String id,
                        String name,
                        String description,
                        List<String> tags,
                        List<String> examples,
                        List<String> inputModes,
                        List<String> outputModes) {
    }

    /** OpenAPI/A2A security scheme 子集；Bearer 使用 type=http/scheme=bearer，legacy 使用 apiKey/in/name。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SecurityScheme(String type,
                                 String scheme,
                                 String bearerFormat,
                                 String in,
                                 String name,
                                 String description) {
    }
}
