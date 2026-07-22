package com.lrj.platform.eval.retrieval;

import com.lrj.platform.eval.EvalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class HttpRetrievalClientTest {

    @Test
    void authenticationFailureIsNotReportedAsZeroRecall() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpRetrievalClient client = new HttpRetrievalClient(restTemplate, new EvalProperties());
        server.expect(requestTo("http://edge/rag/query"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"unauthorized\"}"));

        assertThatThrownBy(() -> client.retrieve("http://edge", "退款", 5, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502 BAD_GATEWAY")
                .hasMessageContaining("401");
        server.verify();
    }
}
