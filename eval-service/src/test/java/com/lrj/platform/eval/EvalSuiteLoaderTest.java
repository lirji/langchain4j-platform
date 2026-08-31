package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvalSuiteLoaderTest：验证 {@link EvalSuiteLoader} 能从 classpath 加载基线用例集，
 * 并对不安全的 suite 名（如路径穿越 {@code ../secret}）抛出 IllegalArgumentException。
 */
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
    void loadsTaxInvoiceGoldenCasesWithDeterministicAssertions() {
        EvalSuiteLoader loader = new EvalSuiteLoader(new ObjectMapper(), new EvalProperties());

        var suite = loader.load("tax-invoice-risk");

        assertThat(suite.name()).isEqualTo("tax-invoice-risk");
        assertThat(suite.cases()).hasSize(4);
        assertThat(suite.cases()).extracting("id").containsExactly(
                "tax-clear-consistent-invoice",
                "tax-high-amount-mismatch",
                "tax-medium-outside-period",
                "tax-high-duplicate-invoice");
        assertThat(suite.cases().get(1).expectedJsonPaths())
                .containsEntry("$.overallRisk", "HIGH")
                .containsEntry("$.findingCount", 2);
    }

    @Test
    void rejectsUnsafeSuiteName() {
        EvalSuiteLoader loader = new EvalSuiteLoader(new ObjectMapper(), new EvalProperties());

        assertThatThrownBy(() -> loader.load("../secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid suite name");
    }
}
