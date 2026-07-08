package com.lrj.platform.channel.feishu;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 飞书（Lark）事件桥配置。默认关闭（{@code enabled=false}），开启后 {@code /channel/feishu/events}
 * 才接收飞书事件回调。凭据（verification-token / encrypt-key / app-id / app-secret）后续按你的飞书自建应用填。
 *
 * <p>飞书入站回调不带平台 api-key，故该端点在 edge-gateway 免鉴权放行、靠飞书签名验真；
 * 租户由 {@code tenant-id} 配置指定（一个飞书应用服务一个平台租户）。
 */
@ConfigurationProperties(prefix = "app.channel.feishu")
public class FeishuProperties {

    /** 总开关。默认关，关时不装配桥、端点返回 404/未启用。 */
    private boolean enabled = false;

    /** 该飞书应用对应的平台租户（调 conversation /chat 时用它传播租户）。 */
    private String tenantId = "default";

    /** 事件订阅的 verification token（校验事件体里的 token）。 */
    private String verificationToken = "";

    /** 事件加密 key（配了则事件体为 {"encrypt": "..."}，用它 AES-256-CBC 解密；未配则明文）。 */
    private String encryptKey = "";

    /** 是否校验 X-Lark-Signature（需 encrypt-key）。默认按 encrypt-key 是否配置自动决定。 */
    private boolean verifySignature = true;

    /** 回复用飞书应用凭据（经 im/v1/messages 发消息给用户）。 */
    private final Reply reply = new Reply();

    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(30);

    /** conversation-service 基址（桥调 /chat）。 */
    private String conversationBaseUrl = "http://conversation-service:8081";

    /** workflow-service 基址（意图路由命中时桥调 /workflow/refund/start）。 */
    private String workflowBaseUrl = "http://workflow-service:8082";

    /** 意图路由（退款/投诉 → 退款工作流）。默认关：命中也不起工单，行为等价纯对话桥。 */
    private final IntentRouting intentRouting = new IntentRouting();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }
    public String getEncryptKey() { return encryptKey; }
    public void setEncryptKey(String encryptKey) { this.encryptKey = encryptKey; }
    public boolean isVerifySignature() { return verifySignature; }
    public void setVerifySignature(boolean verifySignature) { this.verifySignature = verifySignature; }
    public Reply getReply() { return reply; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public String getConversationBaseUrl() { return conversationBaseUrl; }
    public void setConversationBaseUrl(String conversationBaseUrl) { this.conversationBaseUrl = conversationBaseUrl; }
    public String getWorkflowBaseUrl() { return workflowBaseUrl; }
    public void setWorkflowBaseUrl(String workflowBaseUrl) { this.workflowBaseUrl = workflowBaseUrl; }
    public IntentRouting getIntentRouting() { return intentRouting; }

    /** 意图路由开关。默认关，开启后飞书入站消息先过 {@link FeishuIntent} 分类，退款/投诉分流到退款工作流。 */
    public static class IntentRouting {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Reply {
        /** 是否回复。关时桥只收不回（便于先验证入站链路）。 */
        private boolean enabled = false;
        private String appId = "";
        private String appSecret = "";
        /** 飞书开放平台基址（自建应用国内版）。 */
        private String baseUrl = "https://open.feishu.cn";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
