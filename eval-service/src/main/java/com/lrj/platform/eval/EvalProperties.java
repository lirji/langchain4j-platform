package com.lrj.platform.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * eval-service 的配置属性（前缀 {@code app.eval}）。承载被测目标默认地址与鉴权头、响应片段截断长度、
 * 基线/报告/快照目录、LLM 判官与 embedding 的最小通过分，以及双跑门禁的容差参数
 * （passRate/averageScore 容差、最小 agreement、重复跑次数）。
 */
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
    private String snapshotDirectory = "";
    private double gatePassRateTolerance = 0.05D;
    private double gateAverageScoreTolerance = 0.05D;
    private double gateMinAgreement = 0.6D;
    private int gateRuns = 1;

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

    public String getSnapshotDirectory() {
        return snapshotDirectory;
    }

    public void setSnapshotDirectory(String snapshotDirectory) {
        this.snapshotDirectory = snapshotDirectory;
    }

    public double getGatePassRateTolerance() {
        return gatePassRateTolerance;
    }

    public void setGatePassRateTolerance(double gatePassRateTolerance) {
        this.gatePassRateTolerance = gatePassRateTolerance;
    }

    public double getGateAverageScoreTolerance() {
        return gateAverageScoreTolerance;
    }

    public void setGateAverageScoreTolerance(double gateAverageScoreTolerance) {
        this.gateAverageScoreTolerance = gateAverageScoreTolerance;
    }

    public double getGateMinAgreement() {
        return gateMinAgreement;
    }

    public void setGateMinAgreement(double gateMinAgreement) {
        this.gateMinAgreement = gateMinAgreement;
    }

    public int getGateRuns() {
        return gateRuns;
    }

    public void setGateRuns(int gateRuns) {
        this.gateRuns = gateRuns;
    }
}
