package com.lrj.platform.agent.client;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import org.springframework.http.HttpStatus;

class HttpAnalyticsClientTest {

    @Test
    void ask_postsTypedRequestAndParsesTypedReply() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/analytics/sql"))
                .andExpect(method(POST))
                .andExpect(content().contentType(APPLICATION_JSON))
                .andExpect(jsonPath("$.question").value("订单数"))
                .andRespond(withSuccess(
                        "{\"question\":\"订单数\",\"sql\":\"select count(*) from orders\",\"rowCount\":1,"
                                + "\"rows\":[{\"count\":7}],\"answer\":\"一共 7 单。\",\"guardBlocked\":false}",
                        APPLICATION_JSON));

        AnalyticsClient.Result result = new HttpAnalyticsClient(restTemplate).ask("订单数");

        server.verify();
        assertThat(result.question()).isEqualTo("订单数");
        assertThat(result.sql()).isEqualTo("select count(*) from orders");
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.rows()).containsExactly(java.util.Map.of("count", 7));
        assertThat(result.answer()).isEqualTo("一共 7 单。");
        assertThat(result.guardBlocked()).isFalse();
        assertThat(result.error()).isNull();
    }

    @Test
    void ask_guardBlockedReplyPropagates() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/analytics/sql"))
                .andRespond(withSuccess(
                        "{\"question\":\"删表\",\"sql\":null,\"rowCount\":0,\"rows\":[],"
                                + "\"answer\":null,\"guardBlocked\":true}",
                        APPLICATION_JSON));

        AnalyticsClient.Result result = new HttpAnalyticsClient(restTemplate).ask("删表");

        assertThat(result.guardBlocked()).isTrue();
        assertThat(result.sql()).isNull();
        assertThat(result.rows()).isEmpty();
        assertThat(result.error()).isNull();
    }

    @Test
    void ask_transportFailureReturnsError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/analytics/sql"))
                .andRespond(withServerError());

        AnalyticsClient.Result result = new HttpAnalyticsClient(restTemplate).ask("订单数");

        assertThat(result.error()).isNotBlank();
        assertThat(result.rowCount()).isZero();
        assertThat(result.rows()).isEmpty();
        assertThat(result.guardBlocked()).isFalse();
    }

    @Test
    void listTables_getsAndParsesTableNames() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/analytics/schema/tables"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"tables\":[\"orders\",\"customers\"]}", APPLICATION_JSON));

        AnalyticsClient.TablesResult result = new HttpAnalyticsClient(restTemplate).listTables();

        server.verify();
        assertThat(result.tables()).containsExactly("orders", "customers");
        assertThat(result.error()).isNull();
    }

    @Test
    void describeTable_getsAndParsesSchema() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/analytics/schema/tables/orders"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"table\":\"orders\",\"schema\":\"Table orders\\n  - id BIGINT\"}", APPLICATION_JSON));

        AnalyticsClient.TableSchemaResult result = new HttpAnalyticsClient(restTemplate).describeTable("orders");

        server.verify();
        assertThat(result.table()).isEqualTo("orders");
        assertThat(result.schema()).contains("Table orders");
        assertThat(result.error()).isNull();
    }

    @Test
    void describeTable_notFoundReturnsError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/analytics/schema/tables/secret"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        AnalyticsClient.TableSchemaResult result = new HttpAnalyticsClient(restTemplate).describeTable("secret");

        assertThat(result.schema()).isNull();
        assertThat(result.error()).contains("not found");
    }
}
