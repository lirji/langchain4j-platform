package com.lrj.platform.eventbus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * JDBC 去重（跨重启）。schema 由独立版本化 migration 管理。
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
        jdbc.queryForList("SELECT EVENT_ID, PROCESSED_AT FROM PROCESSED_EVENT WHERE 1=0");
        log.info("PROCESSED_EVENT schema verified");
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
