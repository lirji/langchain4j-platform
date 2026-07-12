package com.lrj.platform.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt 密码哈希封装。仅用 {@code spring-security-crypto}，不引入 Spring Security 过滤链。 */
@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** 明文与哈希是否匹配；任一为空返回 false（不抛异常，避免暴露账号是否存在的时序差异）。 */
    public boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || rawPassword.isEmpty() || passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, passwordHash);
    }
}
