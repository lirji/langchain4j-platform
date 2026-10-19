package com.lrj.platform.edge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "edge.casdoor.enabled=false",
                "app.rate-limit.enabled=false"
        })
class GatewayCorsTest {

    @Autowired
    private WebTestClient webTestClient;

    /** 异步入库依赖稳定提交编号，跨域预检必须允许它且不影响两类凭证。 */
    @ParameterizedTest
    @ValueSource(strings = {"authorization", "x-api-key"})
    void allowsIdempotentDocumentIngestionPreflight(String credentialHeader) {
        webTestClient.options()
                .uri("/rag/ingestions")
                .header(HttpHeaders.ORIGIN, "http://localhost:8093")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                        credentialHeader + ",content-type,idempotency-key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8093")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> assertThat(value).containsIgnoringCase("idempotency-key"));
    }

    /** 增加单一业务请求头不应扩大为任意请求头放行。 */
    @Test
    void stillRejectsUnknownIngestionHeader() {
        webTestClient.options()
                .uri("/rag/ingestions")
                .header(HttpHeaders.ORIGIN, "http://localhost:8093")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-untrusted-control")
                .exchange()
                .expectStatus().isForbidden();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8093",
            "http://127.0.0.1:8093",
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:5273",
            "http://127.0.0.1:5273",
            "http://localhost:4173",
            "http://127.0.0.1:4173"
    })
    void allowsDocumentedLoopbackFrontendOrigin(String origin) {
        webTestClient.options()
                .uri("/chat/stream")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        origin)
                .expectHeader().valueEquals(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                        "true");
    }

    @Test
    void rejectsUntrustedOrigin() {
        webTestClient.options()
                .uri("/chat/stream")
                .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
