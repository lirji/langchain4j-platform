package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.knowledge.graph.GraphStore;
import com.lrj.platform.knowledge.graph.Triple;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Obsidian vault 导入器：把一个 Obsidian 库（{@code .md} 笔记 + 文件夹 + {@code [[双链]]} + frontmatter）
 * 导入 knowledge-service 的 RAG。做的三件映射：
 * <ul>
 *   <li>每篇笔记 → 一个文档（正文经现有 {@link DocumentService#upload} 走 Markdown 分块 + embedding + 存储）；</li>
 *   <li>frontmatter 的 {@code category} / 顶层文件夹 → 文档 {@code category}（检索过滤维度）；</li>
 *   <li>{@code [[目标笔记]]} 双链 → GraphRAG 三元组 {@code 本笔记|链接到|目标笔记}，直接写图存储
 *       （不掺进向量索引），使 vault 的知识关系图谱可查（需 {@code app.rag.graph.enabled=true}）。</li>
 * </ul>
 *
 * <p>Obsidian 只是 Markdown 笔记的编写/组织工具，本身不做检索；本导入器把它作为<b>知识来源</b>接入平台 RAG。
 */
@Component
public class ObsidianImporter {

    private static final Logger log = LoggerFactory.getLogger(ObsidianImporter.class);

    /** 匹配 [[链接]]、[[链接|别名]]、[[链接#标题]] 的目标部分。 */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\[\\]]+?)]]");
    private static final String LINK_RELATION = "链接到";

    private final DocumentService documents;
    private final GraphStore graphStore; // 可空：仅 app.rag.graph.enabled=true 时存在

    public ObsidianImporter(DocumentService documents, ObjectProvider<GraphStore> graphStoreProvider) {
        this.documents = documents;
        this.graphStore = graphStoreProvider.getIfAvailable();
    }

    /**
     * 导入一个 zip 打包的 vault。
     *
     * @param vaultZip        vault 的 zip 输入流（含 .md 文件与文件夹结构）
     * @param defaultCategory 请求级默认 category（frontmatter category 优先，其次本参数，最后顶层文件夹名）
     */
    public ObsidianImportResult importVault(InputStream vaultZip, String defaultCategory) throws IOException {
        int imported = 0, triples = 0, skipped = 0;
        List<String> titles = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(vaultZip, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String path = entry.getName();
                if (entry.isDirectory() || !path.toLowerCase().endsWith(".md") || path.contains("/.obsidian/")) {
                    continue; // 跳过目录与 Obsidian 配置目录
                }
                String content = new String(readAll(zis), StandardCharsets.UTF_8);
                String title = baseName(path);
                Parsed parsed = parse(content);
                if (parsed.body().isBlank()) {
                    skipped++;
                    continue;
                }
                String category = firstNonBlank(parsed.category(), defaultCategory, topFolder(path));
                String noteTitle = firstNonBlank(parsed.title(), title);

                String docId = documents.upload(noteTitle + ".md", "text/markdown", parsed.body(), category).docId();
                imported++;
                titles.add(noteTitle);
                triples += ingestWikilinks(noteTitle, parsed.body(), docId, category);
            }
        }
        log.info("obsidian import: 笔记 {} 篇, 双链三元组 {} 条, 跳过 {}", imported, triples, skipped);
        return new ObsidianImportResult(imported, triples, skipped, titles);
    }

    /** 把正文中的 [[双链]] 作为 {@code 本笔记|链接到|目标} 三元组直接写图存储；图未启用时 no-op。 */
    private int ingestWikilinks(String noteTitle, String body, String docId, String category) {
        if (graphStore == null) {
            return 0;
        }
        String tenantId = TenantContext.current().tenantId();
        Set<String> targets = new LinkedHashSet<>();
        Matcher m = WIKILINK.matcher(body);
        while (m.find()) {
            String target = linkTarget(m.group(1));
            if (!target.isBlank() && !target.equalsIgnoreCase(noteTitle)) {
                targets.add(target);
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }
        List<Triple> edges = new ArrayList<>(targets.size());
        for (String target : targets) {
            edges.add(new Triple(noteTitle, LINK_RELATION, target, docId, tenantId, category));
        }
        graphStore.add(edges);
        return edges.size();
    }

    /** [[目标|别名]] / [[目标#标题]] → 取「目标」并去空白。 */
    static String linkTarget(String raw) {
        String t = raw;
        int pipe = t.indexOf('|');
        if (pipe >= 0) t = t.substring(0, pipe);
        int hash = t.indexOf('#');
        if (hash >= 0) t = t.substring(0, hash);
        return t.trim();
    }

    /** 解析 frontmatter（--- ... ---）拿 title/category，其余作正文。极简行解析，不引 YAML 依赖。 */
    static Parsed parse(String content) {
        String title = null, category = null;
        String body = content;
        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int firstNl = trimmed.indexOf('\n');
            int end = trimmed.indexOf("\n---", firstNl); // 关闭的 --- 行
            if (firstNl > 0 && end > firstNl) {
                String front = trimmed.substring(firstNl + 1, end);
                int bodyStart = trimmed.indexOf('\n', end + 1);
                body = bodyStart > 0 ? trimmed.substring(bodyStart + 1) : "";
                for (String line : front.split("\\R")) {
                    String l = line.trim();
                    if (l.regionMatches(true, 0, "title:", 0, 6)) title = stripQuotes(l.substring(6).trim());
                    else if (l.regionMatches(true, 0, "category:", 0, 9)) category = stripQuotes(l.substring(9).trim());
                }
            }
        }
        return new Parsed(title, category, body);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 文件名去路径去 .md。 */
    static String baseName(String path) {
        String name = path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase().endsWith(".md")) name = name.substring(0, name.length() - 3);
        return name;
    }

    /** 顶层文件夹名（zip 常含一层 vault 根目录，取第一段非根目录）。 */
    static String topFolder(String path) {
        String[] parts = path.split("/");
        // parts[0] 通常是 vault 根目录名；若还有下一层文件夹，用它作 category
        if (parts.length >= 3) return parts[1];
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    record Parsed(String title, String category, String body) {}
}
