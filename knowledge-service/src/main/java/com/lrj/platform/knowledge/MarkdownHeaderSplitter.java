package com.lrj.platform.knowledge;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 按真实 Markdown 标题切分，保留原文和章节路径。CommonMark 语法树排除代码及 HTML 内的伪标题，
 * 同时支持 ATX/Setext 和混合层级。超长章节沿用既有递归兜底，最终统一编号避免索引覆盖。
 */
public class MarkdownHeaderSplitter implements DocumentSplitter {

    private final int maxSizePerSection;
    private final int minSizePerSection;
    private final DocumentSplitter fallbackForLongSection;
    private final TokenCountEstimator tokenEstimator;

    /** 保留字符计量、不合并的既有调用方式。 */
    public MarkdownHeaderSplitter(int maxSizePerSection, DocumentSplitter fallbackForLongSection) {
        this(maxSizePerSection, fallbackForLongSection, null, 0);
    }

    /** token 计量与递归兜底使用同一个计数器。 */
    public MarkdownHeaderSplitter(int maxSizePerSection, DocumentSplitter fallbackForLongSection,
                                  TokenCountEstimator tokenEstimator) {
        this(maxSizePerSection, fallbackForLongSection, tokenEstimator, 0);
    }

    /** minSize 为零时保持不合并；启用后只允许同一父标题下的同级小节合并。 */
    public MarkdownHeaderSplitter(int maxSizePerSection, DocumentSplitter fallbackForLongSection,
                                  TokenCountEstimator tokenEstimator, int minSizePerSection) {
        if (maxSizePerSection <= 0) throw new IllegalArgumentException("maxSizePerSection must be > 0");
        if (minSizePerSection < 0) throw new IllegalArgumentException("minSizePerSection must be >= 0");
        this.maxSizePerSection = maxSizePerSection;
        this.minSizePerSection = minSizePerSection;
        this.fallbackForLongSection = fallbackForLongSection;
        this.tokenEstimator = tokenEstimator;
    }

    /** 结构识别不重渲染 Markdown，避免代码、链接和表格原文被转换或丢失。 */
    @Override
    public List<TextSegment> split(Document document) {
        List<Section> sections = sections(document.text());
        if (minSizePerSection > 0) sections = mergeTiny(sections);
        List<TextSegment> chunks = new ArrayList<>();
        for (Section section : sections) {
            Metadata metadata = document.metadata().copy();
            if (section.title() != null) metadata.put("section", section.title());
            if (section.path().size() > 1) metadata.put("breadcrumb", String.join(" > ", section.path()));
            if (size(section.text()) <= maxSizePerSection || fallbackForLongSection == null) {
                chunks.add(TextSegment.from(section.text(), metadata));
            } else {
                chunks.addAll(fallbackForLongSection.split(Document.from(section.text(), metadata)));
            }
        }
        return ChunkIndexing.sequential(chunks);
    }

    /** 只把文档顶层 Heading 作为章节，引用或列表内部标题仍属于所在内容块。 */
    private static List<Section> sections(String text) {
        Node root = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(text);
        List<Heading> headings = new ArrayList<>();
        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading) headings.add(heading);
        }
        List<Section> sections = new ArrayList<>();
        int preludeEnd = headings.isEmpty() ? text.length() : headingStart(headings.getFirst());
        addSection(sections, text.substring(0, preludeEnd), null, 0, List.of());
        Deque<HeadingEntry> hierarchy = new ArrayDeque<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            String title = headingTitle(heading);
            while (!hierarchy.isEmpty() && hierarchy.peekLast().level() >= heading.getLevel()) {
                hierarchy.removeLast();
            }
            hierarchy.addLast(new HeadingEntry(heading.getLevel(), title));
            int end = i + 1 < headings.size() ? headingStart(headings.get(i + 1)) : text.length();
            addSection(sections, text.substring(headingStart(heading), end), title, heading.getLevel(),
                    hierarchy.stream().map(HeadingEntry::title).toList());
        }
        return sections;
    }

    private static int headingStart(Heading heading) {
        var span = heading.getSourceSpans().getFirst();
        // 包含标题前最多三个空格，原文不经 render；CRLF 等位置由 parser 的 source span 计算。
        return span.getInputIndex() - span.getColumnIndex();
    }

    private static String headingTitle(Heading heading) {
        StringBuilder title = new StringBuilder();
        heading.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) { title.append(text.getLiteral()); }
            @Override
            public void visit(Code code) { title.append(code.getLiteral()); }
        });
        return title.toString().strip();
    }

    private static void addSection(List<Section> sections, String text, String title, int level, List<String> path) {
        if (text.isBlank()) return;
        // 只移除边缘空行，保留代码首行缩进和 Markdown 行末双空格等有语义的空白。
        int first = 0;
        while (Character.isWhitespace(text.charAt(first))) first++;
        int start = first;
        while (start > 0 && text.charAt(start - 1) != '\n' && text.charAt(start - 1) != '\r') start--;
        int last = text.length() - 1;
        while (Character.isWhitespace(text.charAt(last))) last--;
        int end = last + 1;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
        sections.add(new Section(text.substring(start, end), title, level, path));
    }

    /** 合并不会越过父标题或层级；标题路径先独立解析，避免合并吞掉中间标题后污染后续路径。 */
    private List<Section> mergeTiny(List<Section> sections) {
        List<Section> merged = new ArrayList<>();
        Section pending = null;
        for (Section section : sections) {
            if (pending == null) {
                pending = section;
            } else if (size(pending.text()) < minSizePerSection && pending.sameParent(section)) {
                pending = pending.append(section);
            } else {
                merged.add(pending);
                pending = section;
            }
        }
        if (pending != null) {
            if (size(pending.text()) < minSizePerSection && !merged.isEmpty()
                    && merged.getLast().sameParent(pending)) {
                merged.set(merged.size() - 1, merged.getLast().append(pending));
            } else {
                merged.add(pending);
            }
        }
        return merged;
    }

    private int size(String text) {
        return tokenEstimator == null ? text.length() : tokenEstimator.estimateTokenCountInText(text);
    }

    private record HeadingEntry(int level, String title) { }

    private record Section(String text, String title, int level, List<String> path) {
        boolean sameParent(Section other) {
            return level > 0 && level == other.level
                    && path.subList(0, path.size() - 1).equals(other.path.subList(0, other.path.size() - 1));
        }

        Section append(Section other) {
            return new Section(text + "\n\n" + other.text, title, level, path);
        }
    }
}
