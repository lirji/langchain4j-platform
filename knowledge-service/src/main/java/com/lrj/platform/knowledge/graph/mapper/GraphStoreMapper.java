package com.lrj.platform.knowledge.graph.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface GraphStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<GraphStoreRow> readInit1();
    /** 保留原writeAdd1操作的条件与影响行数契约。 */
    int writeAdd1(@Param("graphId") String graphId);
    /** 保留原writeAdd2操作的条件与影响行数契约。 */
    int writeAdd2(
        @Param("graphId") String graphId,
        @Param("tenantId") String tenantId,
        @Param("category") String category,
        @Param("subjectText") String subjectText,
        @Param("subjectKey") String subjectKey,
        @Param("relationText") String relationText,
        @Param("objectText") String objectText,
        @Param("objectKey") String objectKey,
        @Param("sourceId") String sourceId,
        @Param("createdAt") Long createdAt);
    /** 保留原readEntities1操作的条件与影响行数契约。 */
    List<GraphStoreRow> readEntities1(@Param("tenantId") String tenantId);
    /** 保留原readEntities2操作的条件与影响行数契约。 */
    List<GraphStoreRow> readEntities2(@Param("tenantId") String tenantId, @Param("category") String category);
    /** 保留原writeRemoveBySourcePrefix1操作的条件与影响行数契约。 */
    int writeRemoveBySourcePrefix1(@Param("tenantId") String tenantId, @Param("sourceId") String sourceId);
    /** 保留原readSize1操作的条件与影响行数契约。 */
    List<Integer> readSize1();
    /** 保留原readTriplesForEntity1操作的条件与影响行数契约。 */
    List<GraphStoreRow> readTriplesForEntity1(
        @Param("tenantId") String tenantId,
        @Param("subjectKey") String subjectKey,
        @Param("objectKey") String objectKey);
    /** 保留原readTriplesForEntity2操作的条件与影响行数契约。 */
    List<GraphStoreRow> readTriplesForEntity2(
        @Param("tenantId") String tenantId,
        @Param("category") String category,
        @Param("subjectKey") String subjectKey,
        @Param("objectKey") String objectKey);
}
