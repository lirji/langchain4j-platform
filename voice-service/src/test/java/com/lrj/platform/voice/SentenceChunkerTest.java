package com.lrj.platform.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceChunkerTest {

    @Test
    void cutsOnEnderWhenLongEnough() {
        SentenceChunker chunker = new SentenceChunker(3);
        List<String> cut = chunker.feed("你好世界。再见");
        assertThat(cut).containsExactly("你好世界。");
        assertThat(chunker.flush()).isEqualTo("再见");
    }

    @Test
    void shortSentenceBelowMinCharsNotCutAlone() {
        SentenceChunker chunker = new SentenceChunker(8);
        // "好。" 太短（<8）不单独切；继续攒到够长再遇 ender 才切
        List<String> cut = chunker.feed("好。今天天气怎么样？");
        assertThat(cut).containsExactly("好。今天天气怎么样？");
    }

    @Test
    void multipleSentencesInOneFeed() {
        SentenceChunker chunker = new SentenceChunker(2);
        List<String> cut = chunker.feed("第一句。第二句！第三句？");
        assertThat(cut).containsExactly("第一句。", "第二句！", "第三句？");
        assertThat(chunker.flush()).isEmpty();
    }

    @Test
    void tokenByToken_accumulatesUntilEnder() {
        SentenceChunker chunker = new SentenceChunker(3);
        List<String> all = new ArrayList<>();
        for (String tok : List.of("退", "款", "需", "审", "批", "。")) {
            all.addAll(chunker.feed(tok));
        }
        assertThat(all).containsExactly("退款需审批。");
        assertThat(chunker.flush()).isEmpty();
    }

    @Test
    void flushReturnsRemainderWithoutEnder() {
        SentenceChunker chunker = new SentenceChunker(3);
        chunker.feed("没有结束标点");
        assertThat(chunker.flush()).isEqualTo("没有结束标点");
        assertThat(chunker.flush()).isEmpty();
    }

    @Test
    void nullOrEmptyToken_noop() {
        SentenceChunker chunker = new SentenceChunker(3);
        assertThat(chunker.feed(null)).isEmpty();
        assertThat(chunker.feed("")).isEmpty();
    }
}
