package com.lrj.platform.channel.dingtalk;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉知识库客服桥配置。默认关闭（{@code enabled=false}），开启后 {@code /channel/dingtalk/events}
 * 才接收钉钉企业内部机器人的消息回调。凭据（app-key / app-secret）按你的钉钉自建应用填。
 *
 * <p>钉钉回调不带平台 api-key，故该端点在 edge-gateway 免鉴权放行、靠钉钉签名（timestamp/sign）验真；
 * 租户由 {@code tenant-id} 配置指定（一个钉钉企业/机器人服务一个平台租户）。
 *
 * <p>相较飞书桥多一道「无命中转人工」兜底（{@link Fallback}）：调 {@code /chat} 前先查知识库，
 * 命中不足则发转人工话术 + @人工客服、不调 LLM。见 {@link DingtalkMessageBridge}。
 */
@ConfigurationProperties(prefix = "app.channel.dingtalk")
public class DingtalkProperties {

    /** 总开关。默认关，关时不装配桥、端点未启用。 */
    private boolean enabled = false;

    /** 该钉钉企业对应的平台租户（调 conversation /chat、查 knowledge /rag/query 时用它传播租户）。 */
    private String tenantId = "default";

    /** 钉钉 AppKey：既作 robotCode（发消息），也是取 access_token 的凭据。 */
    private String appKey = "";

    /** 钉钉 AppSecret：既用于回调验签（HmacSHA256），也是取 access_token 的凭据。 */
    private String appSecret = "";

    /** 是否校验回调的 timestamp/sign 签名。未配 app-secret 时无从校验，自动跳过。 */
    private boolean verifySignature = true;

    /** 钉钉开放平台服务端 API 基址（取 token + 机器人发消息）。 */
    private String apiBaseUrl = "https://api.dingtalk.com";

    /** 兜底闸门检索的知识库类目（与 conversation 侧 CONVERSATION_RAG_CATEGORY 对齐）。留空=全部。 */
    private String ragCategory = "";

    /** 兜底闸门检索召回条数。 */
    private int ragTopK = 5;

    /** 无命中转人工兜底。 */
    private final Fallback fallback = new Fallback();

    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(30);

    /** conversation-service 基址（桥调 /chat）。 */
    private String conversationBaseUrl = "http://conversation-service:8081";

    /** knowledge-service 基址（兜底闸门调 /rag/query）。 */
    private String knowledgeBaseUrl = "http://knowledge-service:8084";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    public boolean isVerifySignature() { return verifySignature; }
    public void setVerifySignature(boolean verifySignature) { this.verifySignature = verifySignature; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public String getRagCategory() { return ragCategory; }
    public void setRagCategory(String ragCategory) { this.ragCategory = ragCategory; }
    public int getRagTopK() { return ragTopK; }
    public void setRagTopK(int ragTopK) { this.ragTopK = ragTopK; }
    public Fallback getFallback() { return fallback; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public String getConversationBaseUrl() { return conversationBaseUrl; }
    public void setConversationBaseUrl(String conversationBaseUrl) { this.conversationBaseUrl = conversationBaseUrl; }
    public String getKnowledgeBaseUrl() { return knowledgeBaseUrl; }
    public void setKnowledgeBaseUrl(String knowledgeBaseUrl) { this.knowledgeBaseUrl = knowledgeBaseUrl; }

    /** 空类目归一化为 null（{@code KnowledgeQueryRequest} 约定 null=不按类目过滤）。 */
    public String ragCategoryOrNull() {
        return ragCategory == null || ragCategory.isBlank() ? null : ragCategory;
    }

    /** 无命中转人工兜底配置。 */
    public static class Fallback {
        /** 判定「答得了」所需的强命中条数下限。低于此值即转人工。 */
        private int minHits = 1;
        /** 强命中的相关性下限：低于此分的命中不计入 minHits，也作为知识库预过滤 minScore。 */
        private double minScore = 0.5;
        /** 转人工话术。 */
        private String message = "知识库暂未收录该问题，已为您转接人工客服，请稍候。";
        /** 转人工时 @ 的人工客服 userId 列表（逗号分隔环境变量即可）。为空则只发话术不 @。 */
        private List<String> humanAgentIds = new ArrayList<>();

        public int getMinHits() { return minHits; }
        public void setMinHits(int minHits) { this.minHits = minHits; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double minScore) { this.minScore = minScore; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public List<String> getHumanAgentIds() { return humanAgentIds; }
        public void setHumanAgentIds(List<String> humanAgentIds) { this.humanAgentIds = humanAgentIds; }
    }
}
