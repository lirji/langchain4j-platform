package com.lrj.platform.knowledge.store.doris;

import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Doris metadata filter → SQL 的纯字符串单测（不连库）。覆盖等值/数值转型/逻辑组合/IN/key 白名单。
 */
class DorisFilterTranslatorTest {

    private final DorisFilterTranslator translator = new DorisFilterTranslator();

    @Test
    void nullFilter_yieldsEmptyClause() {
        DorisFilterTranslator.Translated t = translator.translate(null);
        assertThat(t.whereClause).isEmpty();
        assertThat(t.params).isEmpty();
    }

    @Test
    void stringEquality_usesGetJsonStringAndBindsParam() {
        DorisFilterTranslator.Translated t = translator.translate(
                metadataKey("tenantId").isEqualTo("acme"));
        assertThat(t.whereClause).isEqualTo("get_json_string(metadata, '$.tenantId') = ?");
        assertThat(t.params).containsExactly("acme");
    }

    @Test
    void numericComparison_castsToDouble() {
        DorisFilterTranslator.Translated t = translator.translate(
                metadataKey("version").isGreaterThan(2));
        assertThat(t.whereClause).isEqualTo("CAST(get_json_string(metadata, '$.version') AS DOUBLE) > ?");
        assertThat(t.params).containsExactly(2);
    }

    @Test
    void andCombination_wrapsInParentheses() {
        Filter f = metadataKey("tenantId").isEqualTo("acme")
                .and(metadataKey("type").isEqualTo("image"));
        DorisFilterTranslator.Translated t = translator.translate(f);
        assertThat(t.whereClause).isEqualTo(
                "(get_json_string(metadata, '$.tenantId') = ? AND get_json_string(metadata, '$.type') = ?)");
        assertThat(t.params).containsExactly("acme", "image");
    }

    @Test
    void isIn_rendersPlaceholderList() {
        DorisFilterTranslator.Translated t = translator.translate(
                metadataKey("category").isIn(List.of("a", "b")));
        assertThat(t.whereClause).isEqualTo("get_json_string(metadata, '$.category') IN (?,?)");
        assertThat(t.params).containsExactly("a", "b");
    }

    @Test
    void unsafeMetadataKey_isRejected() {
        assertThatThrownBy(() -> translator.translate(metadataKey("weird key; DROP").isEqualTo("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe metadata key");
    }
}
