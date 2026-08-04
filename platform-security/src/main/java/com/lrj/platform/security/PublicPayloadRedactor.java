package com.lrj.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对公开事件负载做 fail-closed 的递归 PII 脱敏。先把语言内 DTO 转成 JSON 兼容树，再遮蔽邮箱、
 * 中国手机号和身份证，防止嵌套 task result/progress 绕过只处理顶层字符串的护栏。
 */
public class PublicPayloadRedactor {

    private static final int MAX_DEPTH = 32;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_CN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    private final ObjectMapper objectMapper;

    public PublicPayloadRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 非 Spring 单测/兼容构造器使用；日期保持 Web JSON 的 ISO-8601 形态。 */
    public static PublicPayloadRedactor standalone() {
        ObjectMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return new PublicPayloadRedactor(mapper);
    }

    public Object redact(Object value) {
        return redact(value, 0);
    }

    private Object redact(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth >= MAX_DEPTH) {
            return "[REDACTED-depth-limit]";
        }
        if (value instanceof String text) {
            return redactText(text);
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(redactText(String.valueOf(key)), redact(item, depth + 1)));
            return copy;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(redact(item, depth + 1)));
            return copy;
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                copy.add(redact(Array.get(value, index), depth + 1));
            }
            return copy;
        }
        try {
            return redact(objectMapper.convertValue(value, Object.class), depth + 1);
        } catch (IllegalArgumentException ignored) {
            return "[REDACTED-unserializable]";
        }
    }

    private static String redactText(String text) {
        String redacted = ID_CN.matcher(text).replaceAll("[REDACTED-id-card]");
        redacted = EMAIL.matcher(redacted).replaceAll("[REDACTED-email]");
        return PHONE_CN.matcher(redacted).replaceAll("[REDACTED-phone]");
    }
}
