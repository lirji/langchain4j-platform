package com.lrj.platform.conversation.memory.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 独立画像数据库配置；机密由环境注入，连接池容量及获取连接等待时间均有界。 */
@ConfigurationProperties("app.conversation.memory.profile.datasource")
public record ProfileJdbcProperties(String url, String username, String password,
                                    @DefaultValue("4") int maximumPoolSize,
                                    @DefaultValue("3000") long connectionTimeoutMs) {
    public ProfileJdbcProperties {
        if (url == null || url.isBlank() || username == null || username.isBlank()) {
            throw new IllegalArgumentException("profile datasource url and username are required");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 32) {
            throw new IllegalArgumentException("profile datasource maximum-pool-size must be between 1 and 32");
        }
        if (connectionTimeoutMs < 250 || connectionTimeoutMs > 30_000) {
            throw new IllegalArgumentException("profile datasource connection-timeout-ms must be between 250 and 30000");
        }
    }

    /** 防止配置对象被诊断日志打印时泄露 URL 参数或口令。 */
    @Override
    public String toString() {
        return "ProfileJdbcProperties[credentials=redacted, maximumPoolSize=" + maximumPoolSize + "]";
    }
}
