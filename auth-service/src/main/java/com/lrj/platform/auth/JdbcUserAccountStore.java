package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JDBC 账号存储（{@code AUTH_STORE=jdbc}）。表结构演进沿用项目约定：{@code CREATE TABLE IF NOT EXISTS}
 * 字面量写在类里，无 Flyway/Liquibase。首次为空时用 {@link SeedUsers} 初始化演示账号。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcUserAccountStore implements UserAccountStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserAccountStore.class);

    private final JdbcTemplate jdbc;

    public JdbcUserAccountStore(DataSource authDataSource, PasswordHasher hasher, AuthProperties props) {
        this.jdbc = new JdbcTemplate(authDataSource);
        init(hasher, props);
    }

    private void init(PasswordHasher hasher, AuthProperties props) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS USERS (
                  USERNAME VARCHAR(128) NOT NULL PRIMARY KEY,
                  PASSWORD_HASH VARCHAR(256) NOT NULL,
                  TENANT VARCHAR(128) NOT NULL,
                  USER_ID VARCHAR(128) NOT NULL,
                  SCOPES VARCHAR(1024),
                  ENABLED BOOLEAN NOT NULL,
                  CREATED_AT BIGINT NOT NULL
                )""");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM USERS", Integer.class);
        if (count == null || count == 0) {
            long now = System.currentTimeMillis();
            for (UserAccount u : SeedUsers.defaults(hasher, props.getDemoPassword())) {
                jdbc.update("""
                        INSERT INTO USERS (USERNAME, PASSWORD_HASH, TENANT, USER_ID, SCOPES, ENABLED, CREATED_AT)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""",
                        u.username(), u.passwordHash(), u.tenant(), u.userId(),
                        String.join(",", u.scopes()), u.enabled(), now);
            }
            log.info("USERS table seeded with demo accounts (jdbc)");
        }
        log.info("USERS table ready");
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        List<UserAccount> rows = jdbc.query(
                "SELECT * FROM USERS WHERE USERNAME=?", this::mapUser,
                username.trim().toLowerCase(Locale.ROOT));
        return rows.stream().findFirst();
    }

    private UserAccount mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
                rs.getString("USERNAME"),
                rs.getString("PASSWORD_HASH"),
                rs.getString("TENANT"),
                rs.getString("USER_ID"),
                parseScopes(rs.getString("SCOPES")),
                rs.getBoolean("ENABLED"));
    }

    private static Set<String> parseScopes(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
