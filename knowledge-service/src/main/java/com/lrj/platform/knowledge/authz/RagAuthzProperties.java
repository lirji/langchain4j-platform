package com.lrj.platform.knowledge.authz;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 细粒度授权配置（前缀 {@code app.rag.authz}），由 {@link KnowledgeAuthzConfig} 经
 * {@code @EnableConfigurationProperties} 注册。默认 {@code disabled}，与接入前逐字一致。
 *
 * <p>本类只承载配置与<strong>自校验</strong>：候选池/批次的实际<em>消费</em>（安全 overfetch、checkBulk 分批）
 * 在后续阶段的 {@code KnowledgeQueryService}/{@link RealKnowledgeAuthz} 落地；此处引入是为把上限固化成
 * 领域模型并让非法配置在<strong>启动期</strong>失败（{@link #afterPropertiesSet()}），而非运行期静默退化。
 *
 * <p>{@link #mode} 用枚举承载：非法字符串（如 {@code app.rag.authz.mode=bogus}）在 Spring 绑定期即失败，
 * 不会退化成 disabled。注意 bean 装配仍由 {@code KnowledgeAuthzConfig} 的 {@code @ConditionalOnProperty}
 * 按原字符串驱动（本阶段不改判权装配行为）。
 */
@ConfigurationProperties(prefix = "app.rag.authz")
public class RagAuthzProperties implements InitializingBean {

    /** 授权模式：disabled(默认) | shadow | enforce。 */
    private AuthzMode mode = AuthzMode.DISABLED;

    /** 授权候选放大倍数：与 {@code reranker.retrieveMultiplier()} 取 <em>max</em>（不相乘），给授权过滤留余量。>=1。 */
    private int candidateMultiplier = 3;

    /** 候选池绝对上限（纵深防御，避免超大 topK×放大 打爆 ES/SpiceDB）。>=1。 */
    private int maxCandidates = 200;

    /** 单次 checkBulk 的最大资源数，超出按此分批。取值 [1, maxCandidates]。 */
    private int bulkSize = 100;

    /** 严格租户模式：true 时要求身份仅来自 Casdoor owner，且与公共库互斥（跨配置校验在后续接口/配置层）。 */
    private boolean strictTenantOnly = false;

    /** 启动期自校验：任一上限非法则抛出，使上下文启动失败，绝不静默退化。 */
    @Override
    public void afterPropertiesSet() {
        if (candidateMultiplier < 1) {
            throw new IllegalStateException(
                    "app.rag.authz.candidate-multiplier 必须 >= 1，当前=" + candidateMultiplier);
        }
        if (maxCandidates < 1) {
            throw new IllegalStateException(
                    "app.rag.authz.max-candidates 必须 >= 1，当前=" + maxCandidates);
        }
        if (bulkSize < 1 || bulkSize > maxCandidates) {
            throw new IllegalStateException(
                    "app.rag.authz.bulk-size 必须在 [1, max-candidates(" + maxCandidates + ")] 内，当前=" + bulkSize);
        }
    }

    public AuthzMode getMode() {
        return mode;
    }

    public void setMode(AuthzMode mode) {
        this.mode = mode;
    }

    public int getCandidateMultiplier() {
        return candidateMultiplier;
    }

    public void setCandidateMultiplier(int candidateMultiplier) {
        this.candidateMultiplier = candidateMultiplier;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getBulkSize() {
        return bulkSize;
    }

    public void setBulkSize(int bulkSize) {
        this.bulkSize = bulkSize;
    }

    public boolean isStrictTenantOnly() {
        return strictTenantOnly;
    }

    public void setStrictTenantOnly(boolean strictTenantOnly) {
        this.strictTenantOnly = strictTenantOnly;
    }
}
