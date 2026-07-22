package com.lrj.platform.analytics.controller;

import com.lrj.platform.analytics.NlToSqlService;
import com.lrj.platform.protocol.analytics.AnalyticsSqlReply;
import com.lrj.platform.protocol.analytics.AnalyticsSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * AnalyticsControllerTest：验证 {@link AnalyticsController} 把问题委托给 {@link NlToSqlService}——
 * 透传 {@link AnalyticsSqlReply}、缺失/空请求时兜底为空串，并保留 {@code /chat/sql}（兼容路由）与
 * {@code /analytics/sql}（服务原生路由）两个端点。
 */
class AnalyticsControllerTest {

    @Test
    void chatSql_delegatesQuestionToService() {
        NlToSqlService service = mock(NlToSqlService.class);
        AnalyticsSqlReply result = new AnalyticsSqlReply(
                "退款总额", "SELECT 1", 1, List.of(Map.of("total", 1)), "1", false);
        when(service.ask("退款总额")).thenReturn(result);
        AnalyticsController controller = new AnalyticsController(service);

        assertEquals(result, controller.chatSql(new AnalyticsSqlRequest("退款总额")));
        verify(service).ask("退款总额");
    }

    @Test
    void chatSql_rejectsMissingQuestionBeforeCallingModel() {
        NlToSqlService service = mock(NlToSqlService.class);
        AnalyticsController controller = new AnalyticsController(service);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.chatSql(new AnalyticsSqlRequest("   ")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.chatSql(null));
        verify(service, never()).ask(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void chatSqlEndpoint_returns400ForBlankQuestion() throws Exception {
        NlToSqlService service = mock(NlToSqlService.class);

        standaloneSetup(new AnalyticsController(service)).build()
                .perform(post("/analytics/sql")
                        .contentType("application/json")
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).ask(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void chatSqlEndpoint_keepsLegacyRoute() throws Exception {
        NlToSqlService service = mock(NlToSqlService.class);
        when(service.ask("退款总额")).thenReturn(new AnalyticsSqlReply(
                "退款总额", "SELECT 1", 1, List.of(Map.of("total", 1)), "1", false));

        standaloneSetup(new AnalyticsController(service)).build()
                .perform(post("/chat/sql")
                        .contentType("application/json")
                        .content("{\"question\":\"退款总额\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("退款总额"))
                .andExpect(jsonPath("$.sql").value("SELECT 1"))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.answer").value("1"))
                .andExpect(jsonPath("$.guardBlocked").value(false));

        verify(service).ask("退款总额");
    }

    @Test
    void analyticsSqlEndpoint_keepsServiceNativeRoute() throws Exception {
        NlToSqlService service = mock(NlToSqlService.class);
        when(service.ask("订单数量")).thenReturn(new AnalyticsSqlReply(
                "订单数量", "SELECT COUNT(*)", 1, List.of(Map.of("count", 3)), "3", false));

        standaloneSetup(new AnalyticsController(service)).build()
                .perform(post("/analytics/sql")
                        .contentType("application/json")
                        .content("{\"question\":\"订单数量\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("订单数量"))
                .andExpect(jsonPath("$.sql").value("SELECT COUNT(*)"))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.answer").value("3"));

        verify(service).ask("订单数量");
    }
}
