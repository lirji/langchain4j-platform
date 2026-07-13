package com.lrj.platform.protocol.knowledge;

/**
 * 一条检索命中（跨服务 DTO）。{@code visibility} 为末尾加法字段（RBAC 共享库支持）：
 * 取值 {@code "tenant"}（来自调用方自己租户分区）或 {@code "public"}（来自共享库保留分区 {@code __public__}）。
 * 老消费者忽略该字段即可，向后兼容。
 */
public record KnowledgeHit(String id,
                           Double score,
                           String docId,
                           String displayName,
                           String category,
                           String index,
                           String text,
                           String source,
                           String visibility) {
}
