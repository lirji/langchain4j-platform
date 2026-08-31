package com.lrj.platform.edge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 财税路由必须存在、保持鉴权，并允许可信身份携带 tax-review scope。 */
class TaxRouteSecurityTest {

    @Test
    void taxRouteIsPublishedButNotOpen() throws IOException {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml).contains("id: tax", "Path=/tax,/tax/**", "tax-review");
        }
        assertThat(EdgeOpenPaths.isOpen("/tax/invoices/review")).isFalse();
    }
}
