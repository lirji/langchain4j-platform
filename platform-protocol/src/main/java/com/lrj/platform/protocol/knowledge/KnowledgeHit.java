package com.lrj.platform.protocol.knowledge;

/**
 * 一条检索命中（跨服务 DTO）。{@code visibility} 为末尾加法字段（RBAC 共享库支持）：
 * 取值 {@code "tenant"}（来自调用方自己租户分区）或 {@code "public"}（来自共享库保留分区 {@code __public__}）。
 * 老消费者忽略该字段即可，向后兼容。
 */
public record KnowledgeHit(String id,
                           Double score,
                           String docId,
                           String displayName,
                           String category,
                           String index,
                           String text,
                           String source,
                           String visibility,
                           String version,
                           java.util.Map<String,String> sourceMetadata) {
    public KnowledgeHit { sourceMetadata = sourceMetadata == null ? java.util.Map.of() : java.util.Map.copyOf(sourceMetadata); }
    /** 加法字段：只含来源定位/质量告警，历史响应未提供时为空；不参与身份与权限判断。 */
    public KnowledgeHit(String id, Double score, String docId, String displayName, String category, String index,
                        String text, String source, String visibility, String version) {
        this(id,score,docId,displayName,category,index,text,source,visibility,version,java.util.Map.of());
    }
    /** 旧调用方可继续构造命中；未知版本不能作为发布复查通过的证据。 */
    public KnowledgeHit(String id, Double score, String docId, String displayName, String category,
                        String index, String text, String source, String visibility) {
        this(id, score, docId, displayName, category, index, text, source, visibility, null);
    }
}
