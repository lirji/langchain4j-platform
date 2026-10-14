package com.lrj.platform.knowledge.ingest.sync;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeSyncControllerTest {
    private final KnowledgeSyncService service = mock(KnowledgeSyncService.class);
    private final KnowledgeSyncProperties.Source source = SyncTestSupport.source("tenant");
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new KnowledgeSyncController(
            new KnowledgeSyncProperties(true, List.of(source)), service)).build();
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void requiresScopeAndDoesNotExposeOtherUsersOrTenants() throws Exception {
        mvc.perform(get("/rag/ingestions/sources")).andExpect(status().isForbidden());
        TenantContext.set(new TenantContext.Tenant("other", "sync-user", Set.of("ingest")));
        mvc.perform(get("/rag/ingestions/sources/faq")).andExpect(status().isNotFound());
        TenantContext.set(new TenantContext.Tenant("tenant", "other-user", Set.of("ingest")));
        mvc.perform(post("/rag/ingestions/sources/faq/poll")).andExpect(status().isNotFound());
        mvc.perform(get("/rag/ingestions/sources/faq/document").param("externalId", "faq1")).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
    @Test void exposesStatusAndPollForBoundIdentityOnly() throws Exception {
        TenantContext.set(new TenantContext.Tenant("tenant", "sync-user", Set.of("ingest")));
        when(service.status(source)).thenReturn(new com.lrj.platform.protocol.knowledge.KnowledgeSyncOperations.Status("faq", "IDLE", null, 0, null, 0, false, 0, null, null, "sync-user"));
        var current = service.status(source);
        when(service.trigger(source, false)).thenReturn(current);
        mvc.perform(get("/rag/ingestions/sources")).andExpect(status().isOk()).andExpect(jsonPath("$[0].sourceId").value("faq"));
        mvc.perform(post("/rag/ingestions/sources/faq/poll")).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("IDLE"));
        verify(service).trigger(source, false);
        when(service.document(source, "faq1")).thenReturn(new SyncData.DocumentState("faq", "faq1", 2, "doc", 1, true));
        mvc.perform(get("/rag/ingestions/sources/faq/document").param("externalId", "faq1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true)).andExpect(jsonPath("$.documentId").value("doc"));
    }
    @Test void delegatedOperatorsAreExplicitSameTenantAndStillNeedIngestScope() throws Exception {
        var configured = new KnowledgeSyncProperties.Source(source.id(), source.tenantId(), source.userId(), source.department(), source.endpoint(), source.bearerToken(), false, List.of("operator"));
        var delegated = MockMvcBuilders.standaloneSetup(new KnowledgeSyncController(new KnowledgeSyncProperties(true, List.of(configured)), service)).build();
        TenantContext.set(new TenantContext.Tenant("tenant", "operator", Set.of()));
        delegated.perform(post("/rag/ingestions/sources/faq/retry")).andExpect(status().isForbidden());
        TenantContext.set(new TenantContext.Tenant("other", "operator", Set.of("ingest")));
        delegated.perform(get("/rag/ingestions/sources/faq/runs")).andExpect(status().isNotFound());
        TenantContext.set(new TenantContext.Tenant("tenant", "operator", Set.of("ingest")));
        delegated.perform(post("/rag/ingestions/sources/faq/pause").contentType("application/json").content("{\"expectedRevision\":3}")).andExpect(status().isOk());
        verify(service).control(configured, 3, true);
        delegated.perform(post("/rag/ingestions/sources/faq/pause").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        delegated.perform(post("/rag/ingestions/sources/faq/pause").contentType("application/json").content("{\"expectedRevision\":-1}")).andExpect(status().isBadRequest());
        when(service.control(configured, 3, false)).thenThrow(new SyncFailure("CONTROL_CONFLICT"));
        delegated.perform(post("/rag/ingestions/sources/faq/resume").contentType("application/json").content("{\"expectedRevision\":3}")).andExpect(status().isConflict());
        delegated.perform(get("/rag/ingestions/sources/faq/runs/absent")).andExpect(status().isNotFound());
    }
    @Test void unavailableSourceReturnsSafeErrorCode() throws Exception {
        TenantContext.set(new TenantContext.Tenant("tenant", "sync-user", Set.of("ingest")));
        doThrow(new SyncFailure("SOURCE_CONFIG_CHANGED")).when(service).trigger(source, false);
        mvc.perform(post("/rag/ingestions/sources/faq/poll")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_CONFIG_CHANGED"));
    }
}
