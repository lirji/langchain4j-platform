package com.lrj.platform.protocol.interop;

import java.util.List;
import java.util.Map;

/**
 * A2A/interop 场景下对外暴露的 Agent 名片（跨服务 DTO）。
 * 描述本 Agent 的 {@code name}、{@code description}、{@code version}、支持的 {@code capabilities} 能力清单，
 * 以及可供对端调用的 {@code endpoints} 端点表，供 interop-service 的 {@code /interop/**} 发现与握手使用。
 * 紧凑构造器对集合做空值兜底与不可变拷贝。
 */
public record AgentCard(String name,
                        String description,
                        String version,
                        List<String> capabilities,
                        Map<String, String> endpoints) {

    public AgentCard {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
    }
}
