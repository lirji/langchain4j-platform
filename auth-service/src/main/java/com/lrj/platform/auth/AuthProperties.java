package com.lrj.platform.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * auth-service 行为配置。前缀 {@code app.auth.*}。
 *
 * <p>令牌密钥/有效期在 {@code platform.security.session.*}（与 edge-gateway 共用），这里只放
 * auth-service 特有的：存储后端、刷新 cookie、登录节流、演示账号默认口令。
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 账号/会话存储后端：{@code in-memory}（默认，重启即失效）或 {@code jdbc}。 */
    private String store = "in-memory";

    /** 演示种子账号的默认明文口令（仅内存 store 用；启动时 BCrypt 哈希）。生产请落库自管。 */
    private String demoPassword = "demo12345";

    private final Cookie cookie = new Cookie();
    private final Throttle throttle = new Throttle();

    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public String getDemoPassword() { return demoPassword; }
    public void setDemoPassword(String demoPassword) { this.demoPassword = demoPassword; }
    public Cookie getCookie() { return cookie; }
    public Throttle getThrottle() { return throttle; }

    /** 刷新令牌 cookie 属性。跨域直调时须 {@code same-site=None} + {@code secure=true}；dev 走同源代理可用 {@code Lax}。 */
    public static class Cookie {
        private String name = "refresh_token";
        /** 仅作用于 /auth 路径，业务请求不携带刷新令牌。 */
        private String path = "/auth";
        private boolean secure = false;
        /** Lax / Strict / None。跨域携带 cookie 必须为 None 且 secure=true。 */
        private String sameSite = "Lax";
        /** 可选：显式 cookie 域；留空则由浏览器按当前主机推断。 */
        private String domain = "";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public boolean isSecure() { return secure; }
        public void setSecure(boolean secure) { this.secure = secure; }
        public String getSameSite() { return sameSite; }
        public void setSameSite(String sameSite) { this.sameSite = sameSite; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
    }

    /** 登录爆破节流（内存计数，因 /auth/login 在边缘是 open 路径、绕过网关限流）。 */
    public static class Throttle {
        private boolean enabled = true;
        /** 窗口内允许的最大失败次数，超过则 429。 */
        private int maxAttempts = 5;
        private Duration window = Duration.ofMinutes(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }
}
