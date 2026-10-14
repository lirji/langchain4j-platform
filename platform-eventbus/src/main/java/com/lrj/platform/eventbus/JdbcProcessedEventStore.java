package com.lrj.platform.eventbus;

import com.lrj.platform.eventbus.mapper.ProcessedEventStoreMapper;
import com.lrj.platform.eventbus.mapper.ProcessedEventStoreRow;
import com.lrj.platform.persistence.PlusMapperFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;

/**
 * JDBC 去重（跨重启）。schema 由独立版本化 migration 管理。
 * 靠 PK 冲突判定重复：插入成功=首次，主键冲突=已处理。
 */
public class JdbcProcessedEventStore implements ProcessedEventStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcProcessedEventStore.class);

    private final ProcessedEventStoreMapper db;

    public JdbcProcessedEventStore(DataSource dataSource) {
        this.db = PlusMapperFactory.create(dataSource, ProcessedEventStoreMapper.class);
        init();
    }

    private void init() {
        db.readInit1();
        log.info("PROCESSED_EVENT schema verified");
    }

    @Override
    public boolean isProcessed(String eventId) {
        return db.selectById(eventId) != null;
    }

    @Override
    public boolean markProcessed(String eventId) {
        if (db.reclaim(eventId, System.currentTimeMillis()) == 1) return true;
        try {
            var event = new ProcessedEventStoreRow();
            event.eventId = eventId; event.processedAt = System.currentTimeMillis();
            db.insert(event);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public void releaseClaim(String eventId) {
        db.deleteById(eventId);
    }
}
