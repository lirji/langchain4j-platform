package com.lrj.platform.knowledge.lifecycle;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class MultimodalIngestionText {

    private MultimodalIngestionText() {}

    public static boolean isImageContentType(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("image/");
    }

    public static byte[] decodeBase64Image(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new IllegalArgumentException("imageBase64 is required");
        }
        String value = imageBase64.trim();
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) {
            value = value.substring(comma + 1);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length == 0) {
                throw new IllegalArgumentException("imageBase64 is empty");
            }
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("imageBase64 is not valid base64", ex);
        }
    }

    public static String build(String text, String caption, String ocrText) {
        List<String> sections = new ArrayList<>();
        add(sections, "Text", text);
        add(sections, "Image caption", caption);
        add(sections, "Image OCR", ocrText);
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("image caption or ocrText is required");
        }
        return String.join("\n\n", sections);
    }

    private static void add(List<String> sections, String label, String value) {
        if (value != null && !value.isBlank()) {
            sections.add(label + ":\n" + value.trim());
        }
    }
}
