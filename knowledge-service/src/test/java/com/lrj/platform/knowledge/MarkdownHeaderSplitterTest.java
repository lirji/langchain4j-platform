package com.lrj.platform.knowledge;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测 markdown-header 策略的边界：每 section 1 chunk、超长 fallback、空文档、非 markdown 文档。
 */
class MarkdownHeaderSplitterTest {

    @Test
    void codeFencesAndHtmlCommentsDoNotCreateSections() {
        String md = "# Guide\n## Example\n```bash\n## shell comment\necho hi\n```\n"
                + "~~~text\n# literal heading\n~~~\n<!--\n## hidden\n-->\n## Next\ntext";
        List<TextSegment> chunks = new MarkdownHeaderSplitter(10_000, null).split(Document.from(md));
        assertThat(chunks.stream().map(s -> s.metadata().getString("section")))
                .containsExactly("Guide", "Example", "Next");
        assertThat(chunks.get(1).text()).contains("## shell comment", "# literal heading", "## hidden", "echo hi");
    }

    @Test
    void laterH1StartsANewHierarchyEvenWhenH2Exists() {
        List<TextSegment> chunks = splitter.split(Document.from("# A\n## One\ntext A\n# B\n## Two\ntext B"));
        assertThat(chunks.stream().map(s -> s.metadata().getString("section")))
                .containsExactly("A", "One", "B", "Two");
        assertThat(chunks.get(3).metadata().getString("breadcrumb")).isEqualTo("B > Two");
    }

    @Test
    void setextAndFormattedAtxHeadingsKeepTheirOriginalText() {
        String md = "Manual\n======\nIntro\n\n  ## **Install** `app` ###\nDo this.\n\nNext\n----\nDone.";
        List<TextSegment> chunks = splitter.split(Document.from(md));
        assertThat(chunks.stream().map(s -> s.metadata().getString("section")))
                .containsExactly("Manual", "Install app", "Next");
        assertThat(chunks.get(1).text()).contains("## **Install** `app` ###");
        assertThat(chunks.get(2).metadata().getString("breadcrumb")).isEqualTo("Manual > Next");
    }

