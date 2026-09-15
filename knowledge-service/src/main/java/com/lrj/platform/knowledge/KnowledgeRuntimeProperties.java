package com.lrj.platform.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 同一 artifact 的部署角色；先拆发布/扩缩容生命周期，再拆仓库。 */
@ConfigurationProperties(prefix = "app.rag.runtime")
public class KnowledgeRuntimeProperties {

    private Role role = Role.COMBINED;
    private boolean production = false;
    private boolean legacyWriteEnabled = true;

    /** 显式生产保护，避免 combined 部署绕过持久化约束。 */
    public boolean isProduction() { return production; }
    public void setProduction(boolean production) { this.production = production; }
    /** 仅开发或迁移期允许旧同步上传；生产必须关闭。 */
    public boolean isLegacyWriteEnabled() { return legacyWriteEnabled; }
    public void setLegacyWriteEnabled(boolean enabled) { this.legacyWriteEnabled = enabled; }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public enum Role {
        COMBINED,
        QUERY,
        INGEST_API,
        INGEST_WORKER
    }
}
