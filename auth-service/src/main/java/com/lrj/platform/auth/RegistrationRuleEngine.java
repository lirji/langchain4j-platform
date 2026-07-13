package com.lrj.platform.auth;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 自助注册的"按规则自动映射"：根据用户名（邮箱形态时取其域）匹配 {@link AuthProperties.Registration}
 * 里配置的规则，决定新用户落到哪个租户、派哪些角色。无命中则用默认租户 + 默认角色。
 *
 * <p>这是三条分配路径里的"规则自动映射"；另两条是 admin 后台指派与自助注册默认角色（本引擎的兜底分支）。
 */
@Component
public class RegistrationRuleEngine {

    /** 与公共库保留租户一致，禁止注册占用（auth 与 knowledge 各自独立服务，故此处复述该常量）。 */
    static final String RESERVED_PUBLIC_TENANT = "__public__";

    private final AuthProperties props;

    public RegistrationRuleEngine(AuthProperties props) {
        this.props = props;
    }

    /** 解析结果：目标租户与角色集。 */
    public record Assignment(String tenant, Set<String> roles) {}

    /** 按用户名/邮箱解析目标租户与角色。 */
    public Assignment resolve(String username) {
        AuthProperties.Registration reg = props.getRegistration();
        String domain = domainOf(username);
        if (domain != null) {
            for (AuthProperties.Registration.Rule rule : reg.getRules()) {
                if (rule.getEmailDomain() != null
                        && domain.equalsIgnoreCase(rule.getEmailDomain().trim())) {
                    String tenant = requireSafeTenant(rule.getTenant());
                    return new Assignment(tenant, new LinkedHashSet<>(rule.getRoles()));
                }
            }
        }
        String tenant = requireSafeTenant(reg.getDefaultTenant());
        Set<String> roles = new LinkedHashSet<>();
        if (reg.getDefaultRole() != null && !reg.getDefaultRole().isBlank()) {
            roles.add(reg.getDefaultRole().trim());
        }
        return new Assignment(tenant, roles);
    }

    private static String requireSafeTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            throw new AuthException(500, "registration_misconfigured", "注册规则未配置有效租户");
        }
        String t = tenant.trim();
        if (RESERVED_PUBLIC_TENANT.equalsIgnoreCase(t)) {
            throw new AuthException(400, "reserved_tenant", "不允许注册到保留租户");
        }
        return t;
    }

    /** 取邮箱域（小写）；非邮箱返回 null。 */
    private static String domainOf(String username) {
        if (username == null) {
            return null;
        }
        int at = username.indexOf('@');
        if (at < 0 || at == username.length() - 1) {
            return null;
        }
        return username.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }
}
