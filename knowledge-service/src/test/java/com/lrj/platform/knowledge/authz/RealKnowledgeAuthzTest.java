package com.lrj.platform.knowledge.authz;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.protocol.ZedTokenView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RealKnowledgeAuthz 单测（mock AuthzEngine，无需真实 server/SpiceDB）。
 * 覆盖：enforce 真过滤、shadow 不拦截、读走 fully-consistent、写含 owner+parent_space。
 */
class RealKnowledgeAuthzTest {

    private static final String TID = "acme";

    @Test
    void enforce_filtersToAllowedSubset() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.checkBulk(any(), eq("view"), anyList(), any())).thenReturn(Map.of(
                ResourceRef.of("document", TID + "_d1"), true,
                ResourceRef.of("document", TID + "_d2"), false));
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.ENFORCE);

        Set<String> readable = authz.filterReadable(TID, "alice", ordered("d1", "d2"));

        assertThat(readable).containsExactly("d1");
    }

    @Test
    void shadow_returnsAllCandidates_evenWhenDenied() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.checkBulk(any(), eq("view"), anyList(), any())).thenReturn(Map.of(
                ResourceRef.of("document", TID + "_d1"), true,
                ResourceRef.of("document", TID + "_d2"), false));
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.SHADOW);

        Set<String> readable = authz.filterReadable(TID, "alice", ordered("d1", "d2"));

        assertThat(readable).as("shadow 不拦截，返回全集").containsExactly("d1", "d2");
    }

    @Test
    void filterReadable_usesFullyConsistent() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.checkBulk(any(), any(), anyList(), any())).thenReturn(Map.of());
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.ENFORCE);

        authz.filterReadable(TID, "alice", ordered("d1"));

        ArgumentCaptor<Consistency> cap = ArgumentCaptor.forClass(Consistency.class);
        verify(engine).checkBulk(any(), eq("view"), anyList(), cap.capture());
        assertThat(cap.getValue().mode()).isEqualTo(Consistency.Mode.FULLY_CONSISTENT);
    }

    @Test
    void onDocumentCreated_writesOwnerAndParentSpace() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("t"));
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.ENFORCE);

        authz.onDocumentCreated(TID, "d1", "alice");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RelationshipUpdate>> cap = ArgumentCaptor.forClass(List.class);
        verify(engine).writeRelationships(cap.capture());
        List<RelationshipUpdate> updates = cap.getValue();
        assertThat(updates).hasSize(2);
        assertThat(updates).anySatisfy(u -> {
            assertThat(u.relation()).isEqualTo("owner");
            assertThat(u.subject()).isEqualTo(SubjectRef.user("alice"));
            assertThat(u.resource()).isEqualTo(ResourceRef.of("document", TID + "_d1"));
        });
        assertThat(updates).anySatisfy(u -> {
            assertThat(u.relation()).isEqualTo("parent_space");
            assertThat(u.subject()).isEqualTo(SubjectRef.of("space", TID + "_default"));
        });
    }

    @Test
    void emptyDocIds_shortCircuits() {
        AuthzEngine engine = mock(AuthzEngine.class);
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.ENFORCE);
        assertThat(authz.filterReadable(TID, "alice", Set.of())).isEmpty();
    }

    @Test
    void modeAndEnabled() {
        AuthzEngine engine = mock(AuthzEngine.class);
        assertThat(new RealKnowledgeAuthz(engine, AuthzMode.SHADOW).mode()).isEqualTo(AuthzMode.SHADOW);
        assertThat(new RealKnowledgeAuthz(engine, AuthzMode.SHADOW).enabled()).isTrue();
        // 单参构造默认 ENFORCE（供集成测试与显式强制场景）
        assertThat(new RealKnowledgeAuthz(engine).mode()).isEqualTo(AuthzMode.ENFORCE);
    }

    @Test
    void checkDocument_enforce_reflectsEngine() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), eq("view"), any(), any())).thenReturn(true);
        when(engine.check(any(), eq("edit"), any(), any())).thenReturn(false);
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.ENFORCE);

        assertThat(authz.checkDocument(TID, "alice", "d1", "view")).isTrue();
        assertThat(authz.checkDocument(TID, "alice", "d1", "edit")).isFalse();
    }

    @Test
    void checkDocument_shadow_alwaysAllows() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenReturn(false); // 引擎拒绝
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.SHADOW);

        assertThat(authz.checkDocument(TID, "bob", "d1", "view")).as("shadow 不拦截").isTrue();
    }

    @Test
    void checkDocument_usesFullyConsistent() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenReturn(true);
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.ENFORCE);

        authz.checkDocument(TID, "alice", "d1", "view");

        ArgumentCaptor<Consistency> cap = ArgumentCaptor.forClass(Consistency.class);
        verify(engine).check(any(), eq("view"), any(), cap.capture());
        assertThat(cap.getValue().mode()).isEqualTo(Consistency.Mode.FULLY_CONSISTENT);
    }

    @Test
    void shadow_recordsWouldDenyMetric() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenReturn(false); // 引擎拒绝
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meter =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.SHADOW, meter);

        boolean result = authz.checkDocument(TID, "bob", "d1", "view");

        assertThat(result).as("shadow 放行").isTrue();
        double denies = meter.get("knowledge.authz.decisions")
                .tags("mode", "shadow", "operation", "single", "permission", "view", "decision", "deny")
                .counter().count();
        assertThat(denies).as("shadow 记 would_deny 指标").isEqualTo(1.0);
    }

    @Test
    void checkDocument_shadow_failOpen_onDependencyError() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenThrow(new RuntimeException("authz down"));
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.SHADOW);
        assertThat(authz.checkDocument(TID, "bob", "d1", "view")).as("shadow 依赖故障→放行(fail-open)").isTrue();
    }

    @Test
    void checkDocument_enforce_failClosed_onDependencyError() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenThrow(new RuntimeException("authz down"));
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.ENFORCE);
        assertThat(authz.checkDocument(TID, "bob", "d1", "view")).as("enforce 依赖故障→拒绝(fail-closed)").isFalse();
    }

    @Test
    void filterReadable_enforce_failClosed_onError() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.checkBulk(any(), any(), anyList(), any())).thenThrow(new RuntimeException("down"));
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.ENFORCE);
        assertThat(authz.filterReadable(TID, "bob", ordered("d1", "d2"))).as("enforce 故障→空集").isEmpty();
    }

    @Test
    void filterReadable_shadow_failOpen_onError() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.checkBulk(any(), any(), anyList(), any())).thenThrow(new RuntimeException("down"));
        RealKnowledgeAuthz authz = new RealKnowledgeAuthz(engine, AuthzMode.SHADOW);
        assertThat(authz.filterReadable(TID, "bob", ordered("d1", "d2"))).as("shadow 故障→全集(不拦截)")
                .containsExactly("d1", "d2");
    }

    private static Set<String> ordered(String... ids) {
        return new LinkedHashSet<>(List.of(ids));
    }
}
