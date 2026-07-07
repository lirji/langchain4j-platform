package com.lrj.platform.vision;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link VisionModel} 默认实现：把图片 base64 编码后与指令拼成多模态 {@code UserMessage}，
 * 调用内部持有的视觉 {@code ChatModel}。
 *
 * <p>内部 {@code ChatModel} 由 {@link VisionConfig} 经 {@code GatewayChatModelFactory} 构造传入
 * （已挂全部 {@code ChatModelListener}：指标 / 成本 / per-tenant token 预算），<strong>不是 Spring Bean</strong>，
 * 避免与主 {@code ChatModel} 撞 {@code @AiService} 自动发现。
 *
 * <p>默认指令（{@code instruction} 留空）路径带<strong>按图内容 SHA-256 去重的有界 LRU 缓存</strong>：
 * 同一张图重复上传直接复用上次结果。带具体问题的调用不缓存（问题随请求变化）。
 */
public class DefaultVisionModel implements VisionModel {

    private static final Logger log = LoggerFactory.getLogger(DefaultVisionModel.class);

    private final ChatModel model;
    private final String modelName;
    private final String captionPrompt;

    /** 访问序 LRU，超容量自动淘汰最久未用项；{@code synchronizedMap} 包一层保证并发安全。 */
    private final Map<String, String> captionCache;

    public DefaultVisionModel(ChatModel model, String modelName, String captionPrompt, int captionCacheSize) {
        this.model = model;
        this.modelName = modelName;
        this.captionPrompt = captionPrompt;
        this.captionCache = captionCacheSize <= 0 ? null : Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > captionCacheSize;
                    }
                });
    }

    @Override
    public String caption(byte[] image, String mimeType, String instruction) {
        boolean useDefault = instruction == null || instruction.isBlank();
        String prompt = useDefault ? captionPrompt : instruction.trim();
        // 仅默认指令路径走缓存（同图同指令才可复用）。
        if (!useDefault || captionCache == null) {
            return describe(image, mimeType, prompt);
        }
        String key = sha256(image) + ":" + mimeType;
        String cached = captionCache.get(key);
        if (cached != null) {
            log.info("vision caption cache HIT (key={}…, chars={})", key.substring(0, 8), cached.length());
            return cached;
        }
        String text = describe(image, mimeType, prompt);
        captionCache.put(key, text);
        return text;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private String describe(byte[] image, String mime, String instruction) {
        String base64 = Base64.getEncoder().encodeToString(image);
        UserMessage msg = UserMessage.from(
                ImageContent.from(base64, mime),
                TextContent.from(instruction));
        long t0 = System.currentTimeMillis();
        String text = model.chat(msg).aiMessage().text();
        log.info("vision describe: bytes={} mime={} -> chars={} ({}ms)",
                image.length, mime, text == null ? 0 : text.length(), System.currentTimeMillis() - t0);
        return text == null ? "" : text.trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
