package com.lrj.platform.analytics.controller;

import com.lrj.platform.analytics.GuardedSqlExecutor;
import com.lrj.platform.protocol.analytics.AnalyticsSqlPlanReply;
import com.lrj.platform.protocol.analytics.AnalyticsSqlPlanRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AnalyticsPlanControllerTest {

    @Test
    void executesLanguageNeutralPlanWithoutTenantInBody() throws Exception {
        GuardedSqlExecutor executor = mock(GuardedSqlExecutor.class);
        when(executor.execute("订单数", "SELECT count(*) FROM orders"))
                .thenReturn(new AnalyticsSqlPlanReply(
                        "订单数", "SELECT count(*) FROM orders LIMIT 100",
                        1, List.of(Map.of("count", 3)), true, null));

        standaloneSetup(new AnalyticsPlanController(executor)).build()
                .perform(post("/analytics/sql/plans/execute")
                        .contentType("application/json")
                        .content("""
                                {
                                  "question":"订单数",
                                  "sql":"SELECT count(*) FROM orders"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(true))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.rows[0].count").value(3));

        verify(executor).execute("订单数", "SELECT count(*) FROM orders");
    }

    @Test
    void rejectsBlankPlan() throws Exception {
        standaloneSetup(new AnalyticsPlanController(mock(GuardedSqlExecutor.class))).build()
                .perform(post("/analytics/sql/plans/execute")
                        .contentType("application/json")
                        .content("{\"question\":\" \",\"sql\":\" \"}"))
                .andExpect(status().isBadRequest());
    }
}
