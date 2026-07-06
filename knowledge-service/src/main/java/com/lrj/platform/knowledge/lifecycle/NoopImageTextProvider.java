package com.lrj.platform.knowledge.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rag.image-text.provider", havingValue = "none", matchIfMissing = true)
public class NoopImageTextProvider implements ImageTextProvider {

    @Override
    public ImageTextExtraction extract(String filename, String contentType, byte[] imageBytes) {
        return ImageTextExtraction.empty();
    }
}
