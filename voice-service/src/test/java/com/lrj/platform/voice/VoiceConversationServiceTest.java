package com.lrj.platform.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * VoiceConversationServiceTest：验证 {@link VoiceConversationService#chat} 的语音闭环——正常轮次先 ASR 转写、
 * 再经 {@link ConversationClient} 对话、TTS 合成时剥除引用标记（文本侧保留），route=CHAT；空白转写直接回退提示
 * 且不调用对话（不烧 token），route=NONE；并覆盖 {@code stripCitations} 去除 {@code [doc=...]} 标记。
 */
class VoiceConversationServiceTest {

    private static SpeechService.Speech tts(String tag) {
        return new SpeechService.Speech(tag.getBytes(), "audio/mpeg");
    }

    @Test
    void chat_normalTurn_transcribesRepliesAndSynthesizes() {
        SpeechService speech = mock(SpeechService.class);
        ConversationClient conversation = mock(ConversationClient.class);
        when(speech.transcribe(any(), any())).thenReturn("退款怎么审批");
        when(conversation.chat("c1", "退款怎么审批")).thenReturn("退款需主管审批 [doc=guide.md#2]");
        when(speech.synthesize("退款需主管审批")).thenReturn(tts("audio")); // 已剥引用
        VoiceConversationService svc = new VoiceConversationService(speech, conversation);

        VoiceConversationService.VoiceReply reply = svc.chat(new byte[]{1}, "a.mp3", "c1");

        assertThat(reply.transcript()).isEqualTo("退款怎么审批");
        assertThat(reply.reply()).isEqualTo("退款需主管审批 [doc=guide.md#2]"); // 文本侧保留引用
        assertThat(reply.route()).isEqualTo("CHAT");
        assertThat(reply.audioBase64()).isNotBlank();
    }

    @Test
    void chat_blankTranscript_fallsBackWithoutCallingConversation() {
        SpeechService speech = mock(SpeechService.class);
        ConversationClient conversation = mock(ConversationClient.class);
        when(speech.transcribe(any(), any())).thenReturn("   ");
        when(speech.synthesize(anyString())).thenReturn(tts("fallback"));
        VoiceConversationService svc = new VoiceConversationService(speech, conversation);

        VoiceConversationService.VoiceReply reply = svc.chat(new byte[]{1}, "a.mp3", "c1");

        assertThat(reply.route()).isEqualTo("NONE");
        assertThat(reply.reply()).contains("没有听清");
        // 空转写不进对话，不烧 token
        verifyNoInteractions(conversation);
    }

    @Test
    void stripCitations_removesDocMarkers() {
        // [doc=...] 剥除后多余空白折叠：结果不含任何 [doc= 标记
        String out = VoiceConversationService.stripCitations("答案 [doc=a#1] 见此 [doc=b#2]。");
        assertThat(out).doesNotContain("[doc=").contains("答案").contains("见此");
        assertThat(VoiceConversationService.stripCitations(null)).isEmpty();
    }
}
