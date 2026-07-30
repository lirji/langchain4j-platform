package com.lrj.platform.knowledge.multimodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BailianMultimodalEmbeddingModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class RecordingModel extends BailianMultimodalEmbeddingModel {
        String lastRequest;
        String response = """
                {"output":{"embeddings":[{"index":0,"type":"vl","embedding":[0.1,0.2,0.3]}]}}
                """;

        RecordingModel(MultimodalEmbeddingProperties props) {
            super(props, MAPPER);
        }

        @Override
        protected String post(String jsonBody) {
            lastRequest = jsonBody;
            return response;
        }
    }

    private static MultimodalEmbeddingProperties props() {
        MultimodalEmbeddingProperties props = new MultimodalEmbeddingProperties();
        props.setProvider("bailian");
        props.setBaseUrl("https://example.aliyuncs.com/api/v1");
        props.setApiKey("test-key");
        props.setModelName("qwen3-vl-embedding");
        props.setDimension(3);
        props.setMaxImageBytes(1024);
        return props;
    }

    @Test
    void embedsTextUsingNativeContentsContract() {
        RecordingModel model = new RecordingModel(props());
        assertThat(model.embedText("红色运动鞋")).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(model.lastRequest)
                .contains("\"model\":\"qwen3-vl-embedding\"")
                .contains("\"contents\":[{\"text\":\"红色运动鞋\"}]")
                .contains("\"dimension\":3");
    }

    @Test
    void embedsImageAsDataUriInSameContract() {
        RecordingModel model = new RecordingModel(props());
        assertThat(model.embedImage(new byte[]{1, 2, 3}, "image/png"))
                .containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(model.lastRequest)
                .contains("\"contents\":[{\"image\":\"data:image/png;base64,AQID\"}]");
    }

    @Test
    void rejectsEmptyOversizedAndWrongDimension() {
        RecordingModel model = new RecordingModel(props());
        assertThatThrownBy(() -> model.embedImage(new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.embedImage(new byte[2048], "image/png"))
                .isInstanceOf(IllegalArgumentException.class);

        model.response = """
                {"output":{"embeddings":[{"embedding":[0.1,0.2]}]}}
                """;
        assertThatThrownBy(() -> model.embedText("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension mismatch");
    }
}
