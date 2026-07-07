package com.lrj.platform.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.eval")
public class EvalProperties {

    private String defaultTargetBaseUrl = "http://edge-gateway:8080";
    private String apiKey = "";
    private String apiKeyHeader = "X-API-Key";
    private int responseSnippetLimit = 512;
    private String baselineDirectory = "";
    private String reportDirectory = "";
    private double judgeMinScore = 0.7D;
    private double embeddingMinScore = 0.75D;

    public String getDefaultTargetBaseUrl() {
        return defaultTargetBaseUrl;
    }

    public void setDefaultTargetBaseUrl(String defaultTargetBaseUrl) {
        this.defaultTargetBaseUrl = defaultTargetBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKeyHeader() {
        return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
        this.apiKeyHeader = apiKeyHeader;
    }

    public int getResponseSnippetLimit() {
        return responseSnippetLimit;
    }

    public void setResponseSnippetLimit(int responseSnippetLimit) {
        this.responseSnippetLimit = responseSnippetLimit;
    }

    public String getBaselineDirectory() {
        return baselineDirectory;
    }

    public void setBaselineDirectory(String baselineDirectory) {
        this.baselineDirectory = baselineDirectory;
    }

    public String getReportDirectory() {
        return reportDirectory;
    }

    public void setReportDirectory(String reportDirectory) {
        this.reportDirectory = reportDirectory;
    }

    public double getJudgeMinScore() {
        return judgeMinScore;
    }

    public void setJudgeMinScore(double judgeMinScore) {
        this.judgeMinScore = judgeMinScore;
    }

    public double getEmbeddingMinScore() {
        return embeddingMinScore;
    }

    public void setEmbeddingMinScore(double embeddingMinScore) {
        this.embeddingMinScore = embeddingMinScore;
    }
}
