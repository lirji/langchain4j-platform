package com.lrj.platform.knowledge.operations.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface KnowledgeOperationsStoreMapper {
    /** 保留原readContract1操作的条件与影响行数契约。 */
    List<KnowledgeOperationsStoreRow> readContract1();
    /** 保留原readContract2操作的条件与影响行数契约。 */
    List<KnowledgeOperationsStoreRow> readContract2();
    /** 保留原readContract3操作的条件与影响行数契约。 */
    List<KnowledgeOperationsStoreRow> readContract3();
    /** 保留原readContract4操作的条件与影响行数契约。 */
    List<KnowledgeOperationsStoreRow> readContract4();
    /** 保留原writeFeedback1操作的条件与影响行数契约。 */
    int writeFeedback1(
        @Param("id") String id,
        @Param("tenantKey") String tenantKey,
        @Param("authorKey") String authorKey,
        @Param("requestKey") String requestKey,
        @Param("requestHash") String requestHash,
        @Param("bodyJson") String bodyJson,
        @Param("documentId") String documentId,
        @Param("reasonCode") String reasonCode);
    /** 保留原readFeedback1操作的条件与影响行数契约。 */
    List<KnowledgeOperationsStoreRow> readFeedback1(@Param("tenantKey") String tenantKey, @Param("requestKey") String requestKey);
    /** 保留原readFeedback2操作的条件与影响行数契约。 */
    List<KnowledgeOperationsStoreRow> readFeedback2(@Param("tenantKey") String tenantKey, @Param("id") String id);
    /** 保留原writeCreate1操作的条件与影响行数契约。 */
    int writeCreate1(
        @Param("id") String id,
        @Param("tenantKey") String tenantKey,
        @Param("requestKey") String requestKey,
        @Param("requestHash") String requestHash,
        @Param("revision") Long revision,
        @Param("bodyJson") String bodyJson);
    /** 保留原readCreate1操作的条件与影响行数契约。 */
    List<KnowledgeOperationsStoreRow> readCreate1(@Param("tenantKey") String tenantKey, @Param("requestKey") String requestKey);
    /** 保留原readGet1操作的条件与影响行数契约。 */
    List<KnowledgeOperationsStoreRow> readGet1(@Param("tenantKey") String tenantKey, @Param("id") String id);
    /** 保留原writeClaim1操作的条件与影响行数契约。 */
    int writeClaim1(
        @Param("tenantKey") String tenantKey,
        @Param("documentId") String documentId,
        @Param("documentVersion") Long documentVersion,
        @Param("candidateId") String candidateId);
    /** 保留原readAudit1操作的条件与影响行数契约。 */
    List<KnowledgeOperationsStoreRow> readAudit1(
        @Param("tenantKey") String tenantKey,
        @Param("candidateId") String candidateId,
        @Param("limit") Integer limit);
    /** 保留原writeUpdate1操作的条件与影响行数契约。 */
    int writeUpdate1(
        @Param("bodyJson") String bodyJson,
        @Param("revision") Long revision,
        @Param("tenantKey") String tenantKey,
        @Param("id") String id,
        @Param("revision2") Long revision2);
    /** 保留原writeAudit1操作的条件与影响行数契约。 */
    int writeAudit1(
        @Param("tenantKey") String tenantKey,
        @Param("candidateId") String candidateId,
        @Param("actor") String actor,
        @Param("actionCode") String actionCode,
        @Param("revision") Long revision,
        @Param("occurredAt") Long occurredAt);
    /** 游标与可选作者过滤均参数绑定；首屏不依赖数据库波浪号排序。 */
    List<KnowledgeOperationsStoreRow> feedbackPage(
        @Param("tenant") String tenant,
        @Param("actor") String actor,
        @Param("before") String before,
        @Param("limit") int limit);
    /** 候选分页始终限定租户。 */
    List<KnowledgeOperationsStoreRow> candidatePage(
        @Param("tenant") String tenant,
        @Param("before") String before,
        @Param("limit") int limit);
    /** 只汇总当前租户文档反馈。 */
    List<KnowledgeOperationsStoreRow> feedbackSummary(@Param("tenant") String tenant, @Param("document") String document);
}
