package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC 刷新会话存储（{@code AUTH_STORE=jdbc}）。只存刷新令牌哈希，支持轮转（撤旧建新）。 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcRefreshSessionStore implements RefreshSessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRefreshSessionStore.class);

    private final JdbcTemplate jdbc;

    public JdbcRefreshSessionStore(DataSource authDataSource) {
        this.jdbc = new JdbcTemplate(authDataSource);
        init();
    }

    private void init() {
        jdbc.queryForList("""
                SELECT TOKEN_HASH, USERNAME, CREATED_AT, EXPIRES_AT, REVOKED
                FROM AUTH_SESSION WHERE 1=0""");
        log.info("AUTH_SESSION schema verified");
    }

    @Override
    public void create(RefreshSession session) {
        jdbc.update("""
                INSERT INTO AUTH_SESSION (TOKEN_HASH, USERNAME, CREATED_AT, EXPIRES_AT, REVOKED)
                VALUES (?, ?, ?, ?, ?)""",
                session.tokenHash(),
                session.username(),
                session.createdAt().toEpochMilli(),
                session.expiresAt().toEpochMilli(),
                session.revoked());
    }

    @Override
    public Optional<RefreshSession> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        List<RefreshSession> rows = jdbc.query(
                "SELECT * FROM AUTH_SESSION WHERE TOKEN_HASH=?", this::mapSession, tokenHash);
        return rows.stream().findFirst();
    }

    @Override
    public void revoke(String tokenHash) {
        jdbc.update("UPDATE AUTH_SESSION SET REVOKED=TRUE WHERE TOKEN_HASH=?", tokenHash);
    }

    @Override
    public void revokeByUsername(String username) {
        if (username != null && !username.isBlank()) {
            jdbc.update("UPDATE AUTH_SESSION SET REVOKED=TRUE WHERE USERNAME=? AND REVOKED=FALSE", username);
        }
    }

    @Override
    public void deleteByUsername(String username) {
        if (username != null && !username.isBlank()) {
            jdbc.update("DELETE FROM AUTH_SESSION WHERE USERNAME=?", username);
        }
    }

    private RefreshSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new RefreshSession(
                rs.getString("TOKEN_HASH"),
                rs.getString("USERNAME"),
                Instant.ofEpochMilli(rs.getLong("CREATED_AT")),
                Instant.ofEpochMilli(rs.getLong("EXPIRES_AT")),
                rs.getBoolean("REVOKED"));
    }
}
