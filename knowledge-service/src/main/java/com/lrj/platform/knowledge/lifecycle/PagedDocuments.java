package com.lrj.platform.knowledge.lifecycle;

import java.util.List;

/**
 * 文档列表的分页信封（{@code GET /rag/documents?page=&size=} 返回）。
 * 分页发生在租户隔离与文档级授权过滤 <strong>之后</strong>，因此 {@link #total} 是"当前调用方可见"的总条数，
 * 而非该分区全部文档数——保证与 {@link DocumentService#list(boolean)} 的可见性语义一致。
 *
 * @param items      本页文档（已按 {@code uploadedAt} 降序、{@code docId} 升序稳定排序）
 * @param page       当前页码，1-based；服务端已 clamp 到 {@code [1, totalPages]}
 * @param size       页大小；服务端已 clamp 到 {@code [1, MAX_PAGE_SIZE]}
 * @param total      过滤后总条数（可见文档总数）
 * @param totalPages 总页数 {@code ceil(total/size)}，至少为 1（total=0 时也返回 1，便于前端展示"第 1/1 页"）
 */
public record PagedDocuments(
        List<DocumentInfo> items,
        int page,
        int size,
        long total,
        int totalPages) {
}
