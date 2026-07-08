package com.lrj.platform.eval.retrieval;

import java.util.List;

/**
 * 检索客户端：把 query 打到 knowledge-service，取回按相关性降序的文档 id（{@code displayName#index}）。
 * 藏在接口后使 {@link RetrievalEvaluator} 可对 stub 确定性单测、不连网络。
 */
public interface RetrievalClient {

    List<String> retrieve(String targetBaseUrl, String question, Integer topK, String category);
}
