package com.lrj.platform.conversation.vision;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配守卫：视觉对话默认关时应装配 {@link NoopVisionClient}（enabled=false），
 * 确保 {@code /chat/vision} 走禁用分支、不试图连 vision-service。
 */
class VisionClientWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NoopVisionClient.class, HttpVisionClient.class);

    @Test
    void disabledByDefault_noopClientPresent() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(VisionClient.class);
            assertThat(ctx.getBean(VisionClient.class)).isInstanceOf(NoopVisionClient.class);
            assertThat(ctx.getBean(VisionClient.class).enabled()).isFalse();
        });
    }

    @Test
    void explicitlyDisabled_noopClientPresent() {
        runner.withPropertyValues("app.conversation.vision.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(VisionClient.class)).isInstanceOf(NoopVisionClient.class));
    }
}
