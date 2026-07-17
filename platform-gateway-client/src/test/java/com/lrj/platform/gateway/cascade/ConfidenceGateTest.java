package com.lrj.platform.gateway.cascade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConfidenceGateTest：验证 {@link ConfidenceGate#isConfident} 的启发式置信判定——干净的长答案判为
 * 置信，答案过短、含不确定标记（中/英文）或为 null 时判为不置信（需升级）。
 */
class ConfidenceGateTest {

    @Test
    void cleanLongAnswerIsConfident() {
        ConfidenceGate gate = new ConfidenceGate(new CascadeProperties());
        assertThat(gate.isConfident("首都是哪", "北京是中国的首都，是政治与文化中心。")).isTrue();
    }

    @Test
    void tooShortAnswerEscalates() {
        ConfidenceGate gate = new ConfidenceGate(new CascadeProperties()); // min-answer-chars 默认 8
        assertThat(gate.isConfident("q", "短")).isFalse();
    }

    @Test
    void uncertaintyMarkerEscalates() {
        ConfidenceGate gate = new ConfidenceGate(new CascadeProperties());
        assertThat(gate.isConfident("q", "抱歉，我无法确定这个问题的答案。")).isFalse();
        assertThat(gate.isConfident("q", "I'm not sure about the exact figure here.")).isFalse();
    }

    @Test
    void nullAnswerEscalates() {
        ConfidenceGate gate = new ConfidenceGate(new CascadeProperties());
        assertThat(gate.isConfident("q", null)).isFalse();
    }
}
