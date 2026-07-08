package com.lrj.platform.knowledge.multimodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多模态 embedding 客户端单测：覆写唯一发 HTTP 的 {@link DefaultMultimodalEmbeddingModel#post} 脱网，
 * 验证「请求体拼装 + 响应解析 + 图片大小/空校验」。
 */
class DefaultMultimodalEmbeddingModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 记录最后一次请求体、返回预置响应的可测子类。 */
    private static final class RecordingModel extends DefaultMultimodalEmbeddingModel {
        String lastRequest;
        RecordingModel(MultimodalEmbeddingProperties props) {
            super(props, MAPPER);
        }
        @Override
        protected String post(String jsonBody) {
            this.lastRequest = jsonBody;
            return "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}";
        }
    }

    private static MultimodalEmbeddingProperties props() {
        MultimodalEmbeddingProperties p = new MultimodalEmbeddingProperties();
        p.setModelName("test-clip");
        p.setDimension(3);
        p.setMaxImageBytes(1024);
        return p;
    }

    @Test
    void embedText_assemblesRequestAndParsesVector() {
        RecordingModel model = new RecordingModel(props());
        float[] vec = model.embedText("hello");
        assertThat(vec).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(model.lastRequest).contains("\"model\":\"test-clip\"").contains("\"input\":[\"hello\"]");
    }

    @Test
    void embedImage_usesDataUriInputAndParsesVector() {
        RecordingModel model = new RecordingModel(props());
        float[] vec = model.embedImage(new byte[]{1, 2, 3}, "image/png");
        assertThat(vec).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(model.lastRequest).contains("\"image\":\"data:image/png;base64,");
    }

    @Test
    void embedImage_rejectsEmptyAndOversized() {
        RecordingModel model = new RecordingModel(props());
        assertThatThrownBy(() -> model.embedImage(new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> model.embedImage(new byte[2048], "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }
}
