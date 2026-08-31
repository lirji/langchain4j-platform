package com.lrj.platform.tax;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaxReviewPropertiesTest {

    @Test
    void rejectsUnsafeResourceConfiguration() {
        TaxReviewProperties properties = new TaxReviewProperties();
        properties.setMaxInvoices(0);
        properties.getKnowledge().setTopK(21);
        properties.getKnowledge().setMinScore(1.1);
        properties.getKnowledge().setEvidenceMaxChars(-1);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var paths = factory.getValidator().validate(properties).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .toList();

            assertThat(paths).contains("maxInvoices", "knowledge.topK", "knowledge.minScore",
                    "knowledge.evidenceMaxChars");
        }
    }
}
