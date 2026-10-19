package com.lrj.platform.knowledge.ingest;

import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM Contextual Retrieval 增强：对每个 chunk 用章节和邻近原文窗口生成一句上下文前缀，拼到 chunk 前再嵌入。
 * 生成经 {@link ChunkContextualizer} 抽象注入，逻辑可确定性单测；单 chunk 生成失败保留原文（不崩入库）。
 */
public class LlmContextualEnricher implements ContextualEnricher {
    public static final String STATUS = "contextual_status";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmContextualEnricher.class);

    /** 稳定来源状态同时用于前端契约与低基数指标，不能以枚举名称作为外部值。 */
    public enum Outcome {
        APPLIED("applied"), FALLBACK("fallback"), PREFIX_LIMIT("prefix-limit"), SKIPPED_UNAVAILABLE("skipped-unavailable");
        private final String code;
        Outcome(String code) { this.code = code; }
        /** 返回显式稳定的来源状态，历史元数据不受枚举重命名影响。 */
        public String code() { return code; }
    }

    private final ChunkContextualizer contextualizer;
    private final int maxDocChars;

    public LlmContextualEnricher(ChunkContextualizer contextualizer, int maxDocChars) {
        this.contextualizer = contextualizer;
        this.maxDocChars = Math.max(256, maxDocChars);
    }

    @Override
    public List<TextSegment> enrich(String docText, List<TextSegment> segments) {
        return enrich(docText, segments, () -> { });
    }

    /** 检查点置于容错调用外，失租或预算错误不能被“增强失败保留原文”吞掉。 */
    @Override
    public List<TextSegment> enrich(String docText, List<TextSegment> segments, Runnable checkpoint) {
        return enrich(docText,segments,new com.lrj.platform.knowledge.ingest.job.IngestionModelWork(
                new com.lrj.platform.knowledge.ingest.job.IngestionResourceProperties(),checkpoint));
    }

    /** 先核对整批提示成本；模型失败保留原文，执行权/预算异常始终传播。 */
    @Override
    public List<TextSegment> enrich(String docText,List<TextSegment> segments,
            com.lrj.platform.knowledge.ingest.job.IngestionModelWork work) {
        if (segments == null || segments.isEmpty()) {
            return segments;
        }
        work.preflightContext(0,segments.size());long totalTokens=0;
        for(int index=0;index<segments.size();index++) {
            work.checkpoint();
            totalTokens+=work.promptTokens(ContextualEnrichmentConfig.prompt(window(segments,index),segments.get(index).text()));
        }
        work.preflightContext(totalTokens,segments.size());
        List<TextSegment> out = new ArrayList<>(segments.size());
        // 计数为调用栈局部状态；一次入库失败不能关闭其他文档或租户的增强。
        int consecutiveFailures = 0;
        for (int index=0;index<segments.size();index++) {
            work.checkpoint();
            TextSegment seg=segments.get(index);
            var metadata=seg.metadata().copy();
            if (consecutiveFailures >= work.contextualFailureLimit()) {
                metadata.put(STATUS, Outcome.SKIPPED_UNAVAILABLE.code());
                out.add(TextSegment.from(seg.text(), metadata));
                continue;
            }
            String docWindow=window(segments,index);
            work.beforeContext(ContextualEnrichmentConfig.prompt(docWindow,seg.text()));
            ContextAttempt attempt = safeContext(docWindow, seg.text());
            work.checkpoint();
            consecutiveFailures = attempt.failed() ? consecutiveFailures + 1 : 0;
            if (consecutiveFailures == work.contextualFailureLimit() && index + 1 < segments.size()) {
                // 只记录次数，不记录提供方原始异常、提示词、凭据或正文。
                log.warn("RAG contextual enhancement unavailable after {} consecutive failures; preserving {} remaining chunks",
                        consecutiveFailures, segments.size() - index - 1);
            }
            String prefix = attempt.prefix();
            if (prefix == null || prefix.isBlank() || !work.acceptsPrefix(prefix)) {
                metadata.put(STATUS, (prefix!=null && !prefix.isBlank()?Outcome.PREFIX_LIMIT:Outcome.FALLBACK).code());
                out.add(TextSegment.from(seg.text(),metadata));
            } else {
                String enriched = prefix.trim() + "\n\n" + seg.text();
                metadata.put(STATUS,Outcome.APPLIED.code());
                metadata.put(com.lrj.platform.knowledge.lifecycle.ParsedDocument.SOURCE_TEXT, seg.text());
                metadata.put(com.lrj.platform.knowledge.lifecycle.ParsedDocument.CONTEXT_PREFIX, prefix.trim());
                out.add(TextSegment.from(enriched, metadata));
            }
        }
        return out;
    }

    /** 空前缀可表示资料不足，不等同于提供方异常，因此不会累积失败次数。 */
    private ContextAttempt safeContext(String docWindow, String chunkText) {
        try {
            return new ContextAttempt(contextualizer.context(docWindow, chunkText), false);
        } catch (com.lrj.platform.knowledge.ingest.job.IngestionResourceException
                 | com.lrj.platform.knowledge.ingest.job.IngestionJobConflictException stopped) {
            throw stopped;
        } catch (RuntimeException e) {
            return new ContextAttempt(null, true);
        }
    }

    private record ContextAttempt(String prefix, boolean failed) { }

    /** 以当前章节/父块和相邻原块为中心，避免长文尾部始终看到文首；不做整文逐块扫描。 */
    private String window(List<TextSegment> segments,int index) {
        TextSegment current=segments.get(index);
        String heading=heading(current);
        String title=heading==null?"":slice(heading,0,Math.min(heading.length(),maxDocChars/4))+"\n";
        int budget=maxDocChars-title.length();
        String parent=current.metadata().getString(com.lrj.platform.knowledge.ParentChildSplitter.PARENT_TEXT);
        if(parent!=null && parent.length()<=budget) return title+parent;
        String middle=slice(current.text(),0,Math.min(current.text().length(),budget));
        int left=Math.max(0,(budget-middle.length()-2)/2);
        String before=index>0 && sameSection(current,segments.get(index-1))?segments.get(index-1).text():"";
        String after=index+1<segments.size() && sameSection(current,segments.get(index+1))?segments.get(index+1).text():"";
        before=slice(before,Math.max(0,before.length()-left),before.length());
        int right=Math.max(0,budget-middle.length()-before.length()-2);
        after=slice(after,0,Math.min(right,after.length()));
        return title+(before.isEmpty()?"":before+"\n")+middle+(after.isEmpty()?"":"\n"+after);
    }
    private static boolean sameSection(TextSegment first,TextSegment second) {
        return java.util.Objects.equals(heading(first),heading(second));
    }
    private static String heading(TextSegment segment) {
        String value=segment.metadata().getString("breadcrumb");
        return value==null?segment.metadata().getString("section"):value;
    }
    private static String slice(String text,int start,int end) {
        if(start>0 && start<text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
        if(end>0 && end<text.length() && Character.isHighSurrogate(text.charAt(end-1))) end--;
        return start>=end?"":text.substring(start,end);
    }
}
