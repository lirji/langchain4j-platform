package com.lrj.platform.workflow;

import com.lrj.platform.workflow.mapper.WorkflowReplyStoreMapper;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * {@link WorkflowReplyStore} 的 JDBC 实现：使用 Flowable 的 {@code workflowDataSource}
 * 访问由独立 migration stage 预建的 {@code WF_REPLY} 表。
 *
 * <p><b>事务参与</b>：构造时拿的是 {@code workflowDataSource}，而 {@code save/delete} 用的
 * {@code MyBatis-Plus Mapper} 在有活动事务时会经 {@code DataSourceUtils} 复用 Spring 绑定到该数据源的事务连接——
 * 因 Flowable 由 {@code workflowTransactionManager}（同一数据源的 {@code DataSourceTransactionManager}）
 * 驱动，ServiceTask 同步执行期间写 reply 即与流程推进同事务、原子提交（见接口 javadoc）。
 * 构造时只做 schema contract 校验，不执行 DDL。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class JdbcWorkflowReplyStore implements WorkflowReplyStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowReplyStore.class);

    private final WorkflowReplyStoreMapper db;

    public JdbcWorkflowReplyStore(DataSource workflowDataSource) {
        this.db = PlusMapperFactory.create(workflowDataSource, WorkflowReplyStoreMapper.class);
        init();
    }

    /** 校验独立 migration 已提供当前 schema contract。 */
    private void init() {
        db.readInit1();
        log.info("WF_REPLY schema verified");
    }

    @Override
    public void save(String instanceId, String reply, boolean degraded) {
        // MySQL upsert：同实例重跑/降级覆盖（与 enableDuplicateFiltering 的幂等取向一致）
        db.writeSave1(instanceId, reply, degraded ? 1 : 0, System.currentTimeMillis());
    }

    @Override
    public String find(String instanceId) {
        List<String> rows = Rows.map(db.readFind1(instanceId), (rs, n) -> rs.reply);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void delete(String instanceId) {
        db.writeDelete1(instanceId);
    }
}
