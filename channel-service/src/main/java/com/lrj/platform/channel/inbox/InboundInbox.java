package com.lrj.platform.channel.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** ACK 前持久化，后台有界领取。崩溃后用租约恢复；远程效果依赖业务幂等，不能声称只执行一次。 */
public final class InboundInbox {
    private static final Logger log = LoggerFactory.getLogger(InboundInbox.class);
    private final InboundInboxStore store;
    private final ObjectMapper json;
    private final Executor executor;
    private final Clock clock;
    private final Semaphore slots;
    private final int maxAttempts;
    private final long leaseMillis;
    private final long retryMillis;
    private final long executionMillis;
    private final String owner = UUID.randomUUID().toString();
    private final Map<String, Binding<?>> bindings = new LinkedHashMap<>();
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private int cursor;

    /** 参数由运行配置提供，测试可注入时钟与同步执行器证明恢复行为。 */
    public InboundInbox(InboundInboxStore store, ObjectMapper json, Executor executor, Clock clock,
                         int concurrency, int maxAttempts, long leaseMillis, long retryMillis, long executionMillis) {
        if (concurrency < 1 || maxAttempts < 1 || leaseMillis < 1000 || retryMillis < 0
                || executionMillis < leaseMillis) throw new IllegalArgumentException("invalid inbox limits");
        this.store = store; this.json = json; this.executor = executor; this.clock = clock;
        slots = new Semaphore(concurrency); this.maxAttempts = maxAttempts;
        this.leaseMillis = leaseMillis; this.retryMillis = retryMillis; this.executionMillis = executionMillis;
    }

    /** 绑定只来自服务端渠道配置，消息载荷不能选择租户或执行器。 */
    public synchronized <T> void register(String tenant, String source, Class<T> type, Consumer<T> handler) {
        validate(tenant, 128); validate(source, 32);
        String key = tenant + "\u0000" + source;
        if (bindings.containsKey(key)) throw new IllegalArgumentException("duplicate inbox binding");
        bindings.put(key, new Binding<>(tenant, source, type, handler));
    }

    /** 只有权威存储成功后才返回，线程池拒收不影响已持久化的消息。 */
    public void receive(String tenant, String source, String messageId, Object payload) {
        validate(tenant, 128); validate(source, 32); validate(messageId, 256);
        try {
            String encoded = json.writeValueAsString(payload);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > 131072) throw new IllegalArgumentException("inbox payload too large");
            String id = hash(json.writeValueAsString(java.util.List.of(tenant, source, messageId)));
            long now = clock.millis();
            store.receive(new InboundInboxMessage(id, tenant, source, messageId, hash(encoded), encoded,
                    MDC.get("traceId"), InboundInboxMessage.PENDING, 0, null, 0, 0, now, now, now, null));
        } catch (InboundInboxStore.PayloadConflictException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new UnavailableException(e);
        }
        // 接收已提交；独立轮询负责调度，ACK 不等待领取查询或模型执行。
    }

    /** 公平轮询各绑定，每次最多领取本机并发容量；过期执行器的心跳不能复活旧 epoch。 */
    @Scheduled(fixedDelayString = "${channel.inbox.poll-millis:1000}")
    public synchronized void tick() {
        long now = clock.millis();
        try {
            for (Active a : active.values()) {
                if (now < a.deadline()) store.renew(a.claim(), now, now + leaseMillis);
            }
            var ready = java.util.List.copyOf(bindings.values());
            int count = ready.size();
            for (int i = 0; i < count && slots.tryAcquire(); i++) {
                Binding<?> b = ready.get(Math.floorMod(cursor++, count));
                boolean transferred = false;
                try {
                    var claims = store.claimDue(b.tenant(), b.source(), owner, now, leaseMillis, 1);
                    if (!claims.isEmpty()) {
                        var c = claims.getFirst();
                        active.put(activeKey(c), new Active(c, now + executionMillis));
                        try {
                            executor.execute(() -> execute(b, c));
                        } catch (RuntimeException rejected) {
                            active.remove(activeKey(c));
                            // 没有开始执行，可立即归还；归还失败仍由租约恢复，不丢已 ACK 的内容。
                            store.retry(c, clock.millis(), clock.millis(), false, "EXECUTOR_REJECTED");
                            throw rejected;
                        }
                        transferred = true;
                    }
                } finally {
                    if (!transferred) slots.release();
                }
            }
        } catch (Exception e) {
            log.warn("inbox polling unavailable type={}", e.getClass().getSimpleName());
        }
    }

    private <T> void execute(Binding<T> b, InboundInboxMessage c) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            if (c.traceId() != null) MDC.put("traceId", c.traceId());
            // 连续进程崩溃也计入次数，避免无法正常完成的毒消息无限领取。
            if (c.attempts() > maxAttempts) {
                store.retry(c, clock.millis(), clock.millis(), true, "ATTEMPTS_EXHAUSTED");
                return;
            }
            b.handler().accept(json.readValue(c.payload(), b.type()));
            if (!store.succeed(c, clock.millis())) log.warn("inbox stale completion source={} epoch={}", c.source(), c.leaseEpoch());
        } catch (Exception e) {
            long now = clock.millis();
            long delay = Math.min(300000, retryMillis * (1L << Math.min(c.attempts() - 1, 10)));
            try {
                store.retry(c, now, now + delay, c.attempts() >= maxAttempts, "PROCESSING_FAILED");
            } catch (Exception unavailable) {
                // 保持 RUNNING，恢复时由过期租约接管；日志不包含消息或外部异常文本。
                log.warn("inbox retry persistence unavailable source={}", c.source());
            }
        } finally {
            active.remove(activeKey(c)); slots.release();
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }

    private static void validate(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("invalid inbound identity");
    }

    private static String hash(String value) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String activeKey(InboundInboxMessage c) {
        return c.inboxId() + ":" + c.leaseEpoch();
    }

    private record Binding<T>(String tenant, String source, Class<T> type, Consumer<T> handler) {}
    private record Active(InboundInboxMessage claim, long deadline) {}

    /** 未持久化的事件必须返回可重试错误，不能回 ACK 伪装接收成功。 */
    public static final class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) { super("inbound inbox unavailable", cause); }
    }
}
