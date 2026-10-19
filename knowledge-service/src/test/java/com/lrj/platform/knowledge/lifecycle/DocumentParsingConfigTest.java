package com.lrj.platform.knowledge.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证生产配置真正约束注入的解析器，避免只在手工构造测试中生效。 */
class DocumentParsingConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(DocumentParsingProperties.class, DocumentTextExtractor.class);

    @Test
    void configuredLimitIsAppliedToTheInjectedParser() {
        context.withPropertyValues("app.rag.parsing.max-text-chars=30").run(app -> {
            assertThat(app).hasNotFailed();
            assertThatThrownBy(() -> app.getBean(DocumentTextExtractor.class).extract(
                    new ByteArrayInputStream("x".repeat(100).getBytes(StandardCharsets.UTF_8)), "large.txt"))
                    .isInstanceOfSatisfying(DocumentParsingException.class,
                            ex -> assertThat(ex.error()).isEqualTo(DocumentParsingError.TEXT_LIMIT));
        });
    }

    @Test
    void invalidLimitFailsConfigurationBinding() {
        context.withPropertyValues("app.rag.parsing.max-text-chars=-1").run(app -> assertThat(app).hasFailed());
    }

    /** 使用真实 application.yml 验证默认开关，防止 Java 默认值与运行配置相互覆盖。 */
    @Test
    void applicationDefaultsEnableOcrAndPassItToParserProcess() {
        context.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.cloud.config.enabled=false")
                .run(app -> {
                    assertThat(app).hasNotFailed();
                    var policy = app.getBean(DocumentParsingProperties.class).getOcr();
                    assertThat(policy.isEnabled()).isTrue();
                    assertThat(policy.snapshot().enabled()).isTrue();
                    assertThat(policy.getLanguages()).isEqualTo("chi_sim+eng");
                });
    }

    /** 部署仍可显式关闭 OCR，且子进程收到同一关闭策略。 */
    @Test
    void explicitFalseDisablesOcrInParentAndChildSettings() {
        context.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.cloud.config.enabled=false", "RAG_PARSING_OCR_ENABLED=false")
                .run(app -> {
                    assertThat(app).hasNotFailed();
                    var policy = app.getBean(DocumentParsingProperties.class).getOcr();
                    assertThat(policy.isEnabled()).isFalse();
                    assertThat(policy.snapshot().enabled()).isFalse();
                });
    }
}
