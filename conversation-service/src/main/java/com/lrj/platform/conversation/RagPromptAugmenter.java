package com.lrj.platform.conversation;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagPromptAugmenter {

    private final KnowledgeClient knowledgeClient;
    private final boolean enabled;
    private final int topK;
    private final Double minScore;
    private final String category;
    private final int maxContextChars;

    public RagPromptAugmenter(KnowledgeClient knowledgeClient,
                              @Value("${app.conversation.rag.enabled:false}") boolean enabled,
                              @Value("${app.conversation.rag.top-k:5}") int topK,
                              @Value("${app.conversation.rag.min-score:0.0}") Double minScore,
                              @Value("${app.conversation.rag.category:}") String category,
                              @Value("${app.conversation.rag.max-context-chars:4000}") int maxContextChars) {
        this.knowledgeClient = knowledgeClient;
        this.enabled = enabled;
        this.topK = Math.max(1, topK);
        this.minScore = minScore;
        this.category = category == null || category.isBlank() ? null : category;
        this.maxContextChars = Math.max(256, maxContextChars);
    }

    public String augment(String message) {
        if (!enabled || message == null || message.isBlank()) {
            return message;
        }
        KnowledgeQueryReply reply = knowledgeClient.query(new KnowledgeQueryRequest(message, topK, minScore, category));
        List<KnowledgeHit> hits = reply.hits().stream()
                .filter(hit -> hit.text() != null && !hit.text().isBlank())
                .toList();
        if (hits.isEmpty()) {
            return message;
        }
        StringBuilder context = new StringBuilder();
        context.append("[Knowledge sources]\n");
        int used = 0;
        for (KnowledgeHit hit : hits) {
            String sourceId = sourceId(hit);
            String block = "<source id=\"" + sourceId + "\" type=\"" + hit.source() + "\">\n"
                    + hit.text().trim()
                    + "\n</source>\n";
            if (used + block.length() > maxContextChars) {
                break;
            }
            context.append(block);
            used += block.length();
        }
        if (used == 0) {
            return message;
        }
        context.append("\n[User question]\n").append(message);
        return context.toString();
    }

    private static String sourceId(KnowledgeHit hit) {
        String displayName = hit.displayName() == null || hit.displayName().isBlank() ? "doc" : hit.displayName();
        String index = hit.index() == null || hit.index().isBlank() ? "0" : hit.index();
        return displayName + "#" + index;
    }
}
