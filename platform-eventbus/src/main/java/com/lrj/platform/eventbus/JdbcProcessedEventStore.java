package com.lrj.platform.eventbus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * JDBC 去重（跨重启）。表结构靠 {@code CREATE TABLE IF NOT EXISTS} 字面量维护（与本仓其它 Jdbc*Store 一致）。
 * 靠 PK 冲突判定重复：插入成功=首次，主键冲突=已处理。
 */
public class JdbcProcessedEventStore implements ProcessedEventStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcProcessedEventStore.class);

    private final JdbcTemplate jdbc;

    public JdbcProcessedEventStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        init();
    }

    private void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS PROCESSED_EVENT (
                  EVENT_ID VARCHAR(128) NOT NULL PRIMARY KEY,
                  PROCESSED_AT BIGINT NOT NULL
                )""");
        log.info("PROCESSED_EVENT table ready");
    }

    @Override
    public boolean isProcessed(String eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PROCESSED_EVENT WHERE EVENT_ID = ?", Integer.class, eventId);
        return n != null && n > 0;
    }

    @Override
    public boolean markProcessed(String eventId) {
        try {
            jdbc.update("INSERT INTO PROCESSED_EVENT (EVENT_ID, PROCESSED_AT) VALUES (?, ?)",
                    eventId, System.currentTimeMillis());
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
