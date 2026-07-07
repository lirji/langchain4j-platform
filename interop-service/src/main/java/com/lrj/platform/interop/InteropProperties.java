package com.lrj.platform.interop;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.interop")
public class InteropProperties {

    private String agentBaseUrl = "http://localhost:8085";
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * live capability discovery 开关。默认关（false）—— 保持静态 registry 行为、零下游依赖，
     * dev/test 与既有测试不变。开启后 interop 懒加载 + TTL 从 agent-service 拉取能力，
     * 下游不可达时确定性回退到静态默认。
     */
    private boolean discoveryEnabled = false;

    /** live discovery 缓存 TTL；过期后下次访问触发懒刷新。 */
    private Duration capabilityTtl = Duration.ofSeconds(60);

    @NestedConfigurationProperty
    private A2a a2a = new A2a();

    public String getAgentBaseUrl() {
        return agentBaseUrl;
    }

    public void setAgentBaseUrl(String agentBaseUrl) {
        this.agentBaseUrl = agentBaseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    public void setDiscoveryEnabled(boolean discoveryEnabled) {
        this.discoveryEnabled = discoveryEnabled;
    }

    public Duration getCapabilityTtl() {
        return capabilityTtl;
    }

    public void setCapabilityTtl(Duration capabilityTtl) {
        this.capabilityTtl = capabilityTtl;
    }

    public A2a getA2a() {
        return a2a;
    }

    public void setA2a(A2a a2a) {
        this.a2a = a2a;
    }

    /** 真 A2A Agent Card 元数据（发布在 {@code /.well-known/agent-card.json}）。 */
    public static class A2a {

        private String agentName = "langchain4j-platform";
        private String agentDescription =
                "Platform agent exposed over the A2A protocol: sync chat and async deep-research tasks, "
                        + "proxied to agent-service.";
        private String baseUrl = "http://localhost:8080";
        private String version = "0.1.0";

        public String getAgentName() {
            return agentName;
        }

        public void setAgentName(String agentName) {
            this.agentName = agentName;
        }

        public String getAgentDescription() {
            return agentDescription;
        }

        public void setAgentDescription(String agentDescription) {
            this.agentDescription = agentDescription;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }
}
