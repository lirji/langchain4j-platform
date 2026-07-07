package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * {@code tasks/get} / {@code tasks/cancel} 的 params —— 都只需要一个 task {@code id}
 * （外加可选 historyLength / metadata，本实现忽略）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskQueryParams(String id, Integer historyLength, Map<String, Object> metadata) {
}
