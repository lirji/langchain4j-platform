package com.lrj.platform.tax;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证默认 AI 说明路径与确定性回退器可同时装配且主实现唯一。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.config.enabled=false",
                "app.tax.ai.enabled=true",
                "app.tax.knowledge.enabled=false",
                "platform.security.authentication-required=false"
        })
class TaxAiApplicationContextTest {

    @Autowired
    private TaxNarrator narrator;

    @Test
    void startsWithAiNarratorAsPrimaryImplementation() {
        assertThat(narrator).isInstanceOf(AiTaxNarrator.class);
    }
}
