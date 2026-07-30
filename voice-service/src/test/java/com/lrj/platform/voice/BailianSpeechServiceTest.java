package com.lrj.platform.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BailianSpeechServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class RecordingService extends BailianSpeechService {
        Map<String, Object> lastPayload;
        JsonNode nextResponse;
        byte[] downloaded = "downloaded-wav".getBytes(StandardCharsets.UTF_8);
        String downloadedUrl;

        RecordingService(VoiceProperties props) {
            super(props, MAPPER);
        }

        @Override
        protected JsonNode postJson(Map<String, Object> payload, String operation) {
            this.lastPayload = payload;
            return nextResponse;
        }

        @Override
        protected byte[] downloadAudio(String url) {
            this.downloadedUrl = url;
            return downloaded;
        }
    }

    private static VoiceProperties props() {
        VoiceProperties props = new VoiceProperties();
        props.setProvider("bailian");
        props.setBaseUrl("https://example.aliyuncs.com/api/v1");
        props.setApiKey("test-key");
        props.setAsrModel("qwen3-asr-flash");
        props.setTtsModel("qwen3-tts-flash");
        props.setTtsVoice("Cherry");
        props.setTtsFormat("wav");
        props.setLanguage("zh");
        return props;
    }

    @Test
    void transcribeSendsBase64AudioAndParsesText() throws Exception {
        RecordingService service = new RecordingService(props());
        service.nextResponse = MAPPER.readTree("""
                {"output":{"choices":[{"message":{"content":[{"text":"你好，世界。"}]}}]}}
                """);

        assertThat(service.transcribe(new byte[]{1, 2, 3}, "sample.wav")).isEqualTo("你好，世界。");
        String payload = MAPPER.writeValueAsString(service.lastPayload);
        assertThat(payload)
                .contains("\"model\":\"qwen3-asr-flash\"")
                .contains("\"audio\":\"data:audio/wav;base64,AQID\"")
                .contains("\"language\":\"zh\"")
                .contains("\"enable_itn\":true");
    }

    @Test
    void synthesizeDecodesInlineAudio() throws Exception {
        RecordingService service = new RecordingService(props());
        service.nextResponse = MAPPER.readTree("""
                {"output":{"audio":{"data":"d2F2","url":""}}}
                """);

        SpeechService.Speech speech = service.synthesize("你好");
        assertThat(speech.audio()).isEqualTo("wav".getBytes(StandardCharsets.UTF_8));
        assertThat(speech.contentType()).isEqualTo("audio/wav");
        assertThat(MAPPER.writeValueAsString(service.lastPayload))
                .contains("\"voice\":\"Cherry\"")
                .contains("\"language_type\":\"Chinese\"");
    }

    @Test
    void synthesizeDownloadsProviderAudioUrl() throws Exception {
        RecordingService service = new RecordingService(props());
        service.nextResponse = MAPPER.readTree("""
                {"output":{"audio":{"data":"","url":"https://result.aliyuncs.com/audio.wav"}}}
                """);

        SpeechService.Speech speech = service.synthesize("你好");
        assertThat(speech.audio()).isEqualTo(service.downloaded);
        assertThat(service.downloadedUrl).isEqualTo("https://result.aliyuncs.com/audio.wav");
    }

    @Test
    void rejectsEmptyInputsAndMissingOutputs() throws Exception {
        RecordingService service = new RecordingService(props());
        assertThatThrownBy(() -> service.transcribe(new byte[0], "empty.wav"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.synthesize(" "))
                .isInstanceOf(IllegalArgumentException.class);

        service.nextResponse = MAPPER.readTree("{\"output\":{\"audio\":{}}}");
        assertThatThrownBy(() -> service.synthesize("你好"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neither audio data nor URL");
    }
}
