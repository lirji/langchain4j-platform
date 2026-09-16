package com.lrj.platform.knowledge.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphSourceIdTest：锁住图三元组 sourceId 的版本 provenance 格式。写入、GC 前缀删除与查询侧还原
 * 共用这一个定义，所以这里同时断言「写出去的能被解回来」，避免三方各自解析再次分叉。
 */
class GraphSourceIdTest {

    @Test
    void writesAndParsesBackTheSameProvenance() {
        String sourceId = GraphSourceId.of("a1b2c3d4e5f60718", "3", "people.md#7");

        assertThat(sourceId).isEqualTo("a1b2c3d4e5f60718/v3/people.md#7");
        GraphSourceId parsed = GraphSourceId.parse(sourceId);
        assertThat(parsed.hasProvenance()).isTrue();
        assertThat(parsed.docId()).isEqualTo("a1b2c3d4e5f60718");
        assertThat(parsed.version()).isEqualTo("3");
        assertThat(parsed.displayName()).isEqualTo("people.md");
        assertThat(parsed.index()).isEqualTo("7");
    }

    @Test
    void gcPrefixMatchesEveryTripleOfThatDocumentVersion() {
        String prefix = GraphSourceId.prefix("doc-1", 3);

        assertThat(prefix).isEqualTo("doc-1/v3/");
        assertThat(GraphSourceId.of("doc-1", "3", "a.md#0")).startsWith(prefix);
        assertThat(GraphSourceId.of("doc-1", "3", "b.md#9")).startsWith(prefix);
        // 相邻版本不能被同一前缀带走，否则 GC 会删掉当前可见版本
        assertThat(GraphSourceId.of("doc-1", "4", "a.md#0")).doesNotStartWith(prefix);
    }

    @Test
    void legacySourceIdHasNoProvenanceAndKeepsOldDisplayParsing() {
        GraphSourceId parsed = GraphSourceId.parse("people.md#7");

        assertThat(parsed.hasProvenance()).isFalse();
        assertThat(parsed.docId()).isNull();
        assertThat(parsed.version()).isNull();
        assertThat(parsed.displayName()).isEqualTo("people.md");
        assertThat(parsed.index()).isEqualTo("7");
    }

    @Test
    void fileNameWithSlashStillYieldsDocumentAndVersion() {
        // 从左侧按前两个 / 切分：docId 是 16 hex、version 是 v<数字>，所以文件名里的 / 不会造成歧义
        GraphSourceId parsed = GraphSourceId.parse("doc-1/v2/notes/2026/plan.md#1");

        assertThat(parsed.docId()).isEqualTo("doc-1");
        assertThat(parsed.version()).isEqualTo("2");
        assertThat(parsed.displayName()).isEqualTo("notes/2026/plan.md");
        assertThat(parsed.index()).isEqualTo("1");
    }

    @Test
    void versionSegmentMustBeVNumberOtherwiseNoProvenanceIsClaimed() {
        // 形似但不合格的中间段不能被当成版本，否则查询侧会拿错版本号去和 Registry 比
        assertThat(GraphSourceId.parse("doc-1/v/plan.md#1").hasProvenance()).isFalse();
        assertThat(GraphSourceId.parse("doc-1/version3/plan.md#1").hasProvenance()).isFalse();
        assertThat(GraphSourceId.parse("doc-1/v3x/plan.md#1").hasProvenance()).isFalse();
        assertThat(GraphSourceId.parse("/v3/plan.md#1").hasProvenance()).isFalse();
        assertThat(GraphSourceId.parse("doc-1/v3").hasProvenance()).isFalse();
    }

    @Test
    void blankSourceIdIsInert() {
        assertThat(GraphSourceId.parse(null).hasProvenance()).isFalse();
        assertThat(GraphSourceId.parse(null).displayName()).isNull();
        assertThat(GraphSourceId.parse("  ").displayName()).isNull();
    }

    @Test
    void sourceWithoutChunkIndexKeepsWholeVisibleSourceAsDisplayName() {
        assertThat(GraphSourceId.parse("doc-1/v3/people.md").displayName()).isEqualTo("people.md");
        assertThat(GraphSourceId.parse("doc-1/v3/people.md").index()).isNull();
        assertThat(GraphSourceId.parse("doc-1/v3/people.md#").index()).isNull();
        assertThat(GraphSourceId.parse("doc-1/v3/people.md#").displayName()).isEqualTo("people.md#");
    }
}
