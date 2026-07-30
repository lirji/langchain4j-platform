package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 只读解析 AgentScope Python runner 输出的统一 shadow-report artifact。
 * Java eval-service 不重新执行 Agent shadow，也不成为该报告的在线权威。
 */
public class AgentScopeShadowReportReader {

    private final ObjectMapper mapper;

    public AgentScopeShadowReportReader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    public ShadowReportSummary read(InputStream input) {
        try {
            JsonNode root = mapper.readTree(input);
            String suite = requiredText(root, "suite");
            Instant generatedAt = Instant.parse(requiredText(root, "generated_at"));
            int runs = requiredPositiveInt(root, "runs_per_case");
            JsonNode gate = requiredObject(root, "gate");
            boolean passed = requiredBoolean(gate, "passed");
            List<String> regressions = strings(gate.path("regressions"));
            JsonNode samples = root.path("samples");
            if (!samples.isArray()) {
                throw new IllegalArgumentException("shadow report samples must be an array");
            }
            return new ShadowReportSummary(
                    suite, generatedAt, runs, passed, regressions, samples.size());
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid AgentScope shadow report JSON", exception);
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) {
            throw new IllegalArgumentException("shadow report field is required: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("shadow report field is required: " + field);
        }
        return value.asText();
    }

    private static int requiredPositiveInt(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.canConvertToInt() || value.asInt() < 1) {
            throw new IllegalArgumentException("shadow report field must be positive: " + field);
        }
        return value.asInt();
    }

    private static boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("shadow report field must be boolean: " + field);
        }
        return value.asBoolean();
    }

    private static List<String> strings(JsonNode value) {
        if (!value.isArray()) {
            throw new IllegalArgumentException("shadow report regressions must be an array");
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("shadow report regression must be text");
            }
            result.add(item.asText());
        });
        return List.copyOf(result);
    }

    public record ShadowReportSummary(
            String suite,
            Instant generatedAt,
            int runsPerCase,
            boolean passed,
            List<String> regressions,
            int sampleCount
    ) {}
}
