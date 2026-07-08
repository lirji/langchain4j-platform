package com.lrj.platform.knowledge.hybrid;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HanLP 分词器测试（用内置 portable 词典，离线可跑）。
 */
class HanLpKeywordTokenizerTest {

    private final HanLpKeywordTokenizer tokenizer = new HanLpKeywordTokenizer();

    @Test
    void segmentsChinese_dropsStopwords() {
        Set<String> tokens = tokenizer.tokenize("退款审批的流程是怎样的");
        // 关键词被切出
        assertThat(tokens).contains("退款", "审批", "流程");
        // 常见停用词/助词被丢
        assertThat(tokens).doesNotContain("的", "是");
    }

    @Test
    void blankOrNull_returnsEmpty() {
        assertThat(tokenizer.tokenize("")).isEmpty();
        assertThat(tokenizer.tokenize(null)).isEmpty();
    }

    @Test
    void lowercasesLatinTokens() {
        assertThat(tokenizer.tokenize("Refund 政策")).contains("refund", "政策");
    }
}
