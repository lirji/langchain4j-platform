package com.lrj.platform.knowledge.lifecycle;

import java.util.Base64;

/** 图片上传的小工具：判定图片 content-type、解码 base64（含 data-uri 前缀）。 */
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
}
