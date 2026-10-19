package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 每次调用先核验整文预算再分批；模型输出只在数量/维度/有限值核验后进入不可变准备快照。 */
public final class IngestionBatcher {
    private final IngestionResourceProperties limits;
    private final OpenAiTokenCountEstimator tokenizer;
    private final ObjectMapper mapper = new ObjectMapper();
    public IngestionBatcher(IngestionResourceProperties limits) {
        this.limits = limits; this.tokenizer = new OpenAiTokenCountEstimator(limits.getTokenizerModel());
    }
    /** 以实际模型批次代替一次 embedAll；检查点在每次远程操作前后验证租约和总预算。 */
    public List<Embedding> embed(EmbeddingModel model, List<TextSegment> segments, Runnable checkpoint) {
        List<Batch> batches = embeddingBatches(segments);
        List<Embedding> result = new ArrayList<>(segments.size()); int dimension = -1;
        for (Batch batch : batches) {
            checkpoint.run();
            var response = model.embedAll(segments.subList(batch.from(),batch.to()));
            checkpoint.run();
            List<Embedding> embeddings = response == null ? null : response.content();
            if (embeddings == null || embeddings.size() != batch.to() - batch.from()) fail(IngestionResourceError.MODEL_RESPONSE);
            for (Embedding embedding : embeddings) {
                if (embedding == null || embedding.dimension() < 1) fail(IngestionResourceError.MODEL_RESPONSE);
                if (dimension < 0) {
                    dimension = embedding.dimension();
                    // 首批确认真实维度后，在继续计费调用前拒绝无法持有/落盘的整文向量规模。
                    if ((long) dimension * segments.size() * Float.BYTES > limits.getMaxVectorBytes()) fail(IngestionResourceError.VECTOR_LIMIT);
                }
                if (embedding.dimension() != dimension) fail(IngestionResourceError.MODEL_RESPONSE);
                for (float value : embedding.vector()) if (!Float.isFinite(value)) fail(IngestionResourceError.MODEL_RESPONSE);
                result.add(embedding);
            }
        }
        return List.copyOf(result);
    }

    /** 本地 tokenizer 的估算不替代提供方上下文限制；超限整文拒绝，不能截断片段。 */
    public List<Batch> embeddingBatches(List<TextSegment> segments) {
        List<Batch> result = new ArrayList<>(); int from = 0, batchTokens = 0, batchBytes = 0;
        long totalTokens = 0, totalBytes = 0;
        for (int index = 0; index < segments.size(); index++) {
            var text = segments.get(index).text();
            int tokens = tokenizer.estimateTokenCountInText(text), bytes = text.getBytes(StandardCharsets.UTF_8).length;
            totalTokens += tokens; totalBytes += bytes;
            if (tokens > limits.getMaxSegmentTokens() || tokens > limits.getMaxBatchTokens() || bytes > limits.getMaxBatchBytes()
                    || totalTokens > limits.getMaxDocumentTokens() || totalBytes > limits.getMaxDocumentBytes()) fail(IngestionResourceError.MODEL_INPUT_LIMIT);
            if (index > from && (index - from >= limits.getMaxBatchSegments()
                    || (long) batchTokens + tokens > limits.getMaxBatchTokens() || (long) batchBytes + bytes > limits.getMaxBatchBytes())) {
                result.add(new Batch(from,index)); from = index; batchTokens = 0; batchBytes = 0;
            }
            batchTokens += tokens; batchBytes += bytes;
        }
        if (from < segments.size()) result.add(new Batch(from,segments.size()));
        return result;
    }

    /** sink 的逻辑载荷包括文本/metadata/向量；字节预算不冒充任意后端线协议大小。 */
    public List<Batch> sinkBatches(PreparedIngestionDocument prepared) {
        List<Batch> batches = new ArrayList<>(); int from = 0; long bytes = 0;
        for (int index = 0; index < prepared.segments().size(); index++) {
            TextSegment segment = prepared.segments().get(index); long item;
            try {
                item = segment.text().getBytes(StandardCharsets.UTF_8).length
                        + mapper.writeValueAsBytes(segment.metadata().toMap()).length
                        + (long) prepared.embeddings().get(index).dimension() * Float.BYTES;
            } catch (java.io.IOException failure) { throw new IngestionResourceException(IngestionResourceError.BATCH_LIMIT,failure); }
            if (item > limits.getMaxBatchBytes()) fail(IngestionResourceError.BATCH_LIMIT);
            if (index > from && (index - from >= limits.getMaxBatchSegments() || bytes + item > limits.getMaxBatchBytes())) {
                batches.add(new Batch(from,index)); from = index; bytes = 0;
            }
            bytes += item;
        }
        if (from < prepared.segments().size()) batches.add(new Batch(from,prepared.segments().size()));
        return batches;
    }
    /** 列表范围只引用既有不可变块，不为每个批次复制全文或向量。 */
    public record Batch(int from, int to) { }
    private static void fail(IngestionResourceError error) { throw new IngestionResourceException(error); }
}
