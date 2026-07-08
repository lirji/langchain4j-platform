package com.lrj.platform.eval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：{@link EvalRunner} 有两个构造器（生产 4 参 + 单测便捷 2 参），
 * 生产构造器必须标 {@code @Autowired}，否则 Spring 因多构造器回退无参构造器、
 * 导致 EvalRunner→EvalDualRunner 装配失败、eval-service 起不来。
 */
class EvalRunnerWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
            .withUserConfiguration(EvalConfig.class, EvalRunner.class);

    @Test
    void evalRunnerWiresWithDefaultDisabledJudgeAndEmbedding() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(EvalRunner.class));
    }

    @Test
    void defaultsUseDisabledImplementations() {
        runner.run(ctx -> {
            assertThat(ctx).getBean(EvalJudge.class).isInstanceOf(DisabledEvalJudge.class);
            assertThat(ctx).getBean(EvalEmbeddingComparator.class)
                    .isInstanceOf(DisabledEvalEmbeddingComparator.class);
        });
    }
}
