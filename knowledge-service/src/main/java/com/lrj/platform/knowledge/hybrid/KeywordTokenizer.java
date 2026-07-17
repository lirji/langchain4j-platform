package com.lrj.platform.knowledge.hybrid;

import java.util.Set;

/**
 * 关键词分词接口：把文本切成用于词法检索/实体匹配的 token 集合，供 {@link KeywordSearchService} 与
 * GraphRAG 的实体链接复用。默认实现为 {@code SimpleKeywordTokenizer}。
 */
public interface KeywordTokenizer {

    Set<String> tokenize(String text);
}
