package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.client.KnowledgeClient;
import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code rag_search} 动作：在企业知识库检索资料。通过 {@link KnowledgeClient} 调 knowledge-service 的 RAG，
 * 按配置的 top-k/min-score/category 发起 {@link KnowledgeQueryRequest}，把命中片段截断后以带 {@code [doc=ID]}
 * 标记的形式拼成观察文本，便于最终答案引用来源。是 {@link AgentAction} 可插拔实现，由 {@code app.agent.enabled} 门控（默认开）。
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class RagSearchAction implements AgentAction {

    private static final int MAX_SNIPPET_CHARS = 600;

    private final KnowledgeClient knowledgeClient;
    private final int topK;
    private final Double minScore;
    private final String category;

    public RagSearchAction(KnowledgeClient knowledgeClient,
                           @Value("${app.agent.knowledge.top-k:5}") int topK,
                           @Value("${app.agent.knowledge.min-score:0.0}") Double minScore,
                           @Value("${app.agent.knowledge.category:}") String category) {
        this.knowledgeClient = knowledgeClient;
        this.topK = Math.max(1, topK);
        this.minScore = minScore;
        this.category = category == null || category.isBlank() ? null : category;
    }

    @Override
    public String name() {
        return "rag_search";
    }

    @Override
    public String description() {
        return "在企业知识库里检索资料；actionInput 填关键词或问题。返回带 [doc=ID] 的片段，最终答案可引用这些 id。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "检索词为空：actionInput 请填要查的关键词或问题。";
        }
        KnowledgeQueryReply reply = knowledgeClient.query(
                new KnowledgeQueryRequest(input.trim(), topK, minScore, category));
        List<KnowledgeHit> hits = reply.hits().stream()
                .filter(hit -> hit.text() != null && !hit.text().isBlank())
                .limit(topK)
                .toList();
        if (hits.isEmpty()) {
            return "知识库里没有检索到与「" + input.trim() + "」相关的资料。";
        }
        StringBuilder sb = new StringBuilder("检索到 " + hits.size() + " 条片段：\n");
        for (KnowledgeHit hit : hits) {
            String text = hit.text().trim();
            if (text.length() > MAX_SNIPPET_CHARS) {
                text = text.substring(0, MAX_SNIPPET_CHARS) + "...";
            }
            sb.append("[doc=").append(sourceId(hit)).append("] ");
            if (hit.source() != null && !hit.source().isBlank()) {
                sb.append("(").append(hit.source()).append(") ");
            }
            sb.append(text).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String sourceId(KnowledgeHit hit) {
        String displayName = hit.displayName() == null || hit.displayName().isBlank() ? "doc" : hit.displayName();
        String index = hit.index() == null || hit.index().isBlank() ? "0" : hit.index();
        return displayName + "#" + index;
    }
}
