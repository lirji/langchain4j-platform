package com.lrj.platform.edge;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * ③ Casdoor SSO 身份切换配置（edge.casdoor.*）。
 *
 * <p><strong>生效默认以 {@code edge-gateway/application.yml} 为准：{@code enabled=true}、{@code mode=only}</strong>
 * （{@code EDGE_CASDOOR_ENABLED} / {@code EDGE_CASDOOR_MODE}）。下面字段上的初始值只是 yml 缺席时（如单测直接
 * new 本对象）的保守回落值，不代表部署默认——不要据此判断「Casdoor 默认关」。
 *
 * <p>enabled=true 时 {@code CasdoorTokenExchangeFilter} 用 Casdoor JWKS 验 {@code Authorization: Bearer}，
 * 验过就把 {@code owner→tenantId}、{@code sub→userId}、scope 换成内部 JWT；验不过按 {@link #mode} 401 或透传 legacy。
 *
 * <p>scope 由 Casdoor（权限中心）展开进 token，edge 只从 {@link #scopeClaim} 提取 + 用固定
 * {@link #scopeAllowlist} 过滤——<strong>edge 不维护 role→scope 表，角色规模增长与 edge 无关</strong>（见设计 D3）。
 */
@ConfigurationProperties(prefix = "edge.casdoor")
public class CasdoorSecurityProperties {

    /** 是否启用 Casdoor token 换发。字段回落值 false；yml 生效默认 true。 */
    private boolean enabled = false;

    /**
     * 认证模式（仅 enabled=true 时生效）：
     * <ul>
     *   <li>{@code DUAL}（字段回落值；灰度/回滚窗口）：Casdoor 验过即换发内部 JWT；无 Bearer / 验签失败 → <strong>透传</strong>
     *       给 legacy(session/api-key) filter，供切换窗口回滚。</li>
     *   <li>{@code ONLY}（yml 生效默认，严格 Casdoor-only）：非 open path <strong>必须</strong>有有效 Casdoor token；
     *       无 Bearer / 验签失败 → <strong>401</strong>，不再落 legacy。tenant 恒取 owner，杜绝身份混用。</li>
     * </ul>
     */
    private Mode mode = Mode.DUAL;

    /** {@link #mode} 取值。disabled 由 {@code enabled=false} 表达（filter 根本不装配）。 */
    public enum Mode { DUAL, ONLY }

    /** Casdoor issuer（token iss 校验）。 */
    private String issuer;

    /** Casdoor JWKS 端点（验签公钥，自动缓存/轮转）。 */
    private String jwkSetUri;

    /** 允许的 audience（Casdoor client_id）白名单；非空时 token aud 须命中其一。 */
    private List<String> audiences = List.of();

    /** 租户来自哪个 claim（Casdoor org）。 */
    private String tenantClaim = "owner";

    /** 用户 id 来自哪个 claim（稳定 sub）。 */
    private String subjectClaim = "sub";

    /** 承载已展开业务能力的 claim（默认 Casdoor {@code permissions} 对象数组；也支持 scope 字符串列表）。 */
    private String scopeClaim = "permissions";

    /** 当 scopeClaim 是对象数组（Casdoor permissions）时，从每个对象的此字段取 scope 名。 */
    private String scopeNameField = "name";

    /** 部门来自哪个 claim（Casdoor 用户 groups，元素形如 {@code <org>/<group>}）；一人一部门，取与 owner 同 org 的唯一组。 */
    private String groupsClaim = "groups";

    /** 固定业务 scope allowlist（与角色数量无关）；只有其中的 scope 才写入内部 JWT。 */
    private List<String> scopeAllowlist = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DUAL : mode;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public List<String> getAudiences() {
        return audiences;
    }

    public void setAudiences(List<String> audiences) {
        this.audiences = audiences == null ? List.of() : audiences;
    }

    public String getTenantClaim() {
        return tenantClaim;
    }

    public void setTenantClaim(String tenantClaim) {
        this.tenantClaim = tenantClaim;
    }

    public String getGroupsClaim() {
        return groupsClaim;
    }

    public void setGroupsClaim(String groupsClaim) {
        this.groupsClaim = groupsClaim == null ? "groups" : groupsClaim;
    }

    public String getSubjectClaim() {
        return subjectClaim;
    }

    public void setSubjectClaim(String subjectClaim) {
        this.subjectClaim = subjectClaim;
    }

    public String getScopeClaim() {
        return scopeClaim;
    }

    public void setScopeClaim(String scopeClaim) {
        this.scopeClaim = scopeClaim;
    }

    public String getScopeNameField() {
        return scopeNameField;
    }

    public void setScopeNameField(String scopeNameField) {
        this.scopeNameField = scopeNameField;
    }

    public List<String> getScopeAllowlist() {
        return scopeAllowlist;
    }

    public void setScopeAllowlist(List<String> scopeAllowlist) {
        this.scopeAllowlist = scopeAllowlist == null ? List.of() : scopeAllowlist;
    }
}
