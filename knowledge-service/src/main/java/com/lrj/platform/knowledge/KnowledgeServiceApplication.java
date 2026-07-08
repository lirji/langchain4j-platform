package com.lrj.platform.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 排除 {@link DataSourceAutoConfiguration}：knowledge-service 默认无 SQL 源（向量/关键词/registry 均可内存实现）。
 * 仅当 {@code app.rag.graph.store=jdbc}（GraphRAG 用 MySQL 存三元组）时，由 {@link com.lrj.platform.knowledge.graph.GraphRagConfig}
 * 手动建独立 DataSource；否则不连库，保持零依赖 dev/test。这也修复了 graph 关闭时因 DataSource 自动装配无 url 而启动失败
 * （h2 仅 test scope，运行 jar 无内嵌库兜底）。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class KnowledgeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeServiceApplication.class, args);
    }
}
