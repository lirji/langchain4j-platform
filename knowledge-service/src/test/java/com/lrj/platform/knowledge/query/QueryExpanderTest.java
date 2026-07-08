package com.lrj.platform.knowledge.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询扩展纯逻辑测试（不连模型：改写经 {@link UnaryOperator} 桩注入）。
 */
class QueryExpanderTest {

    @Test
    void noop_returnsOnlyOriginal() {
        assertThat(new NoopQueryExpander().expand("退款怎么审批")).containsExactly("退款怎么审批");
    }

    @Test
    void llm_parsesVariants_dedups_stripsBullets_keepsOriginalFirst() {
        UnaryOperator<String> rewriter = p -> """
                1. 退款如何审批
                - 如何申请退款
                退款怎么审批
                "退款审批流程"
                """;
        List<String> out = new LlmQueryExpander(rewriter, 4).expand("退款怎么审批");
        assertThat(out).first().isEqualTo("退款怎么审批");        // 原 query 居首
        assertThat(out).contains("退款如何审批", "如何申请退款", "退款审批流程");
        assertThat(out).doesNotHaveDuplicates();
        assertThat(out).hasSizeLessThanOrEqualTo(4);            // 受 maxVariants 限制
        assertThat(out).noneMatch(v -> v.startsWith("-") || v.startsWith("1."));
    }

    @Test
    void llm_rewriterThrows_degradesToSingleQuery() {
        UnaryOperator<String> boom = p -> {
            throw new RuntimeException("llm down");
        };
        assertThat(new LlmQueryExpander(boom, 4).expand("q")).containsExactly("q");
    }

    @Test
    void llm_blankQuery_returnsSingleton() {
        assertThat(new LlmQueryExpander(p -> "x", 4).expand("")).containsExactly("");
    }
}
