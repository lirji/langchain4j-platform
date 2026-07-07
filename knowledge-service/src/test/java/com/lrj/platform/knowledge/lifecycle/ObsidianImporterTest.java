package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.knowledge.graph.GraphStore;
import com.lrj.platform.knowledge.graph.Triple;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObsidianImporterTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static DocumentInfo docInfo(String displayName) {
        return new DocumentInfo("doc-" + displayName, "acme", displayName, "text/markdown",
                1, 1, 1, Instant.now(), null);
    }

    private static byte[] vaultZip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, UTF_8)) {
            for (var e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GraphStore> provider(GraphStore value) {
        ObjectProvider<GraphStore> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @Test
    void importsNotesAndMapsWikilinksToGraphTriples() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        DocumentService documents = mock(DocumentService.class);
        when(documents.upload(any(), any(), any(), any())).thenAnswer(inv -> docInfo(inv.getArgument(0)));
        GraphStore graph = mock(GraphStore.class);

        // vault：请假制度(frontmatter category=人事) → [[考勤制度]] 与 [[加班规定|加班]]；考勤制度 → [[请假制度]]；加班规定无 frontmatter
        byte[] zip = vaultZip(Map.of(
                "vault/HR/请假制度.md", "---\ncategory: 人事\n---\n员工请假见 [[考勤制度]] 和 [[加班规定|加班]]。",
                "vault/HR/考勤制度.md", "考勤依据 [[请假制度]]。",
                "vault/加班规定.md", "# 加班\n加班需审批。",
                "vault/.obsidian/app.json", "{\"ignored\":true}"));

        ObsidianImporter importer = new ObsidianImporter(documents, provider(graph));
        ObsidianImportResult result = importer.importVault(new ByteArrayInputStream(zip), "default");

        assertThat(result.notesImported()).isEqualTo(3); // .obsidian 配置被跳过
        assertThat(result.importedTitles()).containsExactlyInAnyOrder("请假制度", "考勤制度", "加班规定");
        assertThat(result.wikilinksAsTriples()).isEqualTo(3); // 请假→考勤, 请假→加班规定, 考勤→请假

        // frontmatter category 优先(人事)；无 frontmatter 用请求默认(default)
        verify(documents).upload(eq("请假制度.md"), eq("text/markdown"), any(), eq("人事"));
        verify(documents).upload(eq("加班规定.md"), eq("text/markdown"), any(), eq("default"));
        verify(documents, times(3)).upload(any(), any(), any(), any());

        // 双链 → 三元组(subject|链接到|object)，去别名/去 self
        ArgumentCaptor<List<Triple>> cap = ArgumentCaptor.forClass(List.class);
        verify(graph, times(2)).add(cap.capture()); // 请假制度(2条) + 考勤制度(1条)，加班规定无链接不调用
        List<Triple> all = new ArrayList<>();
        cap.getAllValues().forEach(all::addAll);
        assertThat(all).extracting(Triple::subject, Triple::relation, Triple::object)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("请假制度", "链接到", "考勤制度"),
                        org.assertj.core.groups.Tuple.tuple("请假制度", "链接到", "加班规定"),
                        org.assertj.core.groups.Tuple.tuple("考勤制度", "链接到", "请假制度"));
    }

    @Test
    void graphDisabled_stillImportsNotes_noTriples() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        DocumentService documents = mock(DocumentService.class);
        when(documents.upload(any(), any(), any(), any())).thenAnswer(inv -> docInfo(inv.getArgument(0)));

        byte[] zip = vaultZip(Map.of("v/a.md", "链接 [[b]]。"));
        ObsidianImporter importer = new ObsidianImporter(documents, provider(null)); // 图未启用

        ObsidianImportResult result = importer.importVault(new ByteArrayInputStream(zip), null);

        assertThat(result.notesImported()).isEqualTo(1);
        assertThat(result.wikilinksAsTriples()).isZero();
    }

    @Test
    void parse_extractsFrontmatterTitleAndCategory() {
        ObsidianImporter.Parsed p = ObsidianImporter.parse("---\ntitle: 我的标题\ncategory: manual\n---\n正文内容");
        assertThat(p.title()).isEqualTo("我的标题");
        assertThat(p.category()).isEqualTo("manual");
        assertThat(p.body().strip()).isEqualTo("正文内容");
    }

    @Test
    void linkTarget_stripsAliasAndHeading() {
        assertThat(ObsidianImporter.linkTarget("目标|别名")).isEqualTo("目标");
        assertThat(ObsidianImporter.linkTarget("目标#小节")).isEqualTo("目标");
        assertThat(ObsidianImporter.linkTarget("  目标  ")).isEqualTo("目标");
    }
}
