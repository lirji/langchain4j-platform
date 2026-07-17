package com.lrj.platform.knowledge.graph;

/**
 * 已归一化、可入库的图三元组：在主语/谓语/宾语之外携带来源文档 {@code sourceId}、租户 {@code tenantId}
 * 与可选 {@code category}（用于隔离与溯源）。构造时校验 subject/relation/object/tenantId 必填，否则抛异常。
 */
public record Triple(String subject,
                     String relation,
                     String object,
                     String sourceId,
                     String tenantId,
                     String category) {

    public Triple {
        subject = normalize(subject);
        relation = normalize(relation);
        object = normalize(object);
        sourceId = normalize(sourceId);
        tenantId = normalize(tenantId);
        category = normalizeNullable(category);
        if (subject.isBlank() || relation.isBlank() || object.isBlank() || tenantId.isBlank()) {
            throw new IllegalArgumentException("subject, relation, object and tenantId are required");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeNullable(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? null : normalized;
    }
}
