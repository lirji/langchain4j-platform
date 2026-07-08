package com.lrj.platform.knowledge.multimodal;

import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 原生多模态检索：把图片直接 embed 进跨模态向量空间并支持用「文本 query」检索这些图片（text→image）。
 *
 * <p><strong>多租户隔离</strong>：走注入的 image 专用 {@link EmbeddingStoreRouter}（base=
 * {@code knowledge_images}，与文本集合 {@code knowledge_segments} 物理分开），每租户一个
 * {@code knowledge_images_<tenant>} collection/表；入库/检索仍额外打 {@code tenantId} + {@code type=image}
 * filter 做纵深防御（复用平台 {@link TenantContext}）。
 *
 * <p><strong>维度安全（重要）</strong>：image 向量来自 CLIP/jina-clip，维度（如 1024）与主 RAG 的文本
 * embedding 通常不同。因为图片走独立的 image collection（与文本 collection 不同名、不同 router 实例），
 * 天然与文本向量隔离，不会触发 {@code DimensionMismatchException}，也不会把 CLIP 维度的 query 拿去和
 * 文本 chunk 向量做点积。
 */
public class MultimodalRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(MultimodalRetrievalService.class);

    /** 标识一条记录是「原生 image 向量」，检索/隔离两用。 */
    public static final String TYPE_IMAGE = "image";

    private final MultimodalEmbeddingModel model;
    private final EmbeddingStoreRouter imageStoreRouter;
    private final int defaultTopK;
    private final double defaultMinScore;

    public MultimodalRetrievalService(MultimodalEmbeddingModel model,
                                      EmbeddingStoreRouter imageStoreRouter,
                                      int defaultTopK,
                                      double defaultMinScore) {
        this.model = model;
        this.imageStoreRouter = imageStoreRouter;
        this.defaultTopK = defaultTopK;
        this.defaultMinScore = defaultMinScore;
    }

    /**
     * 入库钩子：把一张图片 embed 成向量存进该租户的 image collection，带 {@code type=image} /
     * {@code file_name} / {@code tenantId} / {@code mime} metadata。
     *
     * @param image    图片字节
     * @param mimeType MIME
     * @param fileName 来源文件名（进 metadata，检索结果按它回指）
     * @return store 生成的记录 id
     */
    public String ingestImage(byte[] image, String mimeType, String fileName) {
        float[] vector = model.embedImage(image, mimeType);
        String tenantId = TenantContext.current().tenantId();
        String name = (fileName == null || fileName.isBlank()) ? "image" : fileName;

        Metadata metadata = new Metadata();
        metadata.put("type", TYPE_IMAGE);
        metadata.put("file_name", name);
        metadata.put("tenantId", tenantId);
        if (mimeType != null && !mimeType.isBlank()) {
            metadata.put("mime", mimeType);
        }
        // image 片段没有天然文本内容；用文件名占位（TextSegment 需要非空文本，且检索结果里方便展示）。
        TextSegment segment = TextSegment.from("[image] " + name, metadata);

        EmbeddingStore<TextSegment> store = imageStoreRouter.forTenant(tenantId, model.dimension());
        String id = store.add(Embedding.from(vector), segment);
        log.info("ingested image: file={} tenant={} dim={} id={}", name, tenantId, vector.length, id);
        return id;
    }

    /**
     * text→image 检索：把文本 query 用同一个多模态模型 embed，在该租户 image collection 里找最近邻。
     *
     * @param query      查询文本
     * @param maxResults 返回条数（&le;0 用默认）
     * @param minScore   最小相似度（&lt;0 用默认）
     * @return 命中的图片（file_name + score），按相似度降序
     */
    public List<ImageMatch> searchByText(String query, int maxResults, double minScore) {
        int topK = maxResults > 0 ? maxResults : defaultTopK;
        double floor = minScore >= 0 ? minScore : defaultMinScore;
        float[] queryVector = model.embedText(query);

        String tenantId = TenantContext.current().tenantId();
        Filter filter = Filter.and(
                metadataKey("tenantId").isEqualTo(tenantId),
                metadataKey("type").isEqualTo(TYPE_IMAGE));

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryVector))
                .maxResults(topK)
                .minScore(floor)
                .filter(filter)
                .build();

        EmbeddingStore<TextSegment> store = imageStoreRouter.forTenant(tenantId, model.dimension());
        List<EmbeddingMatch<TextSegment>> matches = store.search(request).matches();
        List<ImageMatch> out = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> m : matches) {
            String fileName = m.embedded() != null && m.embedded().metadata() != null
                    ? m.embedded().metadata().getString("file_name") : null;
            out.add(new ImageMatch(m.embeddingId(), fileName, m.score()));
        }
        log.info("image-search: query='{}' tenant={} topK={} minScore={} -> {} hits",
                query, tenantId, topK, floor, out.size());
        return out;
    }

    /** 一条 text→image 命中：记录 id / 文件名 / 相似度。 */
    public record ImageMatch(String id, String fileName, double score) {}
}
