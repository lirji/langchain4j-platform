package com.lrj.platform.knowledge.graph;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * JdbcGraphStoreTest：在内存 H2（MySQL 兼容模式）上验证 {@link JdbcGraphStore} 持久化三元组、
 * 按租户与 category 遍历邻居，以及 removeBySourcePrefix 仅删除指定租户对应文档的三元组（不误删他租户）。
 */
class JdbcGraphStoreTest {

    @Test
    void missingSchemaFailsWithoutCreatingTables() {
        DriverManagerDataSource dataSource = dataSource("graph-missing-schema");

        assertThatThrownBy(() -> new JdbcGraphStore(dataSource))
                .isInstanceOf(DataAccessException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='rag_graph_triple'",
                Integer.class)).isZero();
    }

    @Test
    void persistsTriplesAndTraversesNeighborsByTenantAndCategory() {
        JdbcGraphStore store = store();
        store.add(List.of(
                new Triple("张三", "隶属于", "研发部", "people.md#0", "acme", "org"),
                new Triple("研发部", "使用", "LangChain4j", "tech.md#0", "acme", "org"),
                new Triple("张三", "隶属于", "财务部", "other.md#0", "globex", "org"),
                new Triple("张三", "提到", "预算", "finance.md#0", "acme", "finance")));

        assertThat(store.size()).isEqualTo(4);
        assertThat(store.entities("acme", "org")).containsExactly("张三", "研发部", "LangChain4j");
        assertThat(store.neighbors(Set.of("张三"), 2, "acme", "org"))
                .extracting(Triple::subject, Triple::relation, Triple::object, Triple::sourceId)
                .containsExactly(
                        tuple("张三", "隶属于", "研发部", "people.md#0"),
                        tuple("研发部", "使用", "LangChain4j", "tech.md#0"));
    }

    @Test
    void removeBySourcePrefixDeletesOnlyTenantDocumentTriples() {
        JdbcGraphStore store = store();
        store.add(List.of(
                new Triple("张三", "隶属于", "研发部", "people.md#0", "acme", "org"),
                new Triple("李四", "隶属于", "研发部", "people.md#1", "acme", "org"),
                new Triple("张三", "隶属于", "财务部", "people.md#0", "globex", "org")));

        int removed = store.removeBySourcePrefix("acme", "people.md#");

        assertThat(removed).isEqualTo(2);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.entities("globex", "org")).containsExactly("张三", "财务部");
    }

    @Test
    void removedTriplesKeepTombstonesAndExplicitReindexRestoresOneRow() {
        var source = migratedSource();
        var store = new JdbcGraphStore(source);
        var triple = new Triple("one", "uses", "two", "doc#0", "acme", "manual");
        store.add(List.of(triple));
        assertThat(store.removeBySourcePrefix("globex", "doc#")).isZero();
        assertThat(store.removeBySourcePrefix("acme", "doc#")).isEqualTo(1);
        assertThat(store.removeBySourcePrefix("acme", "doc#")).isZero();
        assertThat(store.size()).isZero();
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DELETED FROM RAG_GRAPH_TRIPLE", Integer.class)).isEqualTo(1);
        store.add(List.of(triple));
        assertThat(store.size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM RAG_GRAPH_TRIPLE", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT DELETED FROM RAG_GRAPH_TRIPLE", Integer.class)).isZero();
    }

    private static JdbcGraphStore store() {
        return new JdbcGraphStore(migratedSource());
    }

    private static DriverManagerDataSource migratedSource() {
        String url = System.getenv("MP_GRAPH_TEST_DB_URL");
        if (url != null && !url.contains("/lc4j_mp_it_")) throw new IllegalArgumentException("isolated test schema required");
        DriverManagerDataSource dataSource = url == null ? dataSource("graph-" + java.util.UUID.randomUUID())
                : new DriverManagerDataSource(url, System.getenv("MP_TEST_DB_USER"), System.getenv("MP_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(dataSource, SchemaName.KNOWLEDGE_GRAPH);
        if (url != null) new JdbcTemplate(dataSource).update("DELETE FROM RAG_GRAPH_TRIPLE");
        return dataSource;
    }

    private static DriverManagerDataSource dataSource(String database) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + database
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
