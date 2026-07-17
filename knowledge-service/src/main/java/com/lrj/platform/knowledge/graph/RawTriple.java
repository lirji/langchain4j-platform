package com.lrj.platform.knowledge.graph;

/**
 * {@link GraphExtractor} 抽取出的原始三元组（主语/谓语/宾语），构造时仅做去空白归一化，尚未经白名单
 * 过滤或别名规整。{@link #isComplete()} 判断三元素是否均非空，供 {@link GraphIngestor} 决定是否采纳。
 */
public record RawTriple(String subject, String relation, String object) {

    public RawTriple {
        subject = normalize(subject);
        relation = normalize(relation);
        object = normalize(object);
    }

    public boolean isComplete() {
        return !subject.isBlank() && !relation.isBlank() && !object.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
