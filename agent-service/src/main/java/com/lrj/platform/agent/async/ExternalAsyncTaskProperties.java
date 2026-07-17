package com.lrj.platform.agent.async;

/**
 * {@link ExternalAsyncTaskClient} 的配置项：是否启用外部托管、async-task-service 基址、是否镜像
 * webhook、中央端是否为权威投递方（authoritative）、worker 标识与租约秒数。绑定
 * {@code app.agent.async.external.*} 前缀，决定本地与中央任务中心的职责划分。
 */
public class ExternalAsyncTaskProperties {

    private boolean enabled;
    private String baseUrl = "http://localhost:8086";
    private boolean mirrorWebhook;
    private boolean authoritative;
    private String workerId = "agent-service";
    private long leaseSeconds = 300;

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

    public boolean isMirrorWebhook() {
        return mirrorWebhook;
    }

    public void setMirrorWebhook(boolean mirrorWebhook) {
        this.mirrorWebhook = mirrorWebhook;
    }

    public boolean isAuthoritative() {
        return authoritative;
    }

    public void setAuthoritative(boolean authoritative) {
        this.authoritative = authoritative;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }
}
