package com.lrj.platform.auth;

import org.springframework.stereotype.Component;

/**
 * 密码策略校验：注册、admin 建户、改密共用。只做长度约束（默认 6–128，可经
 * {@code app.auth.password-policy.*} 调整）；BCrypt 哈希仍由 {@link PasswordHasher} 负责。
 * 校验失败抛 400 {@link AuthException}。
 */
@Component
public class PasswordPolicy {

    private final AuthProperties.PasswordPolicy props;

    public PasswordPolicy(AuthProperties props) {
        this.props = props.getPasswordPolicy();
    }

    /** 校验明文口令长度；不合规抛 400。 */
    public void validate(String rawPassword) {
        int len = rawPassword == null ? 0 : rawPassword.length();
        if (len < props.getMinLength() || len > props.getMaxLength()) {
            throw new AuthException(400, "invalid_password",
                    "密码长度需在 " + props.getMinLength() + "–" + props.getMaxLength() + " 之间");
        }
    }
}
