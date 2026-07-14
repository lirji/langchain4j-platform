package com.lrj.platform.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    private final Registration registration = new Registration();
    private final Rbac rbac = new Rbac();
    private final Seed seed = new Seed();
    private final PasswordPolicy passwordPolicy = new PasswordPolicy();
    private final ClientIp clientIp = new ClientIp();

    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public String getDemoPassword() { return demoPassword; }
    public void setDemoPassword(String demoPassword) { this.demoPassword = demoPassword; }
    public Cookie getCookie() { return cookie; }
    public Throttle getThrottle() { return throttle; }
    public Registration getRegistration() { return registration; }
    public Rbac getRbac() { return rbac; }
    public Seed getSeed() { return seed; }
    public PasswordPolicy getPasswordPolicy() { return passwordPolicy; }
    public ClientIp getClientIp() { return clientIp; }

    /**
     * RBAC 开关（默认关闭）。{@code enabled=false} 时登录只用直配 scopes（角色不展开），行为回到
     * 加 RBAC 之前；灰度时可先只读（{@code adminWritesEnabled=false}）验证登录与 admin GET，稳定后再开写。
     * {@code bootstrapAdminUsers} 是迁移/种子时应确保拥有 admin 角色的用户名单（生产必须显式配置）。
     *
     * <p>{@code inheritanceEnabled}：继承式 RBAC 的第三段灰度（默认关闭）。关时 {@code
     * EffectivePermissionResolver} 只做两级（个人角色 ∪ 直配），与加继承前逐字节一致；开后再把<b>租户基础角色</b>
     * 与<b>用户组角色</b>并入有效 scopes（{@code 有效 = 租户基础 ∪ 组 ∪ 个人角色 ∪ 直配}）。建议先建表、开
     * {@code adminWritesEnabled} 配好租户/组绑定并用归因接口核对，再翻此开关让继承折进签发的 JWT。
     */
    public static class Rbac {
        private boolean enabled = false;
        private boolean adminWritesEnabled = false;
        private boolean inheritanceEnabled = false;
        private List<String> bootstrapAdminUsers = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAdminWritesEnabled() { return adminWritesEnabled; }
        public void setAdminWritesEnabled(boolean adminWritesEnabled) { this.adminWritesEnabled = adminWritesEnabled; }
        public boolean isInheritanceEnabled() { return inheritanceEnabled; }
        public void setInheritanceEnabled(boolean inheritanceEnabled) { this.inheritanceEnabled = inheritanceEnabled; }
        public List<String> getBootstrapAdminUsers() { return bootstrapAdminUsers; }
        public void setBootstrapAdminUsers(List<String> bootstrapAdminUsers) { this.bootstrapAdminUsers = bootstrapAdminUsers; }
    }

    /** 种子开关：{@code false} 时空库不自动灌 demo 账号/角色（生产库应走受控 SQL 建首个用户）。 */
    public static class Seed {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** 密码策略：注册、admin 建户、改密共用。默认最短 6（与既有校验一致），生产可提升。 */
    public static class PasswordPolicy {
        private int minLength = 6;
        private int maxLength = 128;
        public int getMinLength() { return minLength; }
        public void setMinLength(int minLength) { this.minLength = minLength; }
        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }

    /**
     * 客户端 IP 解析。默认只取 {@code remoteAddr}；仅当 Ingress/edge 已覆盖（而非透传）外部
     * {@code X-Forwarded-For} 时才可开 {@code trustForwardedFor}，否则调用者能伪造 XFF 轮换限流 key。
     */
    public static class ClientIp {
        private boolean trustForwardedFor = false;
        public boolean isTrustForwardedFor() { return trustForwardedFor; }
        public void setTrustForwardedFor(boolean trustForwardedFor) { this.trustForwardedFor = trustForwardedFor; }
    }

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

    /**
     * 自助注册。默认**关闭**（生产按需开启）。开启后 {@code POST /auth/register} 建号并派默认角色；
     * {@code rules} 按邮箱域自动映射到租户+角色（命中优先，否则用 default-tenant/default-role）。
     */
    public static class Registration {
        private boolean enabled = false;
        /** 未命中任何规则时的默认租户。 */
        private String defaultTenant = "public";
        /** 未命中任何规则时授予的默认角色。 */
        private String defaultRole = "viewer";
        private List<Rule> rules = new ArrayList<>();
        private final RegistrationThrottle throttle = new RegistrationThrottle();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDefaultTenant() { return defaultTenant; }
        public void setDefaultTenant(String defaultTenant) { this.defaultTenant = defaultTenant; }
        public String getDefaultRole() { return defaultRole; }
        public void setDefaultRole(String defaultRole) { this.defaultRole = defaultRole; }
        public List<Rule> getRules() { return rules; }
        public void setRules(List<Rule> rules) { this.rules = rules; }
        public RegistrationThrottle getThrottle() { return throttle; }

        /**
         * 按客户端 IP 的注册节流（区别于登录节流的 username+IP，且成功/失败都计数，避免换 username 绕过）。
         * {@code maxKeys} 给内存计数表设容量上限，防止随机 IP 撑爆内存。
         */
        public static class RegistrationThrottle {
            private boolean enabled = true;
            private int maxAttempts = 10;
            private Duration window = Duration.ofMinutes(10);
            private int maxKeys = 10000;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public int getMaxAttempts() { return maxAttempts; }
            public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
            public Duration getWindow() { return window; }
            public void setWindow(Duration window) { this.window = window; }
            public int getMaxKeys() { return maxKeys; }
            public void setMaxKeys(int maxKeys) { this.maxKeys = maxKeys; }
        }

        /** 一条注册映射规则：邮箱域 → 租户 + 角色集。 */
        public static class Rule {
            /** 匹配的邮箱域，如 {@code acme.com}（匹配用户名 {@code xxx@acme.com}）。 */
            private String emailDomain;
            private String tenant;
            private List<String> roles = new ArrayList<>();

            public String getEmailDomain() { return emailDomain; }
            public void setEmailDomain(String emailDomain) { this.emailDomain = emailDomain; }
            public String getTenant() { return tenant; }
            public void setTenant(String tenant) { this.tenant = tenant; }
            public List<String> getRoles() { return roles; }
            public void setRoles(List<String> roles) { this.roles = roles; }
        }
    }
}
