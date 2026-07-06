package com.lrj.platform.knowledge.graph;

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
