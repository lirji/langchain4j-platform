package com.lrj.platform.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 阿里云百炼原生语音实现。Qwen3-ASR 与 Qwen3-TTS 都使用 DashScope
 * {@code /services/aigc/multimodal-generation/generation}，不冒充 OpenAI audio 协议。
 */
public class BailianSpeechService implements SpeechService {

    private static final Logger log = LoggerFactory.getLogger(BailianSpeechService.class);
    private static final String GENERATION_PATH =
            "/services/aigc/multimodal-generation/generation";

    private final VoiceProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public BailianSpeechService(VoiceProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        log.info("BailianSpeechService ready: base-url={} asr={} tts={} voice={}",
                trimSlash(props.getBaseUrl()), props.getAsrModel(), props.getTtsModel(), props.getTtsVoice());
    }

    @Override
    public String transcribe(byte[] audio, String filename) {
        if (audio == null || audio.length == 0) {
            throw new IllegalArgumentException("audio is empty");
        }
        String dataUri = "data:" + audioMime(filename) + ";base64,"
                + Base64.getEncoder().encodeToString(audio);

        Map<String, Object> audioItem = Map.of("audio", dataUri);
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", List.of(audioItem));

        Map<String, Object> asrOptions = new LinkedHashMap<>();
        asrOptions.put("enable_itn", true);
        if (props.getLanguage() != null && !props.getLanguage().isBlank()) {
            asrOptions.put("language", props.getLanguage());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getAsrModel());
        payload.put("input", Map.of("messages", List.of(userMessage)));
        payload.put("parameters", Map.of("asr_options", asrOptions));

        JsonNode root = postJson(payload, "ASR");
        String text = root.path("output").path("choices").path(0)
                .path("message").path("content").path(0).path("text").asText("");
        if (text.isBlank()) {
            throw new IllegalStateException("Bailian ASR response has no transcript");
        }
        return text;
    }

    @Override
    public Speech synthesize(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is blank");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text);
        input.put("voice", props.getTtsVoice());
        input.put("language_type", ttsLanguageType(props.getLanguage()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getTtsModel());
        payload.put("input", input);

        JsonNode audio = postJson(payload, "TTS").path("output").path("audio");
        String data = audio.path("data").asText("");
        byte[] bytes;
        if (!data.isBlank()) {
            try {
                bytes = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Bailian TTS returned invalid base64 audio", e);
            }
        } else {
            String url = audio.path("url").asText("");
            if (url.isBlank()) {
                throw new IllegalStateException("Bailian TTS response has neither audio data nor URL");
            }
            bytes = downloadAudio(url);
        }
        if (bytes.length == 0) {
            throw new IllegalStateException("Bailian TTS returned empty audio");
        }
        return new Speech(bytes, props.ttsContentType());
    }

    protected JsonNode postJson(Map<String, Object> payload, String operation) {
        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(props.getBaseUrl()) + GENERATION_PATH))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Bailian " + operation + " failed: HTTP "
                        + response.statusCode() + " " + bounded(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Bailian " + operation + " request failed: " + e.getMessage(), e);
        }
    }

    /**
     * 下载百炼返回的短时 OSS 音频 URL。限制到 HTTPS + 阿里云域名，避免异常响应把服务变成 SSRF 跳板。
     */
    protected byte[] downloadAudio(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null
                    || !(host.endsWith(".aliyuncs.com") || host.endsWith(".aliyuncs.com.cn"))) {
                throw new IllegalStateException("Bailian TTS returned an untrusted audio URL");
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                // 百炼当前仍可能返回 http OSS 地址；同主机升级为 https，避免音频明文传输。
                uri = URI.create("https:" + uri.toString().substring("http:".length()));
            } else if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException("Bailian TTS returned an untrusted audio URL");
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Bailian TTS audio download failed: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Bailian TTS audio download failed: " + e.getMessage(), e);
        }
    }

    private static String audioMime(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".ogg") || name.endsWith(".opus")) return "audio/ogg";
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".aac")) return "audio/aac";
        return "audio/wav";
    }

    private static String ttsLanguageType(String language) {
        if (language == null || language.isBlank()) return "Auto";
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "zh", "yue" -> "Chinese";
            case "en" -> "English";
            case "de" -> "German";
            case "it" -> "Italian";
            case "pt" -> "Portuguese";
            case "es" -> "Spanish";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "fr" -> "French";
            case "ru" -> "Russian";
            default -> "Auto";
        };
    }

    private static String bounded(String body) {
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
