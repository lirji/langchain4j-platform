package com.lrj.platform.knowledge.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
