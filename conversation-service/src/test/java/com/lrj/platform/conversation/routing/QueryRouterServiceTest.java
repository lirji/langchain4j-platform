package com.lrj.platform.conversation.routing;

import com.lrj.platform.conversation.Assistant;
import com.lrj.platform.conversation.RagPromptAugmenter;
import com.lrj.platform.conversation.prompt.ResolvedAssistantStyle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QueryRouterServiceTest：验证 {@link QueryRouterService#route} 按 {@link RouteKind} 分档——RAG 档检索并注入上下文、
 * CHAT/TOOL 档不检索，以及分类器异常时回退 RAG。
 */
class QueryRouterServiceTest {

    private static final ResolvedAssistantStyle STYLE = new ResolvedAssistantStyle("中文", "简洁", "cite", "");
    private static final String KEY = "acme::c1";

    @Test
    void ragRoute_usesRetrievedContext() {
        QueryClassifier classifier = mock(QueryClassifier.class);
        Assistant assistant = mock(Assistant.class);
        RagPromptAugmenter augmenter = mock(RagPromptAugmenter.class);
        when(classifier.classify("本项目用什么数据库")).thenReturn(new RouteDecision(RouteKind.RAG, "查文档"));
        when(augmenter.contextFor("本项目用什么数据库")).thenReturn("ctx");
        when(assistant.chat(eq(KEY), any(), any(), any(), any(), eq("本项目用什么数据库"), eq("ctx")))
                .thenReturn("MySQL");
        QueryRouterService svc = new QueryRouterService(classifier, assistant, augmenter, STYLE);

        RoutedReply r = svc.route(KEY, "本项目用什么数据库");

        assertThat(r.decision().kind()).isEqualTo(RouteKind.RAG);
        assertThat(r.reply()).isEqualTo("MySQL");
        verify(augmenter).contextFor("本项目用什么数据库");
    }

    @Test
    void chatRoute_bareNoRetrieval() {
        QueryClassifier classifier = mock(QueryClassifier.class);
        Assistant assistant = mock(Assistant.class);
        RagPromptAugmenter augmenter = mock(RagPromptAugmenter.class);
        when(classifier.classify("什么是 RAG")).thenReturn(new RouteDecision(RouteKind.CHAT, "通用概念"));
        when(assistant.chat(eq(KEY), any(), any(), any(), any(), eq("什么是 RAG"), eq("")))
                .thenReturn("检索增强生成");
        QueryRouterService svc = new QueryRouterService(classifier, assistant, augmenter, STYLE);

        RoutedReply r = svc.route(KEY, "什么是 RAG");

        assertThat(r.decision().kind()).isEqualTo(RouteKind.CHAT);
        assertThat(r.reply()).isEqualTo("检索增强生成");
        // CHAT 档不检索
        verifyNoInteractions(augmenter);
    }

    @Test
    void toolRoute_bareNoRetrieval() {
        QueryClassifier classifier = mock(QueryClassifier.class);
        Assistant assistant = mock(Assistant.class);
        RagPromptAugmenter augmenter = mock(RagPromptAugmenter.class);
        when(classifier.classify("现在几点")).thenReturn(new RouteDecision(RouteKind.TOOL, "要当前时间"));
        when(assistant.chat(eq(KEY), any(), any(), any(), any(), eq("现在几点"), eq("")))
                .thenReturn("现在是 10 点");
        QueryRouterService svc = new QueryRouterService(classifier, assistant, augmenter, STYLE);

        RoutedReply r = svc.route(KEY, "现在几点");

        assertThat(r.decision().kind()).isEqualTo(RouteKind.TOOL);
        verifyNoInteractions(augmenter);
    }

    @Test
    void classifierException_fallsBackToRag() {
        QueryClassifier classifier = mock(QueryClassifier.class);
        Assistant assistant = mock(Assistant.class);
        RagPromptAugmenter augmenter = mock(RagPromptAugmenter.class);
        when(classifier.classify("x")).thenThrow(new RuntimeException("judge down"));
        when(augmenter.contextFor("x")).thenReturn("ctx");
        when(assistant.chat(eq(KEY), any(), any(), any(), any(), eq("x"), eq("ctx"))).thenReturn("ans");
        QueryRouterService svc = new QueryRouterService(classifier, assistant, augmenter, STYLE);

        RoutedReply r = svc.route(KEY, "x");

        assertThat(r.decision().kind()).isEqualTo(RouteKind.RAG);
        assertThat(r.decision().reason()).contains("fallback");
        verify(augmenter).contextFor("x");
    }
}
