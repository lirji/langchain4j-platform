package com.lrj.platform.conversation;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagPromptAugmenterTest {

    @Test
    void disabledReturnsOriginalMessage() {
        RagPromptAugmenter augmenter = new RagPromptAugmenter(
                request -> new KnowledgeQueryReply(request.query(), "acme", List.of()),
                false,
                5,
                0.0,
                "",
                4000);

        assertThat(augmenter.augment("hello")).isEqualTo("hello");
    }

    @Test
    void enabledPrependsKnowledgeSources() {
        KnowledgeClient client = request -> new KnowledgeQueryReply(
                request.query(),
                "acme",
                List.of(new KnowledgeHit(
                        "hit-1",
                        0.9,
                        "doc-1",
                        "guide.md",
                        "manual",
                        "2",
                        "退款政策需要主管审批。",
                        "hybrid")));
        RagPromptAugmenter augmenter = new RagPromptAugmenter(client, true, 3, 0.2, "manual", 4000);

        String prompt = augmenter.augment("退款怎么审批？");

        assertThat(prompt).contains("[Knowledge sources]");
        assertThat(prompt).contains("<source id=\"guide.md#2\" type=\"hybrid\">");
        assertThat(prompt).contains("退款政策需要主管审批。");
        assertThat(prompt).contains("[User question]\n退款怎么审批？");
    }

    @Test
    void enabledPassesConfiguredRetrievalOptions() {
        CapturingKnowledgeClient client = new CapturingKnowledgeClient();
        RagPromptAugmenter augmenter = new RagPromptAugmenter(client, true, 7, 0.4, "manual", 4000);

        augmenter.augment("refund policy");

        assertThat(client.request).isEqualTo(new KnowledgeQueryRequest("refund policy", 7, 0.4, "manual"));
    }

    @Test
    void perRequestCategoryOverridesConfiguredDefault() {
        CapturingKnowledgeClient client = new CapturingKnowledgeClient();
        RagPromptAugmenter augmenter = new RagPromptAugmenter(client, true, 5, 0.0, "manual", 4000);

        augmenter.contextWithHits("退款怎么审批？", "policy");

        assertThat(client.request).isEqualTo(new KnowledgeQueryRequest("退款怎么审批？", 5, 0.0, "policy"));
    }

    @Test
    void blankPerRequestCategoryFallsBackToConfiguredDefault() {
        CapturingKnowledgeClient client = new CapturingKnowledgeClient();
        RagPromptAugmenter augmenter = new RagPromptAugmenter(client, true, 5, 0.0, "manual", 4000);

        augmenter.contextWithHits("hi", "   ");

        assertThat(client.request).isEqualTo(new KnowledgeQueryRequest("hi", 5, 0.0, "manual"));
    }

    private static class CapturingKnowledgeClient implements KnowledgeClient {
        private KnowledgeQueryRequest request;

        @Override
        public KnowledgeQueryReply query(KnowledgeQueryRequest request) {
            this.request = request;
            return new KnowledgeQueryReply(request.query(), "acme", List.of());
        }
    }
}
