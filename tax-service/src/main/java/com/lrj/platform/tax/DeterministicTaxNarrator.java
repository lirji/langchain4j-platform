package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxPolicyEvidence;

import java.util.List;
import java.util.stream.Collectors;

/** 模型关闭或失败时使用的确定性说明。 */
final class DeterministicTaxNarrator implements TaxNarrator {

    @Override
    public TaxNarrative narrate(TaxReviewOutcome outcome, List<TaxPolicyEvidence> evidence) {
        if (outcome.findings().isEmpty()) {
            return new TaxNarrative("未发现批内重复、金额勾稽、税额勾稽或所属期间异常。仍需人工核验发票真伪和业务真实性。", "FALLBACK");
        }
        String codes = outcome.findings().stream().map(item -> item.code()).distinct().collect(Collectors.joining("、"));
        return new TaxNarrative("确定性规则发现 " + outcome.findings().size() + " 项风险：" + codes
                + "。请结合政策证据和原始凭证人工复核。", "FALLBACK");
    }
}
