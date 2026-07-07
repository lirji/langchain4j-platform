package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A message / artifact 的内容片段。{@code kind} 是判别字段，本实现只支持 {@code "text"}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Part(String kind, String text) {

    public static Part text(String text) {
        return new Part("text", text);
    }
}
