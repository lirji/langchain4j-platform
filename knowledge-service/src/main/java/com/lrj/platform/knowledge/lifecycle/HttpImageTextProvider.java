package com.lrj.platform.knowledge.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.rag.image-text.provider", havingValue = "http")
public class HttpImageTextProvider implements ImageTextProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpImageTextProvider.class);

    private final RestTemplate restTemplate;
    private final ImageTextProviderProperties properties;

    public HttpImageTextProvider(RestTemplate imageTextRestTemplate, ImageTextProviderProperties properties) {
        this.restTemplate = imageTextRestTemplate;
        this.properties = properties;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ImageTextExtraction extract(String filename, String contentType, byte[] imageBytes) {
        if (properties.getEndpointUrl() == null || properties.getEndpointUrl().isBlank()) {
            return ImageTextExtraction.empty();
        }
        try {
            Map<String, Object> response = restTemplate.postForObject(
                    properties.getEndpointUrl(),
                    new HttpEntity<>(request(filename, contentType, imageBytes)),
                    Map.class);
            if (response == null) {
                return ImageTextExtraction.empty();
            }
            return new ImageTextExtraction(string(response.get("caption")), string(response.get("ocrText")));
        } catch (RestClientException ex) {
            log.warn("image text provider failed filename={}: {}", filename, ex.toString());
            return ImageTextExtraction.empty();
        }
    }

    private static Map<String, Object> request(String filename, String contentType, byte[] imageBytes) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("filename", filename);
        request.put("contentType", contentType);
        request.put("imageBase64", Base64.getEncoder().encodeToString(imageBytes));
        return Map.copyOf(request);
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text;
    }
}
