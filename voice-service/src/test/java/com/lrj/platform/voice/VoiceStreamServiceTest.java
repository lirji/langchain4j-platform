package com.lrj.platform.voice;

import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VoiceStreamService 真 token 流式：逐 token 喂 SentenceChunker，凑够一句立即 TTS。
 * 断言按句调用 {@code speech.synthesize}（每句一次 audio-chunk），不检查 SSE 内部帧。
 */
class VoiceStreamServiceTest {

    private static SpeechService.Speech tts() {
        return new SpeechService.Speech("audio".getBytes(), "audio/mpeg");
    }

    /** 逐 token 到达 → 每凑够一句 TTS 一次（引用标记 TTS 前剥离）。 */
    @Test
    void realTokenStream_synthesizesPerSentence() {
        SpeechService speech = mock(SpeechService.class);
        when(speech.transcribe(any(), any())).thenReturn("我要退款");
        when(speech.synthesize(anyString())).thenReturn(tts());
        // 桩：真流式吐 4 个 token 拼成 2 句
        ConversationClient conversation = new ConversationClient() {
            @Override
            public String chat(String chatId, String message) {
                return "";
            }

            @Override
            public void chatStream(String chatId, String message,
                                   Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError) {
                onToken.accept("退款");
                onToken.accept("需主管审批。");
                onToken.accept("请");
                onToken.accept("稍候。");
                onDone.run();
            }
        };
        VoiceStreamService svc = new VoiceStreamService(conversation, speech, 1);

        svc.stream(new byte[]{1}, "a.mp3", "c1");

        verify(speech).synthesize("退款需主管审批。");
        verify(speech).synthesize("请稍候。");
    }

    /** 默认降级：桩只实现 unary chat → 默认 chatStream 整段作单 token → chunker 仍按句切、按句 TTS。 */
    @Test
    void defaultFallback_splitsWholeReplyIntoSentences() {
        SpeechService speech = mock(SpeechService.class);
        when(speech.transcribe(any(), any())).thenReturn("我要退款");
        when(speech.synthesize(anyString())).thenReturn(tts());
        ConversationClient conversation = (chatId, message) -> "退款需主管审批。请稍候。";
        VoiceStreamService svc = new VoiceStreamService(conversation, speech, 1);

        svc.stream(new byte[]{1}, "a.mp3", "c1");

        verify(speech).synthesize("退款需主管审批。");
        verify(speech).synthesize("请稍候。");
    }

    /** 空转写：兜底话术 TTS 一次，不进对话流。 */
    @Test
    void blankTranscript_fallsBackWithoutStreaming() {
        SpeechService speech = mock(SpeechService.class);
        when(speech.transcribe(any(), any())).thenReturn("  ");
        when(speech.synthesize(anyString())).thenReturn(tts());
        ConversationClient conversation = mock(ConversationClient.class);
        VoiceStreamService svc = new VoiceStreamService(conversation, speech, 1);

        svc.stream(new byte[]{1}, "a.mp3", "c1");

        verify(speech).synthesize("抱歉，我没有听清，请您再说一遍。");
        verify(conversation, never()).chatStream(any(), any(), any(), any(), any());
    }
}
