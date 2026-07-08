package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A2A {@code message/stream} 的产出增量事件（{@code kind="artifact-update"}）。
 * {@code append=true} 表示把 {@code artifact} 追加到同一 {@code artifactId} 的产出上（逐 token 增量）；
 * {@code lastChunk=true} 表示该 artifact 的最后一块。移植自单体 {@code a2a/protocol}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskArtifactUpdateEvent(String taskId,
                                      String contextId,
                                      Artifact artifact,
                                      boolean append,
                                      boolean lastChunk) {

    @JsonProperty("kind")
    public String kind() {
        return "artifact-update";
    }
}
