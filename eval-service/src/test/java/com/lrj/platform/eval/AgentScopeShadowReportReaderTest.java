package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentScopeShadowReportReaderTest {

    private final AgentScopeShadowReportReader reader =
            new AgentScopeShadowReportReader(new ObjectMapper());

    @Test
    void readsPythonSnakeCaseShadowArtifactWithoutOwningExecution() {
        var summary = reader.read(json("""
                {
                  "schema_version":"4",
                  "suite":"readonly-cases",
                  "generated_at":"2026-07-30T00:00:00Z",
                  "runs_per_case":2,
                  "dataset":{
                    "schemaVersion":"agent-evaluation-dataset-ref.v1",
                    "datasetId":"readonly-cases",
                    "version":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "kind":"baseline"
                  },
                  "gate":{
                    "passed":false,
                    "regressions":["candidate completion rate regressed"],
                    "thresholds":{},
                    "legacy":{},
                    "candidate":{}
                  },
                  "samples":[
                    {"case_id":"c1","target":"legacy"},
                    {"case_id":"c1","target":"candidate"}
                  ]
                }
                """));

        assertThat(summary.suite()).isEqualTo("readonly-cases");
        assertThat(summary.runsPerCase()).isEqualTo(2);
        assertThat(summary.passed()).isFalse();
        assertThat(summary.regressions()).containsExactly(
                "candidate completion rate regressed");
        assertThat(summary.sampleCount()).isEqualTo(2);
        assertThat(summary.datasetId()).isEqualTo("readonly-cases");
        assertThat(summary.datasetVersion()).isEqualTo(
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    @Test
    void rejectsLegacyCamelCaseOrIncompleteArtifacts() {
        assertThatThrownBy(() -> reader.read(json("""
                {"suite":"x","generatedAt":"2026-07-30T00:00:00Z","runsPerCase":1}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema_version");
    }

    @Test
    void rejectsPreVersionedOrDatasetFreeReports() {
        assertThatThrownBy(() -> reader.read(json("""
                {
                  "schema_version":"3",
                  "suite":"x",
                  "generated_at":"2026-07-30T00:00:00Z",
                  "runs_per_case":1,
                  "gate":{"passed":true,"regressions":[]},
                  "samples":[]
                }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema_version");
    }

    private static ByteArrayInputStream json(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
