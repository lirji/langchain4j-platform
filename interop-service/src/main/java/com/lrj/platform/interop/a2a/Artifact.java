package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * A2A Artifact —— 任务的产出物（一组 {@link Part}）。本实现只产文本 artifact。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Artifact(String artifactId, String name, List<Part> parts) {

    public static Artifact text(String name, String text) {
        return new Artifact(UUID.randomUUID().toString(), name, List.of(Part.text(text)));
    }

    /** 带稳定 {@code artifactId} 的文本 artifact —— 流式 append（逐 token 追加同一 artifact）时用固定 id。 */
    public static Artifact of(String artifactId, String name, String text) {
        return new Artifact(artifactId, name, List.of(Part.text(text)));
    }
}
