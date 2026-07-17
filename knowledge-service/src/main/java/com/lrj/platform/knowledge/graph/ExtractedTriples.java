package com.lrj.platform.knowledge.graph;

import java.util.List;

/**
 * {@link GraphExtractor} 从一段文本抽取出的三元组集合，包裹一个不可变的 {@link RawTriple} 列表
 * （构造时防御性拷贝，null 归一为空列表）。
 */
public record ExtractedTriples(List<RawTriple> triples) {

    public ExtractedTriples {
        triples = triples == null ? List.of() : List.copyOf(triples);
    }
}
