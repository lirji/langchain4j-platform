package com.lrj.platform.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 同一 artifact 的部署角色；先拆发布/扩缩容生命周期，再拆仓库。 */
@ConfigurationProperties(prefix = "app.rag.runtime")
public class KnowledgeRuntimeProperties {

    private Role role = Role.COMBINED;

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
