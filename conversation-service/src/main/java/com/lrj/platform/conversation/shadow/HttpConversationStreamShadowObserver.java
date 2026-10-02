package com.lrj.platform.conversation.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.conversation.ConversationGenerationRequest;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.slf4j.MDC;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 有界独立SSE观察. 超时覆盖body读取, 断连显式关闭HTTP流, 不把回复/凭据写日志或状态. */
public final class HttpConversationStreamShadowObserver implements ConversationStreamShadowObserver {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HttpConversationStreamShadowObserver.class);
    private static final String STREAM_TYPE_TOKEN = "token";
    private static final String STREAM_TYPE_DONE = "done";
    private static final int MAX_FRAME_BYTES = 262_144;
    private static final int MAX_REPLY_CHARS = 65_536;
    private static final int MAX_LINES = 32_768;
    private final HttpClient client;
    private final URI endpoint;
    private final ObjectMapper mapper;
    private final InternalToken tokens;
    private final String header;
    private final ExecutorService executor;
    private final ScheduledExecutorService timer;
    private final Duration deadline;
    private final ConversationShadowMetrics metrics;

    public HttpConversationStreamShadowObserver(HttpClient client, URI endpoint, ObjectMapper mapper,
            InternalToken tokens, String header, ExecutorService executor, ScheduledExecutorService timer,
            Duration deadline, ConversationShadowMetrics metrics) {
        if (deadline.isZero() || deadline.isNegative() || deadline.compareTo(Duration.ofSeconds(30)) > 0
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())))
            throw new IllegalArgumentException("invalid stream shadow endpoint or deadline");
        this.client = client;
        this.endpoint = endpoint;
        this.mapper = mapper;
        this.tokens = tokens;
        this.header = header;
        this.executor = executor;
        this.timer = timer;
        this.deadline = deadline;
        this.metrics = metrics;
    }

    @Override public boolean enabled() { return true; }

    @Override public Observation open(ConversationGenerationRequest request) {
        Session session = new Session();
        TenantContext.Tenant caller = TenantContext.captureRaw();
        String trace = MDC.get("traceId");
        try {
            byte[] body = mapper.writeValueAsBytes(request);
            if (body.length > MAX_FRAME_BYTES || caller == null) throw new IllegalArgumentException("invalid shadow input");
            String credential = tokens.mint(caller);
            session.timeout.set(timer.schedule(() -> session.stop("stream_timeout"), deadline.toMillis(), TimeUnit.MILLISECONDS));
            if (session.closed.get()) session.timeout.get().cancel(false);
            session.future.set(executor.submit(() -> session.read(body, credential, trace)));
            if (session.closed.get()) session.future.get().cancel(true);
        } catch (Exception rejected) {
            session.stop("stream_rejected");
        }
        return session;
    }

    private final class Session implements Observation {
        private final long started = System.nanoTime();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean recorded = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<InputStream> input = new AtomicReference<>();
        private final AtomicReference<Future<?>> future = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();
        private final CompletableFuture<String> reply = new CompletableFuture<>();

        @Override public void finish(String primaryReply) {
            if (!closed.get() && finished.compareAndSet(false, true))
                reply.thenAccept(candidate -> {
                    if (!closed.get()) metrics.comparison(candidate.trim().equals(primaryReply.trim()));
                });
        }

        @Override public void cancel() { stop("stream_cancelled"); }

        void stop(String reason) {
            if (closed.compareAndSet(false, true)) {
                closeInput();
                Future<?> task = future.get();
                if (task != null) task.cancel(true);
                ScheduledFuture<?> scheduled = timeout.get();
                if (scheduled != null) scheduled.cancel(false);
                reply.cancel(false);
                record(reason);
            }
        }

        void read(byte[] body, String credential, String trace) {
            if (closed.get()) return;
            try {
                // 候选入口Uvicorn是HTTP/1.1; 明确版本避免h2c升级把请求体误读成新请求.
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).version(HttpClient.Version.HTTP_1_1).timeout(deadline)
                        .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                        .header(header, credential).POST(HttpRequest.BodyPublishers.ofByteArray(body));
                if (trace != null && trace.length() <= 128) builder.header("X-Trace-Id", trace);
                HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                input.set(response.body());
                if (closed.get()) { closeInput(); return; }
                if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))
                    throw new IOException("invalid candidate response");
                String text = validateStream(new BufferedInputStream(response.body()));
                if (!closed.get()) { record("stream_success"); reply.complete(text); }
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                if (!closed.get()) {
                    record("stream_failure");
                    reply.completeExceptionally(new IllegalStateException("candidate stream failed"));
                }
            } finally {
                closeInput();
                ScheduledFuture<?> scheduled = timeout.get();
                if (scheduled != null) scheduled.cancel(false);
            }
        }

        private void closeInput() {
            InputStream body = input.getAndSet(null);
            if (body != null) try { body.close(); } catch (IOException ignored) {
                log.debug("conversation shadow HTTP body close failed");
            }
        }

        private void record(String result) {
            if (recorded.compareAndSet(false, true)) metrics.record(result, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    /** 按既有契约检查每个帧. 单帧、行数、累计字节和回复都有限制, 截断或终态后事件视为失败. */
    String validateStream(InputStream stream) throws IOException {
        StringBuilder answer = new StringBuilder();
        StringBuilder data = new StringBuilder();
        int sequence = 0;
        long totalBytes = 0;
        boolean terminal = false;
        for (int lines = 0; lines < MAX_LINES; lines++) {
            String line = boundedLine(stream);
            if (line == null) {
                if (!terminal || !data.isEmpty()) throw new IOException("incomplete stream");
                return answer.toString();
            }
            totalBytes += line.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > 8L * 1024 * 1024) throw new IOException("stream exceeds bounds");
            if (line.startsWith("data:")) {
                if (data.length() + line.length() > MAX_FRAME_BYTES) throw new IOException("frame exceeds bounds");
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            } else if (line.isEmpty() && !data.isEmpty()) {
                JsonNode event = mapper.readTree(data.toString());
                data.setLength(0);
                if (terminal || !event.isObject() || event.size() != 3
                        || !event.path("sequence").isIntegralNumber() || !event.path("sequence").canConvertToInt()
                        || event.path("sequence").intValue() != sequence++
                        || !event.path("type").isTextual() || !event.path("data").isTextual())
                    throw new IOException("invalid stream envelope");
                String type = event.path("type").textValue(), text = event.path("data").textValue();
                if (STREAM_TYPE_TOKEN.equals(type)) {
                    if (text.isEmpty() || answer.length() + text.length() > MAX_REPLY_CHARS) throw new IOException("invalid token");
                    answer.append(text);
                } else if (STREAM_TYPE_DONE.equals(type) && text.isEmpty() && !answer.isEmpty()) {
                    terminal = true;
                } else {
                    throw new IOException("candidate stream error");
                }
            }
        }
        throw new IOException("stream line limit exceeded");
    }

    private static String boundedLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') return line.toString(StandardCharsets.UTF_8).replaceFirst("\\r$", "");
            if (line.size() >= MAX_FRAME_BYTES) throw new IOException("stream line exceeds bounds");
            line.write(value);
        }
        if (line.size() != 0) throw new IOException("truncated stream line");
        return null;
    }
}
