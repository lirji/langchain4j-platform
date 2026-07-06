package com.lrj.platform.knowledge.lifecycle;

public interface ImageTextProvider {

    ImageTextExtraction extract(String filename, String contentType, byte[] imageBytes);
}
