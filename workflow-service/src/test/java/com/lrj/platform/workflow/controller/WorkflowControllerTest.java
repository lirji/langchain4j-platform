package com.lrj.platform.workflow.controller;

import com.lrj.platform.security.TenantContext;
import com.lrj.platform.workflow.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class WorkflowControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tasks_withoutApproveScope_isForbidden() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, controller::tasks);

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void tasks_withApproveScope_delegates() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "approve")));
        WorkflowService service = mock(WorkflowService.class);
        List<WorkflowService.TaskView> tasks = List.of(
                new WorkflowService.TaskView("t1", "Approve", "p1", "HIGH", "refund", null));
        when(service.listTasks()).thenReturn(tasks);
        WorkflowController controller = new WorkflowController(service);

        assertEquals(tasks, controller.tasks());
        verify(service).listTasks();
    }

    @Test
    void startEndpoint_acceptsEdgeFacingRefundStartRequest() throws Exception {
        WorkflowService service = mock(WorkflowService.class);
        when(service.start("u1", "我要退款", "m1", "http://callback.local/workflow"))
                .thenReturn(new WorkflowService.StartResult("p1", "WAITING_APPROVAL", null, "t1", "HIGH", false));

        standaloneSetup(new WorkflowController(service)).build()
                .perform(post("/workflow/refund/start")
                        .contentType("application/json")
                        .content("""
                                {
                                  "chatId": "u1",
                                  "message": "我要退款",
                                  "dedupeId": "m1",
                                  "webhookUrl": "http://callback.local/workflow"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value("p1"))
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.taskId").value("t1"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.deduplicated").value(false));

        verify(service).start("u1", "我要退款", "m1", "http://callback.local/workflow");
    }

    @Test
    void tasksEndpoint_requiresApproveScopeAndReturnsTasks() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "approve")));
        WorkflowService service = mock(WorkflowService.class);
        when(service.listTasks()).thenReturn(List.of(
                new WorkflowService.TaskView("t1", "Approve", "p1", "HIGH", "refund", "alice")));

        standaloneSetup(new WorkflowController(service)).build()
                .perform(get("/workflow/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value("t1"))
                .andExpect(jsonPath("$[0].name").value("Approve"))
                .andExpect(jsonPath("$[0].instanceId").value("p1"))
                .andExpect(jsonPath("$[0].priority").value("HIGH"))
                .andExpect(jsonPath("$[0].summary").value("refund"))
                .andExpect(jsonPath("$[0].assignee").value("alice"));

        verify(service).listTasks();
    }
}
