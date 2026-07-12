package com.lrj.platform.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 登录爆破节流：内存滑动窗口计数。因 {@code /auth/login} 在边缘是 open 路径、绕过网关限流，
 * 这里按 key（用户名+IP）限制窗口内失败次数。多副本各自计数（够用；严格需 Redis）。
 */
@Component
public class LoginThrottle {

    private final AuthProperties props;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public LoginThrottle(AuthProperties props) {
        this.props = props;
    }

    /** 是否已被限制（窗口内失败次数达上限）。 */
    public boolean isBlocked(String key) {
        if (!props.getThrottle().isEnabled()) {
            return false;
        }
        Window w = windows.get(key);
        if (w == null) {
            return false;
        }
        Duration window = props.getThrottle().getWindow();
        synchronized (w) {
            if (Instant.now().isAfter(w.windowStart.plus(window))) {
                return false; // 窗口已过期，视为未限制（下次失败重置）
            }
            return w.failures >= props.getThrottle().getMaxAttempts();
        }
    }

    /** 记一次登录失败。 */
    public void recordFailure(String key) {
        if (!props.getThrottle().isEnabled()) {
            return;
        }
        Duration window = props.getThrottle().getWindow();
        Instant now = Instant.now();
        windows.compute(key, (k, w) -> {
            if (w == null || now.isAfter(w.windowStart.plus(window))) {
                return new Window(now, 1);
            }
            synchronized (w) {
                w.failures++;
            }
            return w;
        });
    }

    /** 登录成功后清除该 key 的失败计数。 */
    public void reset(String key) {
        windows.remove(key);
    }

    private static final class Window {
        private final Instant windowStart;
        private int failures;

        private Window(Instant windowStart, int failures) {
            this.windowStart = windowStart;
            this.failures = failures;
        }
    }
}
