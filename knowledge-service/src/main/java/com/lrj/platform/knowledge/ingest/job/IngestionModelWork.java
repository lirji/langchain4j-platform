package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.ParentChildSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;

/** 每份文档一次准备独享的逻辑模型工作预算；显式传递，不用线程本地状态影响在线查询。 */
public final class IngestionModelWork {
    private static final int CHAT_FRAMING_ALLOWANCE = 32;
    private final IngestionResourceProperties limits;
    private final Runnable checkpoint;
    private final IngestionBatcher batcher;
    private final OpenAiTokenCountEstimator tokenizer;
    private final EnumMap<Stage,Long> inputTokens = new EnumMap<>(Stage.class);
    private final EnumMap<Stage,Integer> calls = new EnumMap<>(Stage.class);
    private long tokens, metadataBytes, parentBytes;
    private int totalCalls, contextualCalls;

    public IngestionModelWork(IngestionResourceProperties limits, Runnable checkpoint) {
        this.limits=limits;this.checkpoint=checkpoint;this.batcher=new IngestionBatcher(limits);
        this.tokenizer=new OpenAiTokenCountEstimator(limits.getTokenizerModel());
    }

    /** 整组预检查，按真实逻辑请求记账；失败调用仍消耗额度，禁止以降级退回预算。 */
    public List<Embedding> embed(EmbeddingModel model,List<TextSegment> segments,Stage stage) {
        checkpoint.run();
        var batches=batcher.embeddingBatches(segments);
        preflight(embeddingTokens(segments),batches.size());
        EmbeddingModel counted=batch -> {
            checkpoint.run();charge(stage,embeddingTokens(batch));
            return model.embedAll(batch);
        };
        return batcher.embed(counted,segments,checkpoint);
    }

    /** 在窗口字符串全部驻留前拒绝明显超量；条数只是批次次数下界，实际调用还会核验 token/字节。 */
    public void preflightWindowCount(int count) {
        checkpoint.run();preflight(0,Math.toIntExact(((long)count+limits.getMaxBatchSegments()-1)/limits.getMaxBatchSegments()));
    }
    /** 构造每个窗口后立即校验，避免大 buffer 在进入批次器前先放大堆占用。 */
    public WindowSize preflightWindow(String text,long previousTokens,long previousBytes) {
        checkpoint.run();
        if(text.length()>limits.getMaxBatchBytes()) throw new IngestionResourceException(IngestionResourceError.MODEL_INPUT_LIMIT);
        int count=tokenizer.estimateTokenCountInText(text),bytes=text.getBytes(StandardCharsets.UTF_8).length;
        long totalTokens=previousTokens+count,totalBytes=previousBytes+bytes;
        if(count>limits.getMaxSegmentTokens() || count>limits.getMaxBatchTokens() || bytes>limits.getMaxBatchBytes()
                || totalTokens>limits.getMaxDocumentTokens() || totalBytes>limits.getMaxDocumentBytes())
            throw new IngestionResourceException(IngestionResourceError.MODEL_INPUT_LIMIT);
        preflight(totalTokens,0);return new WindowSize(totalTokens,totalBytes);
    }
    public record WindowSize(long tokens,long bytes) { }

    /** 提示词包含任务说明、章节/邻近上下文、原片段和少量 framing 余量。 */
    public int promptTokens(String prompt) {
        int value=Math.addExact(tokenizer.estimateTokenCountInText(prompt),CHAT_FRAMING_ALLOWANCE);
        if(value>limits.getMaxContextPromptTokens()) fail();
        return value;
    }
    /** 在第一个上下文模型调用前核对整批已知工作量，避免明显超额时先付出调用成本。 */
    public void preflightContext(long promptTokens,int count) {
        checkpoint.run();
        if(count>limits.getMaxContextualCalls()-contextualCalls) fail();
        preflight(promptTokens,count);
    }
    public void beforeContext(String prompt) {
        checkpoint.run();
        if(contextualCalls>=limits.getMaxContextualCalls()) fail();
        charge(Stage.CONTEXTUAL,promptTokens(prompt));contextualCalls++;
    }
    public void checkpoint() { checkpoint.run(); }
    /** 可选前缀不允许扩大最终输入；超长结果弃用前缀，原文完整保留。 */
    public boolean acceptsPrefix(String value) {
        return value!=null && tokenizer.estimateTokenCountInText(value)<=limits.getMaxContextPrefixTokens();
    }

    /** 对完整 metadata 编码计量；保留 parent_text 契约，超限整文失败而非只删部分父块。 */
    public void validateMetadata(List<TextSegment> segments) {
        long bytes=0,parents=0;var mapper=new ObjectMapper();
        for(var segment:segments) {
            checkpoint.run();
            try { bytes+=mapper.writeValueAsBytes(segment.metadata().toMap()).length; }
            catch(java.io.IOException error) { throw new IngestionResourceException(IngestionResourceError.METADATA_LIMIT,error); }
            String parent=segment.metadata().getString(ParentChildSplitter.PARENT_TEXT);
            if(parent!=null) parents+=parent.getBytes(StandardCharsets.UTF_8).length;
            if(bytes>limits.getMaxMetadataBytes()) throw new IngestionResourceException(IngestionResourceError.METADATA_LIMIT);
        }
        metadataBytes=bytes;parentBytes=parents;
    }
    /** 只有固定阶段标签；不暴露正文、租户、文档 ID 或编造金额。 */
    public Snapshot snapshot() {
        return new Snapshot(java.util.Arrays.stream(Stage.values())
                .map(stage->new Usage(stage.code,inputTokens.getOrDefault(stage,0L),calls.getOrDefault(stage,0))).toList(),metadataBytes,parentBytes);
    }
    private long embeddingTokens(List<TextSegment> segments) {
        return segments.stream().mapToLong(s->tokenizer.estimateTokenCountInText(s.text())).sum();
    }
    private void preflight(long addedTokens,int addedCalls) {
        if(addedTokens>limits.getMaxModelInputTokens()-tokens || addedCalls>limits.getMaxModelCalls()-totalCalls) fail();
    }
    private void charge(Stage stage,long addedTokens) {
        preflight(addedTokens,1);tokens+=addedTokens;totalCalls++;
        inputTokens.merge(stage,addedTokens,Long::sum);calls.merge(stage,1,Integer::sum);
    }
    private static void fail() { throw new IngestionResourceException(IngestionResourceError.MODEL_WORK_LIMIT); }
    /** 显式稳定代码供低基数指标使用，新增阶段不改变历史值。 */
    public enum Stage {
        SEMANTIC("semantic"), CONTEXTUAL("contextual"), FINAL_EMBEDDING("final_embedding");
        private final String code;
        Stage(String code) {this.code=code;}
        public String code() {return code;}
    }
    public record Usage(String stage,long estimatedInputTokens,int logicalCalls) { }
    public record Snapshot(List<Usage> usage,long metadataBytes,long repeatedParentBytes) { }
}
