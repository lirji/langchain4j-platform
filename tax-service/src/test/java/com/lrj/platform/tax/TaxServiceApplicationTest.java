package com.lrj.platform.tax;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证关闭外部 AI/RAG 依赖时，财税服务仍能以确定性模式完整启动。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.config.enabled=false",
                "app.tax.ai.enabled=false",
                "app.tax.knowledge.enabled=false",
                "platform.security.authentication-required=false"
        })
class TaxServiceApplicationTest {

    @Autowired
    private TaxInvoiceReviewService reviewService;

    @Autowired
    private TaxNarrator narrator;

    @Test
    void startsInDeterministicFallbackMode() {
        assertThat(reviewService).isNotNull();
        assertThat(narrator).isInstanceOf(DeterministicTaxNarrator.class);
    }
}
