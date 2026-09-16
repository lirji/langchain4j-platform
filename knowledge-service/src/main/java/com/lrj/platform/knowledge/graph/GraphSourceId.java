package com.lrj.platform.knowledge.graph;

import java.util.regex.Pattern;

/**
 * 图三元组 {@code sourceId} 的版本 provenance 格式：{@code <docId>/v<version>/<name>#<index>}。
 *
 * <p>这里是该格式的**唯一定义**。写入（{@link GraphIngestor}）、版本 GC 的前缀删除
 * （{@code KnowledgeVersionGarbageCollector}）与查询侧的 provenance 还原
 * （{@code GraphRetrievalSource}）此前各自拼/各自拆同一个字符串，任何一侧改格式都不会被另两侧发现。
 *
 * <p>{@code docId} 是 SHA-256 前 16 hex，不含 {@code /}；{@code version} 是正整数。因此从左侧按前两个
 * {@code /} 切分即可无歧义还原，即使文件名本身含 {@code /}。解析不出这两段时 {@link #hasProvenance()}
 * 为 false —— 那是引入版本 provenance 之前写入的历史三元组，既无法按 Registry 当前版本判定新鲜度，
 * 也无法做文档级判权，调用方必须显式决定放行还是丢弃，不能默认当成"当前版本"。
 */
public record GraphSourceId(String docId, String version, String visibleSource) {

    private static final Pattern VERSION = Pattern.compile("v(\\d+)");

    /** 写入侧：把文档版本固化进 sourceId。 */
    public static String of(String docId, String version, String chunkId) {
        return prefix(docId, version) + chunkId;
    }

    /** GC 侧：某个文档某个版本的全部三元组共享的 sourceId 前缀。 */
    public static String prefix(String docId, Object version) {
        return docId + "/v" + version + "/";
    }

    public static GraphSourceId parse(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return new GraphSourceId(null, null, null);
        }
        String[] parts = sourceId.split("/", 3);
        if (parts.length == 3 && !parts[0].isBlank()) {
            var matcher = VERSION.matcher(parts[1]);
            if (matcher.matches()) {
                return new GraphSourceId(parts[0], matcher.group(1), parts[2]);
            }
        }
        // 无 provenance：沿用引入版本前的展示口径，取最后一段作为可见来源。
        int lastSeparator = sourceId.lastIndexOf('/');
        return new GraphSourceId(
                null, null, lastSeparator < 0 ? sourceId : sourceId.substring(lastSeparator + 1));
    }

    /** 是否可归属到具体文档版本——决定该命中能否参与版本过滤与文档级判权。 */
    public boolean hasProvenance() {
        return docId != null && version != null;
    }

    /** 可见来源里的文档展示名（{@code name#index} 的 name 部分）；拆不出序号时整段即展示名。 */
    public String displayName() {
        if (visibleSource == null) {
            return null;
        }
        int separator = visibleSource.lastIndexOf('#');
        if (separator < 0 || separator == visibleSource.length() - 1) {
            return visibleSource;
        }
        return visibleSource.substring(0, separator);
    }

    /** 可见来源里的 chunk 序号；无法判定时返回 null。 */
    public String index() {
        if (visibleSource == null) {
            return null;
        }
        int separator = visibleSource.lastIndexOf('#');
        if (separator < 0 || separator == visibleSource.length() - 1) {
            return null;
        }
        return visibleSource.substring(separator + 1);
    }
}
