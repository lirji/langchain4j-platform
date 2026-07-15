package com.lrj.platform.knowledge.authz;

/**
 * 细粒度授权运行模式（app.rag.authz.mode）。
 * <ul>
 *   <li>DISABLED —— 不双写、不过滤，与接入前逐字一致（默认，由 {@link NoopKnowledgeAuthz} 承担）。</li>
 *   <li>SHADOW —— 写路径照写关系；读路径真算 ReBAC 可见集并打点差异，但<strong>不拦截</strong>（灰度观测用）。</li>
 *   <li>ENFORCE —— 写路径照写关系；读路径按 ReBAC 真过滤（生产强制）。</li>
 * </ul>
 */
public enum AuthzMode {
    DISABLED,
    SHADOW,
    ENFORCE
    // 注：配置值→枚举由 Spring 的 @ConditionalOnProperty(havingValue) 装配处理，无需自定义解析。
}
