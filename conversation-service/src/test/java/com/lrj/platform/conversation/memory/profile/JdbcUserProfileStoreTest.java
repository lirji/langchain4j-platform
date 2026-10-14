package com.lrj.platform.conversation.memory.profile;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import com.lrj.platform.security.TenantContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 同一套存储契约默认跑 H2；专用环境变量可切到隔离 MySQL，验证真实锁与事务语义。 */
class JdbcUserProfileStoreTest {
    private final List<HikariDataSource> pools = new ArrayList<>();
    private String url;
    private String username;
    private String password;
    private String tenant;
    private HikariDataSource source;

    @BeforeEach
    void prepare() {
        url = System.getenv().getOrDefault("PROFILE_TEST_DB_URL",
                "jdbc:h2:mem:profile_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        username = System.getenv().getOrDefault("PROFILE_TEST_DB_USER", "sa");
        password = System.getenv().getOrDefault("PROFILE_TEST_DB_PASSWORD", "");
        tenant = UUID.randomUUID().toString();
        source = pool();
        var migrationSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("PROFILE_TEST_MIGRATION_DB_USER", username),
                System.getenv().getOrDefault("PROFILE_TEST_MIGRATION_DB_PASSWORD", password));
        assertThat(SchemaMigrationRunner.migrate(migrationSource, SchemaName.CONVERSATION).success()).isTrue();
        assertThat(SchemaMigrationRunner.migrate(migrationSource, SchemaName.CONVERSATION).migrationsExecuted()).isZero();
    }

    @AfterEach
    void closePools() {
        TenantContext.clear();
        pools.forEach(HikariDataSource::close);
    }

    @Test
    void survivesClosingAndRecreatingAllConnections() {
        JdbcUserProfileStore first = new JdbcUserProfileStore(source, 50);
        MemoryItem expected = item("id", "偏好邮件联系", 123);
        first.add(tenant, "alice", expected);
        source.close();
        JdbcUserProfileStore restarted = new JdbcUserProfileStore(pool(), 50);
        assertThat(restarted.list(tenant, "alice")).containsExactly(expected);
    }

    @Test
    void preservesNormalizationSubstringDedupAndInsertionOrder() {
        JdbcUserProfileStore store = new JdbcUserProfileStore(source, 2);
        store.add(tenant, "alice", item("1", "Prefers Email", 999));
        store.add(tenant, "alice", item("2", "prefers, email.", 998));
        store.add(tenant, "alice", item("3", "always prefers email", 997));
        assertThat(store.list(tenant, "alice")).hasSize(1);
        store.add(tenant, "alice", item("4", "喜欢咖啡", 2));
        store.add(tenant, "alice", item("5", "住在杭州", 1));
        assertThat(store.list(tenant, "alice")).extracting(MemoryItem::id).containsExactly("4", "5");
    }

    @Test
    void isolatesTenantUserCaseAndSeparatorCharacters() {
        JdbcUserProfileStore store = new JdbcUserProfileStore(source, 50);
        store.add(tenant, "Alice", item("1", "偏好邮件", 0));
        store.add(tenant + "::a", "b", item("2", "喜欢咖啡", 0));
        assertThat(store.list(tenant, "alice")).isEmpty();
        assertThat(store.list(tenant.toUpperCase(java.util.Locale.ROOT), "Alice")).isEmpty();
        assertThat(store.list(tenant, "a::b")).isEmpty();
        assertThat(store.clear(tenant, "alice")).isZero();
        assertThat(store.list(tenant, "Alice")).hasSize(1);
    }

    @Test
    void clearIsDurableAndReturnsOnlyThisOwnersCount() {
        JdbcUserProfileStore store = new JdbcUserProfileStore(source, 50);
        store.add(tenant, "alice", item("1", "偏好邮件", 0));
        store.add(tenant, "bob", item("2", "喜欢咖啡", 0));
        assertThat(store.clear(tenant, "alice")).isEqualTo(1);
        assertThat(new JdbcUserProfileStore(pool(), 50).list(tenant, "alice")).isEmpty();
        assertThat(store.clear(tenant, "alice")).isZero();
        assertThat(store.list(tenant, "bob")).hasSize(1);
    }

