package com.lrj.platform.conversation.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.conversation.ConversationGenerationRequest;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.assertj.core.api.Assertions.*;

/** 仅由本地假提供方/独立Python进程脚本启用, 不会调用真实模型. */
@EnabledIfEnvironmentVariable(named = "STREAM_SHADOW_PROCESS_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class ConversationStreamCandidateProcessTest {
    @Test void signedCrossLanguageHttpSseProducesOneOrderedTerminalAndComparison() throws Exception {
        check("hello", "stream_success", true, false);
    }
    @Test void candidateProviderErrorStaysInShadowAndDoesNotReturnProviderDetails() throws Exception {
        check("error", "stream_failure", false, false);
    }
    @Test void cancellationClosesIndependentCandidateStream() throws Exception {
        check("disconnect", "stream_cancelled", false, true);
    }

    private void check(String message, String outcome, boolean compare, boolean cancel) throws Exception {
        var metrics = new SimpleMeterRegistry();
        try (var executor = Executors.newFixedThreadPool(1); var timer = new ScheduledThreadPoolExecutor(1)) {
            var observer = new HttpConversationStreamShadowObserver(HttpClient.newHttpClient(),
                    URI.create(System.getenv("STREAM_SHADOW_PROCESS_URL") + "/internal/conversation/stream"),
                    new ObjectMapper(), new InternalToken(System.getenv("STREAM_SHADOW_INTERNAL_SECRET"), Duration.ofMinutes(5)),
                    "X-Internal-Token", executor, timer, Duration.ofSeconds(3), new ConversationShadowMetrics(metrics));
            TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
            var request = new ConversationGenerationRequest("1", message, "snapshot",
                    new ConversationGenerationRequest.Style("English", "concise", "cite", ""), List.of());
            var session = observer.open(request);
            TenantContext.clear();
            if (cancel) {
                // 必须证实提供方已打开流, 避免把首请求SDK冷启动前取消当成TCP关闭证据.
                var probe = java.net.http.HttpRequest.newBuilder(URI.create(System.getenv("STREAM_SHADOW_PROVIDER_URL")))
                        .version(HttpClient.Version.HTTP_1_1).GET().build();
                var http = HttpClient.newHttpClient();
                HttpConversationStreamShadowObserverTest.await(() -> {
                    try { return http.send(probe, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
                    catch (Exception ignored) { return false; }
                });
                Thread.sleep(100);
                session.cancel();
            } else session.finish("fake answer");
            HttpConversationStreamShadowObserverTest.await(() -> metrics.find("conversation.shadow.requests").tag("outcome", outcome).counter() != null);
            assertThat(metrics.find("conversation.shadow.requests").tag("outcome", outcome).counter().count()).isOne();
            if (compare) {
                HttpConversationStreamShadowObserverTest.await(() -> metrics.find("conversation.shadow.comparisons").tag("exact_match", "true").counter() != null);
            } else assertThat(metrics.find("conversation.shadow.comparisons").counter()).isNull();
        } finally { TenantContext.clear(); metrics.close(); }
    }
}
