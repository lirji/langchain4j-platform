package com.lrj.platform.migrations;

import java.util.Arrays;

/** Relational schemas owned by platform services. */
public enum SchemaName {
    AUTH("auth"),
    ASYNC_TASK("async-task"),
    WORKFLOW("workflow"),
    KNOWLEDGE_INGESTION("knowledge-ingestion"),
    KNOWLEDGE_GRAPH("knowledge-graph"),
    ORDER("order"),
    CHANNEL("channel"),
    ANALYTICS_DEMO("analytics-demo");

    private final String id;

    SchemaName(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static SchemaName parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MIGRATION_SCHEMA is required");
        }
        return Arrays.stream(values())
                .filter(schema -> schema.id.equals(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported MIGRATION_SCHEMA: " + value));
    }
}
