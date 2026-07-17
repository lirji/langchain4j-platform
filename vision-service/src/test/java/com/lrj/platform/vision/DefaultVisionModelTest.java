package com.lrj.platform.vision;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultVisionModelTest：借助捕获式假 {@link dev.langchain4j.model.chat.ChatModel} 验证
 * {@link DefaultVisionModel#caption} 将图片与指令组装为含单个 {@code ImageContent}（base64 == 入参）+
 * 单个 {@code TextContent}（指令）的多模态 UserMessage，空白指令回退默认 caption 提示，
 * 且默认指令路径按图片 SHA 缓存（重复命中不再调模型）、带具体问题的路径不缓存。
 */
class DefaultVisionModelTest {

    /** 捕获最近一次请求的假 ChatModel，回固定文本。 */
    private static final class CapturingChatModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        String reply = "一张柱状图，标题「营收」。";

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            calls.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    private static UserMessage firstUserMessage(CapturingChatModel model) {
        return (UserMessage) model.requests.get(model.requests.size() - 1).messages().get(0);
    }

    @Test
    void caption_assembles_user_message_with_image_and_text() {
        CapturingChatModel model = new CapturingChatModel();
        DefaultVisionModel vision = new DefaultVisionModel(model, "gpt-4o-mini", "默认指令", 0);
        byte[] image = "PNGBYTES".getBytes();

        String out = vision.caption(image, "image/png", "这是什么图？");

        assertThat(out).isEqualTo("一张柱状图，标题「营收」。");
        List<Content> contents = firstUserMessage(model).contents();
        // 恰好一个 ImageContent（base64 == 入参图片）+ 一个 TextContent（== 指令）。
        ImageContent img = (ImageContent) contents.stream().filter(c -> c instanceof ImageContent).findFirst().orElseThrow();
        TextContent txt = (TextContent) contents.stream().filter(c -> c instanceof TextContent).findFirst().orElseThrow();
        assertThat(img.image().base64Data()).isEqualTo(Base64.getEncoder().encodeToString(image));
        assertThat(img.image().mimeType()).isEqualTo("image/png");
        assertThat(txt.text()).isEqualTo("这是什么图？");
    }

    @Test
    void blank_instruction_uses_default_caption_prompt() {
        CapturingChatModel model = new CapturingChatModel();
        DefaultVisionModel vision = new DefaultVisionModel(model, "m", "默认caption指令", 0);

        vision.caption("x".getBytes(), "image/png", "   ");

        TextContent txt = (TextContent) firstUserMessage(model).contents().stream()
                .filter(c -> c instanceof TextContent).findFirst().orElseThrow();
        assertThat(txt.text()).isEqualTo("默认caption指令");
    }

    @Test
    void default_instruction_path_is_cached_by_image_sha() {
        CapturingChatModel model = new CapturingChatModel();
        DefaultVisionModel vision = new DefaultVisionModel(model, "m", "默认指令", 16);
        byte[] image = "same-image".getBytes();

        String a = vision.caption(image, "image/png", null);
        String b = vision.caption(image, "image/png", null);

        assertThat(a).isEqualTo(b);
        assertThat(model.calls.get()).isEqualTo(1); // 第二次命中缓存，不再调用模型
    }

    @Test
    void question_path_is_not_cached() {
        CapturingChatModel model = new CapturingChatModel();
        DefaultVisionModel vision = new DefaultVisionModel(model, "m", "默认指令", 16);
        byte[] image = "same-image".getBytes();

        vision.caption(image, "image/png", "问题A");
        vision.caption(image, "image/png", "问题A");

        assertThat(model.calls.get()).isEqualTo(2); // 带具体问题不缓存
    }
}
