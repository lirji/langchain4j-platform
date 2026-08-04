package com.lrj.platform.workflow;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.security.OutboundCallbackPolicy;
import com.lrj.platform.security.TenantContext;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 用真实 H2/MySQL-mode + Flowable 验证退款幂等账本的数据库并发与事务边界。
 * 默认测试套件排除；{@code -Pflowable-it} 单独运行。
 */
@Tag("flowable-it")
class WorkflowIdempotencyAtomicityTest {

    private ProcessEngine engine;
    private WorkflowService service;
    private ControllableDelegates delegates;
    private CountingStore store;
    private JdbcTemplate jdbc;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        DataSource dataSource = WorkflowTestDatabase.migrated(
                "workflow-idem-" + System.nanoTime(), ";LOCK_TIMEOUT=10000");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        delegates = new ControllableDelegates();

        SpringProcessEngineConfiguration config = new SpringProcessEngineConfiguration();
        config.setDataSource(dataSource);
        config.setTransactionManager(transactionManager);
        config.setDatabaseSchemaUpdate("false");
        config.setDatabaseType("mysql");
        config.setHistory("audit");
        config.setAsyncExecutorActivate(false);
        Map<Object, Object> beans = new HashMap<>();
        beans.put("serviceTaskDelegates", delegates);
        beans.put("workflowTerminalOutboxListener", (ExecutionListener) execution -> { });
        config.setBeans(beans);

        engine = config.buildProcessEngine();
        engine.getRepositoryService().createDeployment()
                .addClasspathResource("processes/refund-approval.bpmn20.xml")
                .deploy();

        JdbcWorkflowIdempotencyStore jdbcStore = new JdbcWorkflowIdempotencyStore(dataSource);
        store = new CountingStore(jdbcStore);
        jdbc = new JdbcTemplate(dataSource);
        WorkflowProperties properties = new WorkflowProperties();
        RuntimeService runtimeService = engine.getRuntimeService();
        service = new WorkflowService(
                runtimeService,
                engine.getTaskService(),
                engine.getHistoryService(),
                mock(AuditLogger.class),
                properties,
                mock(WorkflowReplyStore.class),
                mock(WorkflowMetrics.class),
                new WorkflowOutbox(dataSource),
                mock(WorkflowAsyncTaskNotifier.class),
                new WorkflowTerminalEventOutbox(dataSource),
                mock(ApplicationEventPublisher.class),
                mock(OutboundCallbackPolicy.class),
                store,
                transactionManager);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void concurrentSameRequestCreatesExactlyOneProcess() throws Exception {
        delegates.blockFirstAssess();

        Future<WorkflowService.StartResult> first = executor.submit(
                () -> start("acme", "alice", "chat-1", "退款订单 101", "refund-101"));
        assertThat(delegates.awaitFirstAssess()).isTrue();

        Future<WorkflowService.StartResult> second = executor.submit(
                () -> start("acme", "alice", "chat-1", "退款订单 101", "refund-101"));
        assertThat(store.awaitTwoClaimAttempts()).isTrue();
        assertThat(second.isDone()).isFalse();

        delegates.releaseFirstAssess();
        WorkflowService.StartResult firstResult = get(first);
        WorkflowService.StartResult secondResult = get(second);

        assertThat(firstResult.instanceId()).isEqualTo(secondResult.instanceId());
        assertThat(Set.of(firstResult.deduplicated(), secondResult.deduplicated()))
                .containsExactlyInAnyOrder(false, true);
        assertThat(processCount()).isOne();
        assertThat(ledgerCount()).isOne();
    }

    @Test
    void sameKeyWithChangedArgumentsReturnsConflictWithoutSecondProcess() {
        start("acme", "alice", "chat-1", "退款订单 101", "refund-101");

        assertThatThrownBy(() -> start("acme", "alice", "chat-1", "篡改后的退款请求", "refund-101"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(processCount()).isOne();
        assertThat(ledgerCount()).isOne();
    }

    @Test
    void sameKeyIsIndependentAcrossTenants() {
        WorkflowService.StartResult tenantA = start(
                "tenant-a", "alice", "chat-1", "退款订单 101", "refund-101");
        WorkflowService.StartResult tenantB = start(
                "tenant-b", "bob", "chat-1", "退款订单 101", "refund-101");

        assertThat(tenantA.instanceId()).isNotEqualTo(tenantB.instanceId());
        assertThat(processCount()).isEqualTo(2);
        assertThat(ledgerCount()).isEqualTo(2);
    }

    @Test
    void workflowFailureRollsBackClaimAndAllowsRetry() {
        delegates.failAssess = true;
        assertThatThrownBy(() -> start("acme", "alice", "chat-1", "退款订单 101", "refund-101"))
                .isInstanceOf(RuntimeException.class);
        assertThat(processCount()).isZero();
        assertThat(ledgerCount()).isZero();

        delegates.failAssess = false;
        WorkflowService.StartResult retried = start(
                "acme", "alice", "chat-1", "退款订单 101", "refund-101");
        assertThat(retried.deduplicated()).isFalse();
        assertThat(processCount()).isOne();
        assertThat(ledgerCount()).isOne();
    }

    @Test
    void purgeRemovesWorkflowAndLedgerSoKeyCanBeReused() {
        WorkflowService.StartResult original = start(
                "acme", "alice", "chat-1", "退款订单 101", "refund-101");

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("approve")));
        try {
            assertThat(service.purge("chat-1")).isOne();
        } finally {
            TenantContext.clear();
        }
        assertThat(processCount()).isZero();
        assertThat(ledgerCount()).isZero();

        WorkflowService.StartResult recreated = start(
                "acme", "alice", "chat-1", "退款订单 101", "refund-101");
        assertThat(recreated.instanceId()).isNotEqualTo(original.instanceId());
        assertThat(recreated.deduplicated()).isFalse();
        assertThat(processCount()).isOne();
        assertThat(ledgerCount()).isOne();
    }

