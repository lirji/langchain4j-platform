package com.lrj.platform.edge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRouteIsolationTest {

    @Test
    void evalControlPlaneIsNotPublishedByDefaultEdgeRoutes() throws IOException {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml).doesNotContain("id: eval");
            assertThat(yaml).doesNotContain("Path=/eval");
        }
    }
}
