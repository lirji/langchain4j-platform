package com.lrj.platform.interop;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.interop")
public class InteropProperties {

    private String agentBaseUrl = "http://localhost:8085";
    private String conversationBaseUrl = "http://localhost:8081";
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(30);
    /** conversation /chat/stream 代理的读超时；须大于 A2A 流式 emitter 超时，避免中途截断。 */
    private Duration streamReadTimeout = Duration.ofSeconds(120);

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

    public String getConversationBaseUrl() {
        return conversationBaseUrl;
    }

    public void setConversationBaseUrl(String conversationBaseUrl) {
        this.conversationBaseUrl = conversationBaseUrl;
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

    public Duration getStreamReadTimeout() {
        return streamReadTimeout;
    }

    public void setStreamReadTimeout(Duration streamReadTimeout) {
        this.streamReadTimeout = streamReadTimeout;
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

        /** push 中继回调基址：agent 任务终态 webhook 回到 interop 自己（内网直连、不经 edge-gateway）。 */
        private String pushCallbackBaseUrl = "http://localhost:8088";
        /** A2A push 回推客户端时的 HMAC 签名密钥；空则不签名（{@code X-Webhook-Signature} 不发）。 */
        private String pushHmacSecret = "";
        private Duration pushConnectTimeout = Duration.ofSeconds(2);
        private Duration pushReadTimeout = Duration.ofSeconds(10);
        private int pushMaxRetries = 2;
        private Duration pushBackoff = Duration.ofMillis(500);

        /** 完整 push 回调 URL（供 message/send 作 agent 任务的 webhookUrl）。 */
        public String getPushCallbackUrl() {
            return pushCallbackBaseUrl + "/interop/a2a/push-callback";
        }

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

        public String getPushCallbackBaseUrl() {
            return pushCallbackBaseUrl;
        }

        public void setPushCallbackBaseUrl(String pushCallbackBaseUrl) {
            this.pushCallbackBaseUrl = pushCallbackBaseUrl;
        }

        public String getPushHmacSecret() {
            return pushHmacSecret;
        }

        public void setPushHmacSecret(String pushHmacSecret) {
            this.pushHmacSecret = pushHmacSecret;
        }

        public Duration getPushConnectTimeout() {
            return pushConnectTimeout;
        }

        public void setPushConnectTimeout(Duration pushConnectTimeout) {
            this.pushConnectTimeout = pushConnectTimeout;
        }

        public Duration getPushReadTimeout() {
            return pushReadTimeout;
        }

        public void setPushReadTimeout(Duration pushReadTimeout) {
            this.pushReadTimeout = pushReadTimeout;
        }

        public int getPushMaxRetries() {
            return pushMaxRetries;
        }

        public void setPushMaxRetries(int pushMaxRetries) {
            this.pushMaxRetries = pushMaxRetries;
        }

        public Duration getPushBackoff() {
            return pushBackoff;
        }

        public void setPushBackoff(Duration pushBackoff) {
            this.pushBackoff = pushBackoff;
        }
    }
}
