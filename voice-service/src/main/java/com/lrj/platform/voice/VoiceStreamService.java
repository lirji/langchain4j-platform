package com.lrj.platform.voice;

import com.lrj.platform.security.PublicPayloadRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE 半流式语音：上行一次整段音频，下行 SSE 流式推回——
 * <ol>
 *   <li>整段 ASR → {@code transcript} 事件</li>
 *   <li>消费 conversation {@code /chat/stream} 的 token 流 → {@link SentenceChunker} 逐 token 凑句，
 *       每句 TTS 发一个 {@code audio-chunk} 事件（{@code {text, audioContentType, audioBase64}}），客户端边收边播</li>
 *   <li>收口发剩余尾句 + {@code done}</li>
 * </ol>
 *
 * <p><strong>v2 真 token 流式</strong>：单体在同进程 {@code Assistant.chatStream} 逐 token 分句 TTS；v2 经
 * {@link ConversationClient#chatStream} 消费 conversation {@code /chat/stream} 的 SSE，token 一到就喂 chunker、
 * 凑够一句立即 TTS 发出——首句延迟随生成推进而非等整段回复，逼近单体逐 token 行为。
 */
public class VoiceStreamService {

    private static final Logger log = LoggerFactory.getLogger(VoiceStreamService.class);
    private static final String NOT_UNDERSTOOD = "抱歉，我没有听清，请您再说一遍。";

    private final ConversationClient conversation;
    private final SpeechService speech;
    private final int minChars;
    private final Executor executor;
    private final PublicPayloadRedactor redactor;

    public VoiceStreamService(ConversationClient conversation, SpeechService speech, int minChars) {
        this(conversation, speech, minChars, Runnable::run,
                PublicPayloadRedactor.standalone());
    }

    public VoiceStreamService(ConversationClient conversation, SpeechService speech, int minChars,
                              Executor executor) {
        this(conversation, speech, minChars, executor,
                PublicPayloadRedactor.standalone());
    }

    public VoiceStreamService(ConversationClient conversation, SpeechService speech, int minChars,
                              Executor executor, PublicPayloadRedactor redactor) {
        this.conversation = conversation;
        this.speech = speech;
        this.minChars = minChars;
        this.executor = executor;
        this.redactor = redactor;
    }

    public SseEmitter stream(byte[] audio, String filename, String chatId) {
        SseEmitter emitter = new SseEmitter(180_000L);
        VoiceStreamCancellation cancellation = new VoiceStreamCancellation();
        emitter.onCompletion(cancellation::cancel);
        emitter.onTimeout(() -> {
            cancellation.cancel();
            emitter.complete();
        });
        emitter.onError(ignored -> cancellation.cancel());
        try {
            executor.execute(() -> run(emitter, cancellation, audio, filename, chatId));
        } catch (RuntimeException error) {
            fail(emitter, cancellation, error);
        }
        return emitter;
    }

    private void run(SseEmitter emitter, VoiceStreamCancellation cancellation,
                     byte[] audio, String filename, String chatId) {
        if (cancellation.isCancelled()) {
            return;
        }
        String transcript;
        try {
            transcript = speech.transcribe(audio, filename);
            if (cancellation.isCancelled()) {
                return;
            }
            emitter.send(SseEmitter.event().name("transcript")
                    .data(publicText(transcript)));
        } catch (Exception e) {
            fail(emitter, cancellation, e);
            return;
        }

        if (transcript == null || transcript.isBlank()) {
            try {
                emitSentence(emitter, NOT_UNDERSTOOD);
                emitter.send(SseEmitter.event().name("done").data(""));
            } catch (IOException ignored) {
                cancellation.cancel();
            }
            cancellation.finish();
            emitter.complete();
            return;
        }

        try {
            SentenceChunker chunker = new SentenceChunker(minChars);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            // 真 token 流式：每到一批 token 就喂 chunker，凑够一句立即 TTS。onDone 无操作——
            // 尾句 flush + done 在流阻塞返回后统一收口；onError 记录失败留待收口处理。
            conversation.chatStream(chatId, transcript, cancellation,
                    token -> {
                        if (cancellation.isCancelled()) {
                            return;
                        }
                        for (String sentence : chunker.feed(token)) {
                            emitSentenceQuietly(emitter, sentence, cancellation);
                        }
                    },
                    () -> { },
                    failure::set);

            if (failure.get() != null) {
                fail(emitter, cancellation, failure.get());
                return;
            }
            if (!cancellation.isCancelled()) {
                String tail = chunker.flush();
                if (!tail.isBlank()) {
                    emitSentence(emitter, tail);
                }
                emitter.send(SseEmitter.event().name("done").data(""));
            }
            cancellation.finish();
            emitter.complete();
        } catch (Throwable e) {
            fail(emitter, cancellation, e);
        }
    }

    /** 发一句，客户端已断开（IOException）则置 cancelled 并静默——不因断连抛断整条流。 */
    private void emitSentenceQuietly(SseEmitter emitter, String sentence,
                                     VoiceStreamCancellation cancellation) {
        try {
            emitSentence(emitter, sentence);
        } catch (IOException e) {
            cancellation.cancel();
        }
    }

    static void fail(SseEmitter emitter, VoiceStreamCancellation cancellation, Throwable error) {
        log.warn("voice stream failed errorType={}", error.getClass().getSimpleName());
        if (cancellation.isCancelled()) {
            return;
        }
        cancellation.finish();
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "error", "voice stream failed",
                    "code", "VOICE_STREAM_FAILED")));
        } catch (IOException | IllegalStateException ignored) {
            // The downstream is already closed.
        }
        emitter.complete();
    }

    /** 一句 → 剥引用标记 → TTS → 一个 audio-chunk 事件。 */
    private void emitSentence(SseEmitter emitter, String sentence) throws IOException {
        String safeSentence = publicText(sentence);
        String spoken = VoiceConversationService.stripCitations(safeSentence);
        SpeechService.Speech tts = speech.synthesize(spoken);
        emitter.send(SseEmitter.event().name("audio-chunk").data(Map.of(
                "text", safeSentence,
                "audioContentType", tts.contentType(),
                "audioBase64", Base64.getEncoder().encodeToString(tts.audio()))));
    }

    String publicText(String value) {
        return String.valueOf(redactor.redact(value == null ? "" : value));
    }
}