    @Test
    void unclosedFenceAndIndentedCodeRemainInTheirSection() {
        String md = "## Example\n\n    ## indented code\n\n```\n## unclosed fence heading";
        List<TextSegment> chunks = splitter.split(Document.from(md));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).contains("    ## indented code", "## unclosed fence heading");
    }

    @Test
    void initialCodeIndentationAndCrLfHeadingPositionsArePreserved() {
        List<TextSegment> chunks = splitter.split(Document.from("\r\n    ## code\r\n    echo hi\r\n\r\n# Real\r\ntext  \r\n"));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().text()).startsWith("    ## code").contains("\r\n    echo hi");
        assertThat(chunks.getLast().metadata().getString("section")).isEqualTo("Real");
        assertThat(chunks.getLast().text()).endsWith("text  ");
    }

    @Test
    void tinySectionsNeverMergeAcrossParentHeadings() {
        DocumentSplitter merging = new MarkdownHeaderSplitter(600, DocumentSplitters.recursive(300, 50), null, 100);
        List<TextSegment> chunks = merging.split(Document.from("# A\n## One\na\n# B\n## Two\nb"));
        assertThat(chunks).hasSize(4);
        assertThat(chunks.getLast().metadata().getString("breadcrumb")).isEqualTo("B > Two");
    }

    private final DocumentSplitter splitter = new MarkdownHeaderSplitter(
            600, DocumentSplitters.recursive(300, 50));

    @Test
    void simpleMarkdown_oneSegmentPerSection() {
        String md = """
                # Title

                ## Section A

                Section A content.

                ## Section B

                Section B content.
                """;
        List<TextSegment> segs = splitter.split(Document.from(md));
        // 第一个是 # Title 之前 + 整段开头（不带 ##），单独成 1 个；A 和 B 各 1 个 = 共 3
        // 注意 # 开头不匹配 (?=^##+ )，所以 # Title 跟它下面的内容直到 ## Section A 之前是一个 section
        assertThat(segs).hasSize(3);
        assertThat(segs.get(1).text()).startsWith("## Section A");
        assertThat(segs.get(1).metadata().getString("section")).isEqualTo("Section A");
        assertThat(segs.get(2).metadata().getString("section")).isEqualTo("Section B");
    }

    @Test
    void indexMetadataIsSequential() {
        String md = """
                ## A
                a
                ## B
                b
                ## C
                c
                """;
        List<TextSegment> segs = splitter.split(Document.from(md));
        assertThat(segs).hasSize(3);
        assertThat(segs.get(0).metadata().getString("index")).isEqualTo("0");
        assertThat(segs.get(1).metadata().getString("index")).isEqualTo("1");
        assertThat(segs.get(2).metadata().getString("index")).isEqualTo("2");
    }

    @Test
    void longSection_fallsBackToRecursive_producingMultipleSegments() {
        // 一个超过 600 char 的 section，应该被 fallback splitter 切成多块
        String longBody = "a".repeat(2000);
        String md = "## Big\n\n" + longBody;
        List<TextSegment> segs = splitter.split(Document.from(md));
        // recursive(300, 50) 切 2000+ chars 应该 >1 段
        assertThat(segs.size()).isGreaterThan(1);
        // 都应该带 section 标题元信息
        assertThat(segs.get(0).metadata().getString("section")).isEqualTo("Big");
    }

    @Test
    void nonMarkdown_singleSegment() {
        // 没有 ## 行的纯文本，整篇成 1 个 segment（短的）
        String text = "just a short paragraph without any markdown headers.";
        List<TextSegment> segs = splitter.split(Document.from(text));
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).text()).contains("short paragraph");
        // 没有标题时不把正文第一行伪造成章节名称。
        assertThat(segs.getFirst().metadata().getString("section")).isNull();
    }

    @Test
    void deepHeadings_alsoSplit() {
        // ### / #### 也算 splitting boundary（regex 是 ##+）
        String md = """
                ## Top
                top body
                ### Sub
                sub body
                #### SubSub
                subsub body
                """;
        List<TextSegment> segs = splitter.split(Document.from(md));
        assertThat(segs).hasSize(3);
    }

    @Test
    void tokenUnit_thresholdMeasuredInTokens_notChars() {
        // 同一段英文：char 数 >> token 数（1 token ≈ 4 char）。
        // 阈值 30，section 约 120 char / ~25 token。
        //   - char 计量：120 > 30 → 触发 fallback 切多段
        //   - token 计量：~25 < 30 → 不切，整段 1 个 segment
        // 用这个差异证明 token estimator 路径真的生效（按 token 数比阈值，不是字符数）。
        String body = "The quick brown fox jumps over the lazy dog again and again to fill the section body.";
        String md = "## S\n\n" + body;
        TokenCountEstimator estimator = new OpenAiTokenCountEstimator("gpt-4o-mini");

        DocumentSplitter charMode = new MarkdownHeaderSplitter(30, DocumentSplitters.recursive(20, 5));
        DocumentSplitter tokenMode = new MarkdownHeaderSplitter(
                30, DocumentSplitters.recursive(20, 5, estimator), estimator);

        assertThat(charMode.split(Document.from(md))).hasSizeGreaterThan(1);   // 按字符超阈 → fallback
        assertThat(tokenMode.split(Document.from(md))).hasSize(1);             // 按 token 不超 → 整段
    }

    @Test
    void h1Only_splitsOnSingleHash_whenNoH2Present() {
        // 纯 # 分级的文档：历史行为是一刀不切成巨块，现在退而按 # 切
        String md = """
                # Alpha

                Alpha body.

                # Beta

                Beta body.
                """;
        List<TextSegment> segs = splitter.split(Document.from(md));
        assertThat(segs).hasSize(2);
        assertThat(segs.get(0).metadata().getString("section")).isEqualTo("Alpha");
        assertThat(segs.get(1).metadata().getString("section")).isEqualTo("Beta");
    }

    @Test
    void documentTitleAndFollowingSectionsRemainSeparate() {
        // 文档标题与后续两个 H2 仍各占一段；后续新增 H1 则按新章节正确重置路径。
        String md = "# Title\n\n## A\na\n## B\nb\n";
        List<TextSegment> segs = splitter.split(Document.from(md));
        assertThat(segs).hasSize(3);
        assertThat(segs.get(0).text()).startsWith("# Title");
    }

    @Test
    void deepHeadings_getBreadcrumbPath() {
        String md = """
                ## Top
                top body
                ### Sub
                sub body
                #### SubSub
                subsub body
                """;
        List<TextSegment> segs = splitter.split(Document.from(md));
        assertThat(segs).hasSize(3);
        // 顶层无父，深度 1 → 不注入 breadcrumb；section 叶子标题不变
        assertThat(segs.get(0).metadata().getString("breadcrumb")).isNull();
        assertThat(segs.get(0).metadata().getString("section")).isEqualTo("Top");
        assertThat(segs.get(1).metadata().getString("breadcrumb")).isEqualTo("Top > Sub");
        assertThat(segs.get(2).metadata().getString("breadcrumb")).isEqualTo("Top > Sub > SubSub");
    }

    @Test
    void tinySections_mergedWhenMinSizeSet() {
        // 一串极小 section，min-size 合并开启后并成更少的块
        DocumentSplitter merging = new MarkdownHeaderSplitter(
                600, DocumentSplitters.recursive(300, 50), null, 40);
        String md = "## A\na\n## B\nb\n## C\nc\n## D\nd\n";
        // 不合并：4 段
        assertThat(splitter.split(Document.from(md))).hasSize(4);
        // 合并（每段 ~4 char，min 40）：全并成 1 段
        List<TextSegment> merged = merging.split(Document.from(md));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).text()).contains("## A").contains("## D");
    }

    @Test
    void largeSections_eachStaysStandalone_notMerged() {
        // 两个各自 >min 的大 section：合并开启也不会被并到一起，各成一块
        DocumentSplitter merging = new MarkdownHeaderSplitter(
                10_000, DocumentSplitters.recursive(300, 50), null, 40);
        String big = "x".repeat(200);
        String md = "## Big1\n" + big + "\n## Big2\n" + big + "\n";
        List<TextSegment> segs = merging.split(Document.from(md));
        assertThat(segs).hasSize(2);
        assertThat(segs.get(0).text()).startsWith("## Big1");
        assertThat(segs.get(1).text()).startsWith("## Big2");
    }

    @Test
    void emptySections_skipped() {
        // ## A 后面紧跟 ## B，A 是空 section（只有 heading）—— 仍 emit 不跳
        // 但 leading 空白（## 出现前的空内容）被 strip 后跳过
        String md = "\n\n## A\n## B\n";
        List<TextSegment> segs = splitter.split(Document.from(md));
        assertThat(segs).hasSize(2);
    }
}
