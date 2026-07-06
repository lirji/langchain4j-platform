package com.lrj.platform.knowledge.graph;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class JdbcGraphStoreTest {

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

    private static JdbcGraphStore store() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:graph-" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return new JdbcGraphStore(dataSource);
    }
}
