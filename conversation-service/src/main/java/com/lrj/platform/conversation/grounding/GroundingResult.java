package com.lrj.platform.conversation.grounding;

import java.util.List;

/**
 * Grounding 校验结果。
 *
 * @param answer   （可能追加了可信度提示后缀的）最终答案；warn 模式下 grounded 时与原答案一致
 * @param score    faithfulness 分数（0..1）；未打分（无来源/拒答/未开启）为 {@code null}
 * @param warnings 命中的可信度问题（伪造引用 / 低支撑度）；grounded 时为空
 * @param grounded 是否判定为「充分支撑」（无 warning）
 */
public record GroundingResult(String answer, Double score, List<String> warnings, boolean grounded) {

    /** 未校验（未开启 / 无来源）时的直通结果。 */
    public static GroundingResult passthrough(String answer) {
        return new GroundingResult(answer, null, List.of(), true);
    }
}
