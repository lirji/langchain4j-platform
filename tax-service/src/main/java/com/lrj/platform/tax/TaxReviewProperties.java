package com.lrj.platform.tax;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 财税审查运行参数。 */
@ConfigurationProperties(prefix = "app.tax")
@Validated
public class TaxReviewProperties {

    @Min(1)
    @Max(100)
    private int maxInvoices = 100;
    @NotBlank
    private String ruleSetVersion = "cn-vat-invoice-consistency-v1";
    @Valid
    private final Ai ai = new Ai();
    @Valid
    private final Knowledge knowledge = new Knowledge();

    public int getMaxInvoices() {
        return maxInvoices;
    }

    public void setMaxInvoices(int maxInvoices) {
        this.maxInvoices = maxInvoices;
    }

    public String getRuleSetVersion() {
        return ruleSetVersion;
    }

    public void setRuleSetVersion(String ruleSetVersion) {
        this.ruleSetVersion = ruleSetVersion;
    }

    public Ai getAi() {
        return ai;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public static class Ai {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Knowledge {
        private boolean enabled = true;
        @NotBlank
        private String baseUrl = "http://localhost:8084";
        @NotBlank
        private String category = "tax-policy";
        @Min(1)
        @Max(20)
        private int topK = 5;
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double minScore = 0.2;
        @Min(1)
        @Max(5000)
        private int evidenceMaxChars = 600;
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(3);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public int getEvidenceMaxChars() {
            return evidenceMaxChars;
        }

        public void setEvidenceMaxChars(int evidenceMaxChars) {
            this.evidenceMaxChars = evidenceMaxChars;
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
    }
}
