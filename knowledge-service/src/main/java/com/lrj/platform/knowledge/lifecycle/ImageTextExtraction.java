package com.lrj.platform.knowledge.lifecycle;

public record ImageTextExtraction(String caption, String ocrText) {

    public static ImageTextExtraction empty() {
        return new ImageTextExtraction(null, null);
    }

    public boolean isEmpty() {
        return blank(caption) && blank(ocrText);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