    @Test
    void firstRequestAfterUpgradeAdoptsLegacyBusinessKey() {
        String legacyId = createLegacyInstance("退款订单 101");

        WorkflowService.StartResult adopted = start(
                "acme", "alice", "chat-1", "退款订单 101", "refund-101");

        assertThat(adopted.instanceId()).isEqualTo(legacyId);
        assertThat(adopted.deduplicated()).isTrue();
        assertThat(processCount()).isOne();
        assertThat(ledgerCount()).isOne();
    }

    @Test
    void firstRequestAfterUpgradeRejectsChangedLegacyArguments() {
        createLegacyInstance("原始退款请求");

        assertThatThrownBy(() -> start(
                "acme", "alice", "chat-1", "篡改后的退款请求", "refund-101"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(processCount()).isOne();
        assertThat(ledgerCount()).isZero();
    }

    private WorkflowService.StartResult start(String tenantId,
                                               String userId,
                                               String chatId,
                                               String message,
                                               String dedupeId) {
        TenantContext.set(new TenantContext.Tenant(tenantId, userId, Set.of("chat")));
        try {
            return service.start(chatId, message, dedupeId, null);
        } finally {
            TenantContext.clear();
        }
    }

    private String createLegacyInstance(String message) {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        try {
            String businessKey = WorkflowService.buildBusinessKey("acme", "chat-1", "refund-101");
            Map<String, Object> variables = new HashMap<>();
            variables.put("tenantId", "acme");
            variables.put("userId", "alice");
            variables.put("chatId", "chat-1");
            variables.put("message", message);
            variables.put("terminalOutcome", "auto");
            return engine.getRuntimeService()
                    .startProcessInstanceByKey("refundApproval", businessKey, variables)
                    .getId();
        } finally {
            TenantContext.clear();
        }
    }

    private WorkflowService.StartResult get(Future<WorkflowService.StartResult> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw error;
        }
    }

    private long processCount() {
        return engine.getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey("refundApproval").count();
    }

    private int ledgerCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM WF_IDEMPOTENCY", Integer.class);
        return count == null ? 0 : count;
    }

    public static final class ControllableDelegates {
        private final AtomicInteger assessCalls = new AtomicInteger();
        private volatile CountDownLatch firstAssessEntered = new CountDownLatch(0);
        private volatile CountDownLatch releaseFirstAssess = new CountDownLatch(0);
        volatile boolean failAssess;

        void blockFirstAssess() {
            firstAssessEntered = new CountDownLatch(1);
            releaseFirstAssess = new CountDownLatch(1);
        }

        boolean awaitFirstAssess() throws InterruptedException {
            return firstAssessEntered.await(5, TimeUnit.SECONDS);
        }

        void releaseFirstAssess() {
            releaseFirstAssess.countDown();
        }

        public void assess(DelegateExecution execution) {
            int call = assessCalls.incrementAndGet();
            if (failAssess) {
                throw new IllegalStateException("simulated assess failure");
            }
            if (call == 1 && firstAssessEntered.getCount() > 0) {
                firstAssessEntered.countDown();
                try {
                    if (!releaseFirstAssess.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted", interrupted);
                }
            }
            execution.setVariable("priority", "HIGH");
            execution.setVariable("category", "refund");
            execution.setVariable("summary", "refund request");
        }

        public void resolve(DelegateExecution execution) { }

        public void reject(DelegateExecution execution) { }
    }

    private static final class CountingStore implements WorkflowIdempotencyStore {
        private final WorkflowIdempotencyStore delegate;
        private final CountDownLatch claimAttempts = new CountDownLatch(2);

        private CountingStore(WorkflowIdempotencyStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Claim claim(String tenantId,
                           String operation,
                           String keyHash,
                           String requestHash,
                           String businessKey) {
            claimAttempts.countDown();
            return delegate.claim(tenantId, operation, keyHash, requestHash, businessKey);
        }

        @Override
        public void attachInstance(String tenantId,
                                   String operation,
                                   String keyHash,
                                   String requestHash,
                                   String instanceId) {
            delegate.attachInstance(tenantId, operation, keyHash, requestHash, instanceId);
        }

        @Override
        public void deleteByInstance(String instanceId) {
            delegate.deleteByInstance(instanceId);
        }

        boolean awaitTwoClaimAttempts() throws InterruptedException {
            return claimAttempts.await(5, TimeUnit.SECONDS);
        }
    }
}
