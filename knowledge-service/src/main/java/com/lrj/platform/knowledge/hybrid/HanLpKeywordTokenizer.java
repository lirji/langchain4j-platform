package com.lrj.platform.knowledge.hybrid;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.dictionary.stopword.CoreStopWordDictionary;
import com.hankcs.hanlp.seg.common.Term;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HanLP 中文分词器（移植单体 {@code HanLpKeywordTokenizer}）。用内置 portable 词典（随 jar，无需外部下载），
 * 去停用词（{@link CoreStopWordDictionary}）与单字非中文 token，保留单字中文（HanLP 已合并常见多字词，
 * 剩下的单字通常是有意义的专名）。中文召回优于默认 bigram。
 *
 * <p>仅当 {@code app.rag.hybrid.tokenizer=hanlp} 时装配，替换默认 {@link SimpleKeywordTokenizer}。
 */
@Component
@ConditionalOnProperty(name = "app.rag.hybrid.tokenizer", havingValue = "hanlp")
public class HanLpKeywordTokenizer implements KeywordTokenizer {

    @Override
    public Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        List<Term> terms = HanLP.segment(text);
        Set<String> out = new HashSet<>(terms.size());
        for (Term t : terms) {
            String word = t.word == null ? "" : t.word.trim().toLowerCase();
            if (word.isEmpty()) {
                continue;
            }
            if (CoreStopWordDictionary.contains(word)) {
                continue;
            }
            // 丢纯标点 / 单字非中文
            if (word.length() == 1 && word.charAt(0) < 0x4E00) {
                continue;
            }
            out.add(word);
        }
        return out;
    }
}
