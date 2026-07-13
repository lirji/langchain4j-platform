package com.lrj.platform.knowledge.es;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ES RAG 配置（阶段1，es-hybrid-rerank）。前缀 {@code app.rag.es}，由 {@code EsRagConfig} 经
 * {@code @EnableConfigurationProperties} 注册。默认全关，未部署 ES 不影响启动。
 *
 * <p>{@code enabled} 是总开关；{@code indexEnabled}/{@code queryEnabled} 默认 true，
 * 但只有在 {@code enabled=true} 时才由各 bean 生效（灰度可只开写、不开查）。
 */
@ConfigurationProperties(prefix = "app.rag.es")
public class EsRagProperties {

    /** 总开关：false（默认）时索引器为 Noop、不注册 ES 检索源。 */
    private boolean enabled = false;
    /** 是否写 ES 索引（受 enabled 门控）。 */
    private boolean indexEnabled = true;
    /** 是否用 ES 参与检索（受 enabled 门控）。 */
    private boolean queryEnabled = true;
    /** ES 地址，逗号分隔多节点，如 http://localhost:9200。 */
    private String uris = "http://localhost:9200";
    private String username;
    private String password;
    private String apiKey;
    /** 全文索引名。 */
    private String indexName = "knowledge_segments_text";
    /** text 字段分析器：smartcn（默认，需 analysis-smartcn 插件）| ik_smart | ik_max_word。不可用 standard（中文按单字切）。 */
    private String analyzer = "smartcn";
    /** weighted_max 融合下把 BM25 归一到 [0,1]；rrf 融合忽略此项。 */
    private boolean normalizeScore = true;
    /** ES 写入失败是否让上传整体失败；false=best-effort（默认）。 */
    private boolean failFast = false;
    private int connectTimeoutMs = 2000;
    private int socketTimeoutMs = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 有效写开关：总开关与写开关同时为真。 */
    public boolean isIndexActive() {
        return enabled && indexEnabled;
    }

    public boolean isIndexEnabled() {
        return indexEnabled;
    }

    public void setIndexEnabled(boolean indexEnabled) {
        this.indexEnabled = indexEnabled;
    }

    /** 有效查开关：总开关与查开关同时为真。 */
    public boolean isQueryActive() {
        return enabled && queryEnabled;
    }

    public boolean isQueryEnabled() {
        return queryEnabled;
    }

    public void setQueryEnabled(boolean queryEnabled) {
        this.queryEnabled = queryEnabled;
    }

    public String getUris() {
        return uris;
    }

    public void setUris(String uris) {
        this.uris = uris;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getAnalyzer() {
        return analyzer;
    }

    public void setAnalyzer(String analyzer) {
        this.analyzer = analyzer;
    }

    public boolean isNormalizeScore() {
        return normalizeScore;
    }

    public void setNormalizeScore(boolean normalizeScore) {
        this.normalizeScore = normalizeScore;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }
}
