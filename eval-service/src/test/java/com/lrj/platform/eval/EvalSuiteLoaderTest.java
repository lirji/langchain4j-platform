package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalSuiteLoaderTest {

    @Test
    void loadsClasspathBaselineSuite() {
        EvalSuiteLoader loader = new EvalSuiteLoader(new ObjectMapper(), new EvalProperties());

        var suite = loader.load("platform-smoke");

        assertThat(suite.name()).isEqualTo("platform-smoke");
        assertThat(suite.cases()).hasSize(1);
        assertThat(suite.cases().getFirst().id()).isEqualTo("eval-capabilities");
        assertThat(suite.cases().getFirst().oracleContains()).isEqualTo("eval-service");
    }

    @Test
    void rejectsUnsafeSuiteName() {
        EvalSuiteLoader loader = new EvalSuiteLoader(new ObjectMapper(), new EvalProperties());

        assertThatThrownBy(() -> loader.load("../secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid suite name");
    }
}
