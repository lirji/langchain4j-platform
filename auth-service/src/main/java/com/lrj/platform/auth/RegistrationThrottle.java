package com.lrj.platform.auth;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自助注册节流：按<b>客户端 IP</b> 固定窗口计数（区别于登录节流的 username+IP，且成功/失败都计数，
 * 避免攻击者换 username 绕过）。{@code maxKeys} 给内存表设容量上限并惰性清理过期项，防止随机 IP 撑爆
 * 内存。多副本下为节点本地限制（严格全局限流是后续项）。超限抛 429 {@link AuthException}。
 *
 * <p>注意：时间源用 {@link System#currentTimeMillis()}（生产运行期），无外部时钟依赖。
 */
@Component
public class RegistrationThrottle {

    private record Window(long startMillis, AtomicInteger count) {}

    private final AuthProperties.Registration.RegistrationThrottle props;
    private final ConcurrentMap<String, Window> byIp = new ConcurrentHashMap<>();

    public RegistrationThrottle(AuthProperties props) {
        this.props = props.getRegistration().getThrottle();
    }

    /** 记一次注册尝试并检查是否超限；超限抛 429。关闭时直接放行。 */
    public void checkAndRecord(String clientIp) {
        if (!props.isEnabled()) {
            return;
        }
        String key = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp;
        long now = System.currentTimeMillis();
        long windowMs = props.getWindow().toMillis();

        evictIfOverCapacity(now, windowMs);

        Window w = byIp.compute(key, (k, cur) -> {
            if (cur == null || now - cur.startMillis() >= windowMs) {
                return new Window(now, new AtomicInteger(1));
            }
            cur.count().incrementAndGet();
            return cur;
        });
        if (w.count().get() > props.getMaxAttempts()) {
            throw new AuthException(429, "too_many_registrations", "注册尝试过于频繁，请稍后再试");
        }
    }

    /** 容量上限保护：超过 maxKeys 时清理已过期的窗口项（惰性 GC）。 */
    private void evictIfOverCapacity(long now, long windowMs) {
        if (byIp.size() <= props.getMaxKeys()) {
            return;
        }
        byIp.entrySet().removeIf(e -> now - e.getValue().startMillis() >= windowMs);
    }

    /** 测试/诊断用：当前跟踪的 IP 数。 */
    int trackedKeys() {
        return byIp.size();
    }
}
