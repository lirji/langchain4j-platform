package com.lrj.platform.metering.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 灰度开关与每次模型资源上限；不改已有日额度权威来源。 */
@ConfigurationProperties("app.token-budget.reservations")
public class ReservationBudgetProperties {
    private boolean enabled;
    private int maxOutputTokens = 4096;
    private int maxInputBytes = 131072;
    private int imageTokenAllowance = 16384;
    private boolean apiEnabled;
    private String serviceSecret;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int tokens) {
        if (tokens < 1 || tokens > 131072) throw new IllegalArgumentException("invalid maximum output tokens");
        maxOutputTokens = tokens;
    }
    public int getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(int bytes) {
        if (bytes < 1 || bytes > 524288) throw new IllegalArgumentException("invalid maximum input bytes");
        maxInputBytes = bytes;
    }
    public int getImageTokenAllowance() { return imageTokenAllowance; }
    public void setImageTokenAllowance(int tokens) {
        if (tokens < 0 || tokens > 65536) throw new IllegalArgumentException("invalid image allowance");
        imageTokenAllowance = tokens;
    }
    public boolean isApiEnabled() { return apiEnabled; }
    public void setApiEnabled(boolean enabled) { apiEnabled = enabled; }
    public String getServiceSecret() { return serviceSecret; }
    public void setServiceSecret(String secret) { serviceSecret = secret; }
}
