package com.lrj.platform.conversation.shadow;

import com.lrj.platform.protocol.conversation.ConversationGenerationRequest;
import com.lrj.platform.protocol.conversation.ConversationGenerationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 异步调用独立无状态 runtime。候选响应仅进入低基数指标，正文不会被返回、缓存或写日志。
 */
public class HttpConversationShadowObserver implements ConversationShadowObserver {

    private static final Logger log = LoggerFactory.getLogger(HttpConversationShadowObserver.class);
    private final RestTemplate restTemplate;
    private final Executor executor;
    private final ConversationShadowMetrics metrics;

    public HttpConversationShadowObserver(RestTemplate restTemplate, Executor executor,
                                          ConversationShadowMetrics metrics) {
        this.restTemplate = Objects.requireNonNull(restTemplate);
        this.executor = Objects.requireNonNull(executor);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public void observe(ConversationGenerationRequest request, String primaryReply) {
        try {
            executor.execute(() -> callCandidate(request, primaryReply));
        } catch (RuntimeException exception) {
            metrics.record("rejected", Duration.ZERO);
            log.warn("conversation candidate shadow submission failed: {}",
                    exception.getClass().getSimpleName());
        }
    }

    private void callCandidate(ConversationGenerationRequest request, String primaryReply) {
        long started = System.nanoTime();
        try {
            ConversationGenerationResponse response = restTemplate.postForObject(
                    "/internal/conversation/generate",
                    request,
                    ConversationGenerationResponse.class);
            if (response == null || response.reply() == null || response.reply().isBlank()) {
                throw new IllegalStateException("conversation candidate returned an empty reply");
            }
            metrics.record("success", elapsed(started));
            metrics.comparison(response.reply().trim().equals(primaryReply.trim()));
        } catch (RuntimeException exception) {
            metrics.record("failure", elapsed(started));
            log.warn("conversation candidate shadow failed: {}",
                    exception.getClass().getSimpleName());
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }
}
