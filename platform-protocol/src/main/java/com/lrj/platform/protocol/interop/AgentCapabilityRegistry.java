package com.lrj.platform.protocol.interop;

import java.util.List;

/**
 * AgentScope 发布的版本化、语言中立能力注册表。revision 是能力描述符规范 JSON 的 SHA-256；
 * interop 只消费并持久化该 DTO，不复制 Java 静态能力目录。
 */
public record AgentCapabilityRegistry(String schemaVersion,
                                      String revision,
                                      List<McpToolDescriptor> capabilities) {

    public AgentCapabilityRegistry {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
