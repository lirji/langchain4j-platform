package com.lrj.platform.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import com.lrj.platform.security.PublicPayloadRedactor;
import com.lrj.platform.security.TenantContext;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 语音客服装配。<strong>整个 config 条件化在 {@code app.voice.enabled=true}</strong> ——
 * 关闭（默认）时 voice 相关 Bean 全不存在（{@code /voice/**} 端点走软依赖缺失分支，见 {@link VoiceController}）。
 * SpeechService 按 provider 选择 OpenAI-compatible 或百炼原生实现；对话经 HTTP 调 conversation-service。
 */
@Configuration
@ConditionalOnProperty(name = "app.voice.enabled", havingValue = "true")
public class VoiceConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.voice")
    VoiceProperties voiceProperties() {
        return new VoiceProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "app.voice.provider", havingValue = "openai", matchIfMissing = true)
    SpeechService openAiSpeechService(VoiceProperties props, ObjectMapper mapper) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            LoggerFactory.getLogger(VoiceConfig.class).warn(
                    "app.voice.api-key is blank; cloud OpenAI will 401. Set OPENAI_API_KEY if not using a local gateway.");
        }
        return new OpenAiSpeechService(props, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "app.voice.provider", havingValue = "bailian")
    SpeechService bailianSpeechService(VoiceProperties props, ObjectMapper mapper) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "app.voice.provider=bailian requires VOICE_API_KEY/BAILIAN_API_KEY");
        }
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "app.voice.provider=bailian requires VOICE_BASE_URL/BAILIAN_NATIVE_BASE_URL");
        }
        return new BailianSpeechService(props, mapper);
    }

    @Bean
    RestTemplate conversationRestTemplate(RestTemplateBuilder builder,
                                          OutboundTenantForwarder tenantForwarder,
                                          OutboundTraceForwarder traceForwarder,
                                          @Value("${app.voice.conversation-base-url:http://localhost:8081}") String baseUrl,
                                          @Value("${app.voice.conversation-connect-timeout:1s}") Duration connectTimeout,
                                          @Value("${app.voice.conversation-read-timeout:60s}") Duration readTimeout) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }

    @Bean
    ConversationClient conversationClient(RestTemplate conversationRestTemplate) {
        return new HttpConversationClient(conversationRestTemplate);
    }

    @Bean
    VoiceConversationService voiceConversationService(SpeechService speechService, ConversationClient conversationClient) {
        return new VoiceConversationService(speechService, conversationClient);
    }

    @Bean
    VoiceStreamService voiceStreamService(ConversationClient conversationClient, SpeechService speechService,
                                          VoiceProperties props,
                                          @Qualifier("voiceStreamExecutor") Executor executor,
                                          PublicPayloadRedactor redactor) {
        return new VoiceStreamService(
                conversationClient, speechService, props.getStreamSentenceMinChars(), executor, redactor);
    }

    @Bean(name = "voiceStreamExecutor")
    Executor voiceStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("voice-stream-");
        executor.setTaskDecorator(task -> {
            TenantContext.Tenant tenant = TenantContext.captureRaw();
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            return () -> {
                TenantContext.Tenant previousTenant = TenantContext.captureRaw();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    if (tenant != null) TenantContext.set(tenant); else TenantContext.clear();
                    if (mdc != null) MDC.setContextMap(mdc); else MDC.clear();
                    task.run();
                } finally {
                    if (previousTenant != null) TenantContext.set(previousTenant); else TenantContext.clear();
                    if (previousMdc != null) MDC.setContextMap(previousMdc); else MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
