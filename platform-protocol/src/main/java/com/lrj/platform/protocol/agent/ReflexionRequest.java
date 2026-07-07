package com.lrj.platform.protocol.agent;

/**
 * Reflexion 自省环入参（{@code POST /agent/reflexive} 与 SSE {@code /agent/reflexive/stream} body）。
 *
 * @param question 待自省作答的问题
 */
public record ReflexionRequest(String question) {
}
