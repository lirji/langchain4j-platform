package com.lrj.platform.knowledge.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

/** 运营数据必须由真实关系库持久化，幂等和版本竞争不能以Mock证明。 */
class KnowledgeOperationsStoreTest {
    private final javax.sql.DataSource source = OperationsTestDatabase.create();
    @Test
    void feedbackIsDurableIdempotentAndIsolatedByTenantAndUser() {
        var store = new JdbcKnowledgeOperationsStore(source, new ObjectMapper());
        var input = new FeedbackInput("request-1", "退款多久", Reason.NO_ANSWER, "没有找到政策", null);
        var first = store.feedback("tenant", "alice", input, 1);
        assertThat(store.feedback("tenant", "alice", input, 2).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> store.feedback("tenant", "alice",
                new FeedbackInput("request-1", "另一问题", Reason.NO_ANSWER, "", null), 3)).isInstanceOf(OperationsException.class);
        var restarted = new JdbcKnowledgeOperationsStore(source, new ObjectMapper());
        assertThat(restarted.feedbacks("tenant", "alice", "~", 20)).hasSize(1);
        assertThat(restarted.feedbacks("tenant", "alice", first.id(), 20)).isEmpty();
        assertThat(restarted.feedbacks("Tenant", "alice", "~", 20)).isEmpty();
        assertThat(restarted.feedbacks("tenant", "bob", "~", 20)).isEmpty();
    }
    @Test
    void candidateUpdatesUseVersionAndAuditInSameTransaction() {
        var store = new JdbcKnowledgeOperationsStore(source, new ObjectMapper());
        var draft = new Draft("退款政策", "三天内退款", null, List.of(new Check("退款多久", "三天")));
        var candidate = Candidate.create("tenant", "alice", null, null, "doc-1", draft, 100);
        var created = store.create(candidate, "create-1", "fingerprint");
        var updated = created.change(draft, Status.DRAFT, null, null, null, "alice", 200);
        store.save(updated, created.version(), "EDIT");
        assertThatThrownBy(() -> store.save(updated, created.version(), "EDIT")).isInstanceOf(OperationsException.class);
        assertThat(store.get("other", created.id())).isEmpty();
        assertThat(store.list("tenant", "~", 20)).extracting(Candidate::id).contains(created.id());
        assertThat(store.list("tenant", created.id(), 20)).isEmpty();
        assertThat(store.audit("tenant", created.id(), 20)).hasSize(2);
        assertThat(store.get("tenant", created.id()).orElseThrow().version()).isEqualTo(1);
    }
    @Test void releaseSlotIsUniqueAndRolledBackWhenCandidateUpdateFails() {
        var store = new JdbcKnowledgeOperationsStore(source, new ObjectMapper());
        var draft = new Draft("policy", "refund", null, List.of(new Check("refund", "refund")));
        String tenant = "release-" + java.util.UUID.randomUUID();
        var first = store.create(Candidate.create(tenant, "alice", null, null, "doc", draft, 10), "first", "one");
        var second = store.create(Candidate.create(tenant, "alice", null, null, "doc", draft, 20), "second", "two");
        var release = new Release(1, "alice", "alice", null);
        var firstClaim = first.change(draft, Status.PUBLISHING, null, release, null, "alice", 30);
        // 版本校验失败必须撤销先插入的发布槽，不能永久占住文档版本。
        assertThatThrownBy(() -> store.claim(firstClaim, 99)).isInstanceOf(OperationsException.class);
        var secondClaim = second.change(draft, Status.PUBLISHING, null, release, null, "alice", 40);
        assertThat(store.claim(secondClaim, 0).id()).isEqualTo(second.id());
        assertThatThrownBy(() -> store.claim(firstClaim, 0)).isInstanceOf(OperationsException.class);
        assertThat(store.get(tenant, first.id()).orElseThrow().status()).isEqualTo(Status.DRAFT);
        assertThat(store.audit(tenant, first.id(), 20)).hasSize(1);
    }

}
