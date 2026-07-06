package com.lrj.platform.knowledge.graph;

import java.util.List;

public record ExtractedTriples(List<RawTriple> triples) {

    public ExtractedTriples {
        triples = triples == null ? List.of() : List.copyOf(triples);
    }
}
