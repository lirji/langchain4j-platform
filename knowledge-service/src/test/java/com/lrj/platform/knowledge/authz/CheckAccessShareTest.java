package com.lrj.platform.knowledge.authz;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.sdk.AccessDeniedException;
import com.lrj.authz.sdk.CheckAccessAspect;
import com.lrj.authz.sdk.SubjectResolver;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@code @CheckAccess} 声明式判权在 Spring AOP 下<strong>真生效</strong>（窄 slice，不加载整个 app）：
 * 手动装配 {@link CheckAccessAspect} + {@link SubjectResolver} + {@link KnowledgeAccessApplicationService} +
 * mock {@link AuthzEngine}/{@link KnowledgeAuthz}。证明 aspectjweaver 在 classpath、@annotation pointcut 拦截、
 * edit 判权按引擎结果放行/抛 {@link AccessDeniedException}。
 */
@SpringJUnitConfig(CheckAccessShareTest.Cfg.class)
class CheckAccessShareTest {

    private static final String TID = "acme";

    @Configuration
    @EnableAspectJAutoProxy
    static class Cfg {
        static final AuthzEngine engine = mock(AuthzEngine.class);
        static final KnowledgeAuthz knowledgeAuthz = mock(KnowledgeAuthz.class);

        @Bean
        AuthzEngine engine() {
            return engine;
        }

        @Bean
        KnowledgeAuthz knowledgeAuthz() {
            return knowledgeAuthz;
        }

        @Bean
        SubjectResolver subjectResolver() {
            return () -> SubjectRef.user(TenantContext.current().userId());
        }

        @Bean
        CheckAccessAspect checkAccessAspect(AuthzEngine e, SubjectResolver r) {
            return new CheckAccessAspect(e, r);
        }

        @Bean
        KnowledgeAccessApplicationService access(KnowledgeAuthz ka) {
            return new KnowledgeAccessApplicationService(ka);
        }
    }

    @Autowired
    KnowledgeAccessApplicationService access;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(Cfg.engine, Cfg.knowledgeAuthz);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void share_denied_whenCallerHasNoEdit() {
        TenantContext.set(new TenantContext.Tenant(TID, "mallory", Set.of()));
        when(Cfg.engine.check(any(), eq("edit"), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> access.shareDocument(TID + "_d1", "d1", "bob"))
                .isInstanceOf(AccessDeniedException.class);
        verify(Cfg.knowledgeAuthz, never()).grantDocumentViewer(any(), any(), any());
    }

    @Test
    void share_allowed_whenCallerHasEdit() {
        TenantContext.set(new TenantContext.Tenant(TID, "alice", Set.of()));
        when(Cfg.engine.check(any(), eq("edit"), any(), any())).thenReturn(true);

        access.shareDocument(TID + "_d1", "d1", "bob");

        verify(Cfg.knowledgeAuthz).grantDocumentViewer(TID, "d1", "bob");
    }

    @Test
    void share_passesCorrectSubjectResourceAndFullyConsistent() {
        TenantContext.set(new TenantContext.Tenant(TID, "alice", Set.of()));
        when(Cfg.engine.check(any(), eq("edit"), any(), any())).thenReturn(true);

        access.shareDocument(TID + "_d1", "d1", "bob");

        ArgumentCaptor<SubjectRef> subject = ArgumentCaptor.forClass(SubjectRef.class);
        ArgumentCaptor<ResourceRef> resource = ArgumentCaptor.forClass(ResourceRef.class);
        ArgumentCaptor<Consistency> consistency = ArgumentCaptor.forClass(Consistency.class);
        verify(Cfg.engine).check(subject.capture(), eq("edit"), resource.capture(), consistency.capture());
        assertThat(subject.getValue()).as("主体=当前用户").isEqualTo(SubjectRef.user("alice"));
        assertThat(resource.getValue()).as("资源=完整 document id").isEqualTo(ResourceRef.of("document", TID + "_d1"));
        assertThat(consistency.getValue().mode()).as("敏感授权变更用强一致")
                .isEqualTo(Consistency.Mode.FULLY_CONSISTENT);
    }
}
