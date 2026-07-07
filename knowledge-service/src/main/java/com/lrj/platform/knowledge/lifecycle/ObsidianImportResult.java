package com.lrj.platform.knowledge.lifecycle;

import java.util.List;

/**
 * Obsidian vault 导入结果。
 *
 * @param notesImported     成功导入的笔记数
 * @param wikilinksAsTriples 由 {@code [[双链]]} 生成并写入图谱的三元组数（图未启用时为 0）
 * @param skipped           跳过的文件数（空正文等）
 * @param importedTitles    导入的笔记标题列表
 */
public record ObsidianImportResult(int notesImported,
                                   int wikilinksAsTriples,
                                   int skipped,
                                   List<String> importedTitles) {}
