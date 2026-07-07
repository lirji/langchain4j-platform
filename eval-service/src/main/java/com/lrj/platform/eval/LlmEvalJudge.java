package com.lrj.platform.eval;

import com.lrj.platform.gateway.GatewayChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 走 platform-gateway-client 确定性 {@code ChatModel}（temp=0，指向 LiteLLM）的 LLM judge。
 *
 * <p>让判官对「实际响应是否满足给定标准」打 0-100 分，归一化到 0.0-1.0 返回。ChatModel 藏在本类后，
 * {@link EvalRunner} 只依赖 {@link EvalJudge} 接口，便于单测 mock。
 */
public class LlmEvalJudge implements EvalJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmEvalJudge.class);
    private static final Pattern SCORE_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final ChatModel chatModel;

    public LlmEvalJudge(GatewayChatModelFactory factory) {
        this.chatModel = factory.buildDeterministic();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public double score(String criteria, String actualResponse) {
        String prompt = """
                你是严格的回归测试评审。请判断【实际响应】在多大程度上满足【期望标准】。
                只输出一个 0 到 100 的整数分数，不要输出任何其它文字、标点或解释。
                0 表示完全不满足，100 表示完全满足。

                【期望标准】
                %s

                【实际响应】
                %s
                """.formatted(safe(criteria), safe(actualResponse));
        try {
            String raw = chatModel.chat(prompt);
            return normalize(parseScore(raw));
        } catch (RuntimeException ex) {
            log.warn("LLM judge scoring failed, treating as 0: {}", ex.getMessage());
            return 0.0D;
        }
    }

    private static double parseScore(String raw) {
        if (raw == null) {
            return 0.0D;
        }
        Matcher matcher = SCORE_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return 0.0D;
        }
        try {
            return Double.parseDouble(matcher.group()) / 100.0D;
        } catch (NumberFormatException ex) {
            return 0.0D;
        }
    }

    private static double normalize(double score) {
        if (score < 0.0D) {
            return 0.0D;
        }
        return Math.min(score, 1.0D);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
