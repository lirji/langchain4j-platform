package com.lrj.platform.knowledge.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic extractor for controlled ingestion. Each statement uses
 * {@code subject|relation|object}; statements can be split by newlines or semicolons.
 */
public class RuleBasedGraphExtractor implements GraphExtractor {

    @Override
    public ExtractedTriples extract(String text) {
        if (text == null || text.isBlank()) {
            return new ExtractedTriples(List.of());
        }
        List<RawTriple> triples = new ArrayList<>();
        for (String statement : text.split("\\R|;|；")) {
            String trimmed = statement.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            RawTriple triple = new RawTriple(parts[0], parts[1], parts[2]);
            if (triple.isComplete()) {
                triples.add(triple);
            }
        }
        return new ExtractedTriples(triples);
    }
}
