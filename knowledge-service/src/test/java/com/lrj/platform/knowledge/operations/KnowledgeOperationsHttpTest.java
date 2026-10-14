package com.lrj.platform.knowledge.operations;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Set;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

/** MVC契约使用真实持久化应用服务，验证请求字段不能覆盖认证身份及版本前置条件。 */
class KnowledgeOperationsHttpTest {
    @AfterEach void clearIdentity() { TenantContext.clear(); }
    @Test void contractsEnforceActorScopeTenantAndVersions() throws Exception {
        var fixture = new KnowledgeOperationsServiceTest(); fixture.prepare();
        var service = fixture.service; var publication = fixture.publication; var admin = fixture.admin; var draft = fixture.draft;
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("operations", service); beans.registerSingleton("publication", publication);
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeOperationsController(beans.getBeanProvider(KnowledgeOperationsService.class),
                beans.getBeanProvider(KnowledgePublicationService.class), beans.getBeanProvider(KnowledgeProbe.class), new InternalSecurityProperties())).build();
        TenantContext.set(new TenantContext.Tenant(admin.tenantId(), "reader", Set.of("chat")));
        mvc.perform(post("/rag/operations/feedback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"http\",\"question\":\"退款多久\",\"reason\":\"NO_ANSWER\",\"tenantId\":\"spoof\",\"userId\":\"spoof\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(admin.tenantId())).andExpect(jsonPath("$.userId").value("reader"));
        mvc.perform(get("/rag/operations/feedback/inbox")).andExpect(status().isForbidden());
        mvc.perform(get("/rag/operations/candidates")).andExpect(status().isForbidden());
        var c = service.create(admin, new CandidateInput("http", null, draft));
        TenantContext.set(new TenantContext.Tenant("other", "editor", Set.of("ingest")));
        mvc.perform(get("/rag/operations/candidates/" + c.id())).andExpect(status().isNotFound());
        TenantContext.set(admin);
        mvc.perform(post("/rag/operations/candidates/" + c.id() + "/evaluate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        var evaluated = service.evaluate(admin, c.id(), 0); var approved = publication.approve(admin, c.id(), evaluated.version());
        mvc.perform(post("/rag/operations/candidates/" + c.id() + "/publish").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + approved.version() + "}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PUBLISHING"));
    }
}
