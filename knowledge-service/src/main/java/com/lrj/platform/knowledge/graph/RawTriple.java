package com.lrj.platform.knowledge.graph;

public record RawTriple(String subject, String relation, String object) {

    public RawTriple {
        subject = normalize(subject);
        relation = normalize(relation);
        object = normalize(object);
    }

    public boolean isComplete() {
        return !subject.isBlank() && !relation.isBlank() && !object.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