    @Test
    void concurrentInstancesDeduplicateAndEnforceCapacity() throws Exception {
        JdbcUserProfileStore first = new JdbcUserProfileStore(source, 5);
        JdbcUserProfileStore second = new JdbcUserProfileStore(pool(), 5);
        runTogether(20, index -> (index % 2 == 0 ? first : second)
                .add(tenant, "alice", item("same", "偏好邮件", index)));
        assertThat(first.list(tenant, "alice")).hasSize(1);
        first.clear(tenant, "alice");
        runTogether(20, index -> (index % 2 == 0 ? first : second)
                .add(tenant, "alice", item("id-" + index, "事实" + String.format("%03d", index), index)));
        assertThat(first.list(tenant, "alice")).hasSize(5);
        assertThat(second.list(tenant, "alice")).isEqualTo(first.list(tenant, "alice"));
    }

    @Test
    void databaseFailureRollsBackWithoutEvictingExistingMemory() {
        JdbcUserProfileStore store = new JdbcUserProfileStore(source, 1);
        store.add(tenant, "alice", item("good", "保留的记忆", 0));
        // 非空列约束在事务内部失败，不能先淘汰旧记录再留下空画像。
        assertThatThrownBy(() -> store.add(tenant, "alice", item(null, "新记忆", 1)))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.list(tenant, "alice")).extracting(MemoryItem::id).containsExactly("good");
    }

    @Test
    void ignoresBlankItemsAndRejectsInvalidLimits() {
        JdbcUserProfileStore store = new JdbcUserProfileStore(source, 50);
        store.add(tenant, "alice", null);
        store.add(tenant, "alice", item("1", "  ", 0));
        assertThat(store.list(tenant, "alice")).isEmpty();
        assertThatThrownBy(() -> new JdbcUserProfileStore(source, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcUserProfileStore(source, 1001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorOnlyVerifiesSchemaAndNeverCreatesTables() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:missing_" + UUID.randomUUID());
        try (HikariDataSource empty = new HikariDataSource(config)) {
            assertThatThrownBy(() -> new JdbcUserProfileStore(empty, 50)).isInstanceOf(RuntimeException.class);
            assertThat(new JdbcTemplate(empty).queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='USER_PROFILE_MEMORY'", Integer.class))
                    .isZero();
        }
    }

    @Test
    void legacyAndJdbcHaveEquivalentObservableStorageBehavior() {
        UserProfileStore legacy = new InMemoryUserProfileStore(3);
        UserProfileStore durable = new JdbcUserProfileStore(source, 3);
        for (String text : List.of("偏好邮件", "偏好邮件联系", "喜欢咖啡", "住在杭州", "使用中文")) {
            MemoryItem value = item(Integer.toHexString(text.hashCode()), text, 123);
            legacy.add(tenant, "alice", value);
            durable.add(tenant, "alice", value);
            assertThat(durable.list(tenant, "alice")).isEqualTo(legacy.list(tenant, "alice"));
        }
        assertThat(durable.clear(tenant, "alice")).isEqualTo(legacy.clear(tenant, "alice"));
    }

    @Test
    void httpProfileContractUsesCallerIdentityAfterStoreReplacement() throws Exception {
        JdbcUserProfileStore store = new JdbcUserProfileStore(source, 50);
        store.add(tenant, "alice", item("1", "偏好邮件", 123));
        store.add(tenant, "bob", item("2", "喜欢咖啡", 123));
        var factory = new StaticListableBeanFactory();
        factory.addBean("profile", new UserProfileService(store,
                mock(ProfileExtractor.class), Runnable::run, 12, false, () -> 123));
        var controller = new MemoryProfileController(factory.getBeanProvider(UserProfileChatService.class),
                factory.getBeanProvider(UserProfileService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        TenantContext.set(new TenantContext.Tenant(tenant, "alice", Set.of("chat")));
        mvc.perform(get("/memory/profile")
                        .param("userId", "bob").param("tenantId", "other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].text").value("偏好邮件"))
                .andExpect(jsonPath("$.tenantId").value(tenant));
        mvc.perform(delete("/memory/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(1));
        assertThat(store.list(tenant, "alice")).isEmpty();
        assertThat(store.list(tenant, "bob")).hasSize(1);
    }

    @Test
    void candidatesRequireApprovalAndKeepProvenanceAcrossRestart() {
        var store = new JdbcUserProfileStore(source, 50);
        long generation = store.generation(tenant, "alice");
        assertThat(store.offer(tenant, "alice", item("candidate", "偏好书面回复", 123), generation)).isTrue();
        assertThat(store.list(tenant, "alice")).isEmpty();
        var row = store.snapshot(tenant, "alice").items().getFirst();
        assertThat(row.state()).isEqualTo("PENDING");
        assertThat(row.sourceChatId()).isEqualTo("chat-1");
        store.decide(tenant, "alice", row.recordId(), new GovernedProfileStore.Decision(
                row.version(), GovernedProfileStore.Action.APPROVE, null, null, null));
        assertThat(new JdbcUserProfileStore(pool(), 50).list(tenant, "alice"))
                .extracting(MemoryItem::text).containsExactly("偏好书面回复");
        assertThat(store.audit(tenant, "bob", Long.MAX_VALUE, 50)).isEmpty();
        assertThat(store.audit(tenant, "alice", Long.MAX_VALUE, 50))
                .extracting(GovernedProfileStore.Audit::action).contains("PROPOSE", "APPROVE");
    }

    @Test
    void clearAndOptOutFenceEarlierObservations() {
        var store = new JdbcUserProfileStore(source, 50);
        long old = store.generation(tenant, "alice");
        store.clear(tenant, "alice");
        assertThat(store.offer(tenant, "alice", item("late", "旧会话事实", 1), old)).isFalse();
        var state = store.snapshot(tenant, "alice");
        store.settings(tenant, "alice", state.generation(), false);
        assertThat(store.generation(tenant, "alice")).isEqualTo(-1);
        assertThat(store.offer(tenant, "alice", item("late", "新会话事实", 1), -1)).isFalse();
        assertThat(store.list(tenant, "alice")).isEmpty();
        assertThatThrownBy(() -> store.settings(tenant, "alice", state.generation(), true))
                .isInstanceOf(MemoryGovernanceException.class);
    }

    @Test
    void governanceUsesVersionsOwnerScopeExpiryAndPinnedCapacity() {
        var store = new JdbcUserProfileStore(source, 1);
        store.add(tenant, "alice", item("first", "人工确认内容", 1));
        var row = store.snapshot(tenant, "alice").items().getFirst();
        assertThatThrownBy(() -> store.decide(tenant, "bob", row.recordId(),
                new GovernedProfileStore.Decision(0L, GovernedProfileStore.Action.FORGET, null, null, null)))
                .isInstanceOf(MemoryGovernanceException.class);
        store.decide(tenant, "alice", row.recordId(), new GovernedProfileStore.Decision(
                row.version(), GovernedProfileStore.Action.PIN, null, null, null));
        store.add(tenant, "alice", item("second", "另外事实", 2));
        assertThat(store.list(tenant, "alice")).extracting(MemoryItem::id).containsExactly("first");
        assertThatThrownBy(() -> store.decide(tenant, "alice", row.recordId(),
                new GovernedProfileStore.Decision(row.version(), GovernedProfileStore.Action.FORGET, null, null, null)))
                .isInstanceOf(MemoryGovernanceException.class);
        store.decide(tenant, "alice", row.recordId(), new GovernedProfileStore.Decision(
                row.version() + 1, GovernedProfileStore.Action.UPDATE, "用户修正后的事实", "preference", 1L));
        assertThat(store.list(tenant, "alice")).isEmpty();
        store.decide(tenant, "alice", row.recordId(), new GovernedProfileStore.Decision(
                row.version() + 2, GovernedProfileStore.Action.FORGET, null, null, null));
        assertThat(store.snapshot(tenant, "alice").items()).isEmpty();
        assertThat(new JdbcTemplate(source).queryForObject(
                "SELECT COUNT(*) FROM USER_PROFILE_MEMORY WHERE RECORD_ID=?", Integer.class, row.recordId())).isZero();
    }

    @Test
    void queuedModelObservationCannotRefillAfterClearOrDisable() {
        var store = new JdbcUserProfileStore(source, 50);
        var queue = new java.util.ArrayList<Runnable>();
        var extractor = mock(ProfileExtractor.class);
        org.mockito.Mockito.when(extractor.extract("消息", "回复"))
                .thenReturn(new ExtractedMemories(List.of(new MemoryFact("仅候选事实", "preference"))));
        var service = new UserProfileService(store, extractor, queue::add, 12, true, () -> 100);
        service.observe(tenant, "alice", "chat", "消息", "回复");
        store.clear(tenant, "alice");
        queue.removeFirst().run();
        assertThat(store.snapshot(tenant, "alice").items()).isEmpty();
        service.observe(tenant, "alice", "chat", "消息", "回复");
        queue.removeFirst().run();
        assertThat(store.snapshot(tenant, "alice").items()).hasSize(1);
        assertThat(service.recall(tenant, "alice")).isEmpty();
        var snapshot = store.snapshot(tenant, "alice");
        store.settings(tenant, "alice", snapshot.generation(), false);
        service.observe(tenant, "alice", "chat", "消息", "回复");
        assertThat(queue).isEmpty();
    }

    @Test
    void generationCapturedBeforeAssistantReplyBlocksLateObservation() {
        var store = new JdbcUserProfileStore(source, 50);
        var extractor = mock(ProfileExtractor.class);
        var service = new UserProfileService(store, extractor, Runnable::run, 12, false, () -> 100);
        long started = service.beginObservation(tenant, "alice");
        store.clear(tenant, "alice");
        org.mockito.Mockito.when(extractor.extract("消息", "回复"))
                .thenReturn(new ExtractedMemories(List.of(new MemoryFact("旧回复的事实", "other"))));
        service.observe(tenant, "alice", "chat", "消息", "回复", started);
        assertThat(store.snapshot(tenant, "alice").items()).isEmpty();
    }

    @Test
    void governanceHttpEnforcesOwnerAndVersionAndReturnsStableErrors() throws Exception {
        var store = new JdbcUserProfileStore(source, 50);
        store.offer(tenant, "alice", item("x", "待确认偏好", 1), store.generation(tenant, "alice"));
        var row = store.snapshot(tenant, "alice").items().getFirst();
        var factory = new StaticListableBeanFactory();
        factory.addBean("profileStore", store);
        var mvc = MockMvcBuilders.standaloneSetup(new MemoryGovernanceController(
                factory.getBeanProvider(UserProfileStore.class))).build();
        TenantContext.set(new TenantContext.Tenant(tenant, "alice", Set.of("chat")));
        mvc.perform(get("/memory/governance").param("userId", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].state").value("PENDING"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/memory/governance/" + row.recordId() + "/decision")
                        .contentType("application/json").content("{\"expectedVersion\":0,\"action\":\"APPROVE\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/memory/governance/" + row.recordId() + "/decision")
                        .contentType("application/json").content("{\"expectedVersion\":0,\"action\":\"FORGET\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("conflict"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/memory/governance/" + row.recordId() + "/decision")
                        .contentType("application/json").content("{\"action\":\"FORGET\"}"))
                .andExpect(status().isBadRequest());
        TenantContext.set(new TenantContext.Tenant(tenant, "bob", Set.of("chat")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/memory/governance/" + row.recordId() + "/decision")
                        .contentType("application/json").content("{\"expectedVersion\":1,\"action\":\"FORGET\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentDecisionsHaveOneWinnerAndOneAudit() throws Exception {
        var first = new JdbcUserProfileStore(source, 50);
        var second = new JdbcUserProfileStore(pool(), 50);
        first.offer(tenant, "alice", item("x", "并发审核候选", 1), first.generation(tenant, "alice"));
        var row = first.snapshot(tenant, "alice").items().getFirst();
        var wins = new java.util.concurrent.atomic.AtomicInteger();
        runTogether(2, i -> {
            try {
                (i == 0 ? first : second).decide(tenant, "alice", row.recordId(),
                        new GovernedProfileStore.Decision(0L, GovernedProfileStore.Action.APPROVE, null, null, null));
                wins.incrementAndGet();
            } catch (MemoryGovernanceException conflict) { assertThat(conflict.code()).isEqualTo("conflict"); }
        });
        assertThat(wins.get()).isEqualTo(1);
        assertThat(first.audit(tenant, "alice", Long.MAX_VALUE, 100).stream()
                .filter(e -> e.action().equals("APPROVE")).count()).isEqualTo(1);
    }

    private HikariDataSource pool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        HikariDataSource result = new HikariDataSource(config);
        pools.add(result);
        return result;
    }

    private static MemoryItem item(String id, String text, long time) {
        return new MemoryItem(id, text, "preference", time, "chat-1");
    }

    private static void runTogether(int count, java.util.function.IntConsumer operation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                results.add(executor.submit(() -> {
                    start.await();
                    operation.accept(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) result.get(30, TimeUnit.SECONDS);
        }
    }
}
