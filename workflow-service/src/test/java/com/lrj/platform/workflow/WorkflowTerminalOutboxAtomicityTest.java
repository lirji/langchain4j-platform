package com.lrj.platform.workflow;

import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A2：B1b 原子性端到端集成测试（{@code @Tag("flowable-it")}，默认 {@code mvn test} 不加载，
 * 仅 {@code mvn -Pflowable-it -pl workflow-service test} 运行）。
 *
 * <p>用真实嵌入式 Flowable 引擎（H2）+ 最小 start→end 流程，验证 {@link WorkflowTerminalOutboxListener}
 * 在 {@code end} 事件写 {@link WorkflowTerminalEventOutbox} 的动作确实与引擎终态在<b>同一事务</b>：
 * <ul>
 *   <li>happy：流程正常结束 → 事件 outbox 行存在（原子写成功）；</li>
 *   <li>rollback：end 上 outbox 监听器之后再挂一个抛异常的监听器 → 整个命令回滚 →
 *       事件 outbox 行与历史实例<b>一起没</b>（证明 INSERT 并入了引擎事务，非两段式）。</li>
 * </ul>
 */
@Tag("flowable-it")
class WorkflowTerminalOutboxAtomicityTest {

    private ProcessEngine engine;
    private RuntimeService runtime;
    private HistoryService history;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:atomicity-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        WorkflowTerminalEventOutbox outbox = new WorkflowTerminalEventOutbox(ds); // 建表（自动提交，独立于流程事务）
        WorkflowProperties props = new WorkflowProperties();
        props.getTerminalNotification().setMode("kafka");
        WorkflowTerminalOutboxListener outboxListener = new WorkflowTerminalOutboxListener(outbox, props);
        ExecutionListener boom = execution -> { throw new RuntimeException("boom rollback"); };

        SpringProcessEngineConfiguration cfg = new SpringProcessEngineConfiguration();
        cfg.setDataSource(ds);
        cfg.setTransactionManager(new DataSourceTransactionManager(ds));
        cfg.setDatabaseSchemaUpdate("true");
        // H2 用 MODE=MySQL（本仓 outbox 用 ON DUPLICATE KEY 等 MySQL 语法）；显式让 Flowable 也发 MySQL DDL，
        // 否则 Flowable 按检测到的 H2 发 IDENTITY 等 H2 DDL，被 MySQL 模式的 H2 拒绝。生产即 MySQL，故一致。
        cfg.setDatabaseType("mysql");
        cfg.setAsyncExecutorActivate(false); // ServiceTask/监听器同步跑，与生产一致
        Map<Object, Object> beans = new HashMap<>();
        beans.put("workflowTerminalOutboxListener", outboxListener);
        beans.put("boom", boom);
        cfg.setBeans(beans);

        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment()
                .addClasspathResource("processes/atomicity-test.bpmn20.xml").deploy();
        runtime = engine.getRuntimeService();
        history = engine.getHistoryService();
        jdbc = new JdbcTemplate(ds);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void happyPath_writesEventOutboxRowAtomicallyWithTerminal() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("tenantId", "acme");
        vars.put("chatId", "u1");
        vars.put("terminalOutcome", "granted");
        vars.put("webhookUrl", "http://cb/hook");

        ProcessInstance pi = runtime.startProcessInstanceByKey("atomicityHappy", vars);

        assertThat(pi.isEnded()).isTrue();
        assertThat(countOutbox()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT TENANT_ID FROM WF_TERMINAL_EVENT_OUTBOX", String.class)).isEqualTo("acme");
        assertThat(jdbc.queryForObject("SELECT OUTCOME FROM WF_TERMINAL_EVENT_OUTBOX", String.class)).isEqualTo("granted");
        assertThat(jdbc.queryForObject("SELECT CHAT_ID FROM WF_TERMINAL_EVENT_OUTBOX", String.class)).isEqualTo("u1");
    }

    @Test
    void rollback_outboxRowAndProcessBothRolledBackTogether() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("tenantId", "acme");
        vars.put("terminalOutcome", "granted");

        assertThatThrownBy(() -> runtime.startProcessInstanceByKey("atomicityRollback", vars))
                .isInstanceOf(Exception.class);

        // 原子性：outbox 行未写，历史实例也未提交（两者随同一命令一起回滚）
        assertThat(countOutbox()).isZero();
        assertThat(history.createHistoricProcessInstanceQuery()
                .processDefinitionKey("atomicityRollback").count()).isZero();
    }

    private int countOutbox() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM WF_TERMINAL_EVENT_OUTBOX", Integer.class);
        return n == null ? 0 : n;
    }
}
