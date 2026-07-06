package com.lrj.platform.knowledge.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpImageTextProviderTest {

    @Test
    void postsImageAndMapsCaptionAndOcrText() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ImageTextProviderProperties properties = new ImageTextProviderProperties();
        properties.setEndpointUrl("http://vision.local/image-text");
        HttpImageTextProvider provider = new HttpImageTextProvider(restTemplate, properties);

        server.expect(once(), requestTo("http://vision.local/image-text"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.filename").value("chart.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.imageBase64").value("AQID"))
                .andRespond(withSuccess("""
                        {"caption":"chart caption","ocrText":"total 99"}
                        """, MediaType.APPLICATION_JSON));

        ImageTextExtraction extracted = provider.extract("chart.png", "image/png", new byte[]{1, 2, 3});

        assertThat(extracted.caption()).isEqualTo("chart caption");
        assertThat(extracted.ocrText()).isEqualTo("total 99");
        server.verify();
    }

    @Test
    void returnsEmptyWhenProviderFails() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ImageTextProviderProperties properties = new ImageTextProviderProperties();
        properties.setEndpointUrl("http://vision.local/image-text");
        HttpImageTextProvider provider = new HttpImageTextProvider(restTemplate, properties);

        server.expect(once(), requestTo("http://vision.local/image-text"))
                .andRespond(withServerError());

        assertThat(provider.extract("chart.png", "image/png", new byte[]{1}).isEmpty()).isTrue();
        server.verify();
    }
}
