package com.lrj.platform.knowledge.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FusionStrategyTest：验证 {@link FusionStrategy} 的默认推导 {@code effectiveDefault}——仅当 ES 真正参与查询
 * （enabled 且 query-enabled）才翻为 RRF，只写不查或 ES 关闭时保持 WEIGHTED_MAX，显式配置始终优先；
 * 以及 {@code parse} 对别名的识别与非法值回退。
 */
class FusionStrategyTest {

    @Test
    void effectiveDefault_flipsToRrfOnlyWhenEsActuallyQuerying() {
        // ES 真正参与查询（enabled + query-enabled）→ RRF
        assertThat(FusionStrategy.effectiveDefault("", true, true)).isEqualTo(FusionStrategy.RRF);
    }

    @Test
    void effectiveDefault_writeOnlyGrayKeepsWeightedMax() {
        // #5：只开写不查（enabled 但 query-enabled=false）→ 不翻 RRF，保持 weighted_max
        assertThat(FusionStrategy.effectiveDefault("", true, false)).isEqualTo(FusionStrategy.WEIGHTED_MAX);
    }

    @Test
    void effectiveDefault_esDisabledKeepsWeightedMax() {
        assertThat(FusionStrategy.effectiveDefault("", false, true)).isEqualTo(FusionStrategy.WEIGHTED_MAX);
    }

    @Test
    void effectiveDefault_explicitConfigWins() {
        assertThat(FusionStrategy.effectiveDefault("weighted_max", true, true)).isEqualTo(FusionStrategy.WEIGHTED_MAX);
        assertThat(FusionStrategy.effectiveDefault("rrf", false, false)).isEqualTo(FusionStrategy.RRF);
    }

    @Test
    void parse_recognizesAliasesAndFallsBack() {
        assertThat(FusionStrategy.parse("RRF", FusionStrategy.WEIGHTED_MAX)).isEqualTo(FusionStrategy.RRF);
        assertThat(FusionStrategy.parse("weighted-max", FusionStrategy.RRF)).isEqualTo(FusionStrategy.WEIGHTED_MAX);
        assertThat(FusionStrategy.parse("nonsense", FusionStrategy.RRF)).isEqualTo(FusionStrategy.RRF);
        assertThat(FusionStrategy.parse("", FusionStrategy.WEIGHTED_MAX)).isEqualTo(FusionStrategy.WEIGHTED_MAX);
    }
}
