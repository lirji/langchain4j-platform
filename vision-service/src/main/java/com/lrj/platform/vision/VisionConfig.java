package com.lrj.platform.vision;

import com.lrj.platform.gateway.GatewayChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视觉装配。<strong>整个 config 条件化在 {@code app.vision.enabled=true}</strong>——关闭（默认）时
 * {@link VisionModel} / {@link VisionContentGuard} / {@code VisionController} 全不存在，零开销零网络。
 *
 * <p>视觉 {@code ChatModel} 经 {@link GatewayChatModelFactory#build(String, Double)} 构造：指向与文本模型
 * <strong>同一 LiteLLM base-url</strong>，仅逻辑模型名换成多模态模型（{@code app.vision.model-name}），
 * 并<strong>自动挂上全部 {@code ChatModelListener}</strong>（指标 / 成本 / per-tenant token 预算）——
 * 视觉调用 token 因此正确纳入配额。该 {@code ChatModel} 只作 {@link DefaultVisionModel} 构造入参，
 * <strong>不注册成 Bean</strong>，避免与 gateway-client 自动装配的主 {@code ChatModel} 撞 {@code @AiService} 发现。
 */
@Configuration
@EnableConfigurationProperties(VisionProperties.class)
@ConditionalOnProperty(name = "app.vision.enabled", havingValue = "true")
public class VisionConfig {

    private static final Logger log = LoggerFactory.getLogger(VisionConfig.class);

    @Bean
    public VisionContentGuard visionContentGuard(VisionProperties props) {
        return new VisionContentGuard(props.getMaxImageBytes(), props.getAllowedMimeTypes());
    }

    @Bean
    public VisionModel visionModel(VisionProperties props, GatewayChatModelFactory chatModelFactory) {
        String modelName = props.getModelName();
        if (modelName == null || modelName.isBlank()) {
            // fail-fast：开了 vision 却没配多模态模型名——绝不静默回退到文本模型（会静默错答/乱计量）。
            throw new IllegalStateException(
                    "app.vision.enabled=true 但 app.vision.model-name 为空："
                            + "请配置指向 LiteLLM model_list 的多模态模型名（如 gpt-4o-mini / qwen2.5-vl），"
                            + "或将 app.vision.enabled 设为 false 关闭视觉服务。");
        }
        ChatModel model = chatModelFactory.build(modelName.trim(), props.getTemperature());
        log.info("VisionModel ready: model={} maxImageBytes={} captionCacheSize={}",
                modelName, props.getMaxImageBytes(), props.getCaptionCacheSize());
        return new DefaultVisionModel(model, modelName.trim(), props.getCaptionPrompt(), props.getCaptionCacheSize());
    }
}
