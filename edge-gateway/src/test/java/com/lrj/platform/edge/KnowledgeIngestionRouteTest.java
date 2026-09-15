package com.lrj.platform.edge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** 从真实配置绑定路由，验证专用路径优先以及未拆分部署的兼容回退。 */
class KnowledgeIngestionRouteTest {
    @Test
    void dedicatedRoutesPrecedeCatchAllAndResolveIndependentTargets() throws Exception {
        var env = environment().withProperty("KNOWLEDGE_INGEST_URI", "http://ingest:8084")
                .withProperty("KNOWLEDGE_QUERY_URI", "http://query:8084")
                .withProperty("KNOWLEDGE_ADMIN_URI", "http://admin:8084");
        var routes = Binder.get(env).bind("spring.cloud.gateway", GatewayProperties.class).get().getRoutes();
        var ingestion = routes.stream().filter(r -> r.getId().equals("knowledge-ingestion")).findFirst().orElseThrow();
        var query = routes.stream().filter(r -> r.getId().equals("knowledge")).findFirst().orElseThrow();
        var admin = routes.stream().filter(r -> r.getId().equals("knowledge-admin")).findFirst().orElseThrow();
        assertThat(routes.indexOf(ingestion)).isLessThan(routes.indexOf(query));
        assertThat(routes.indexOf(admin)).isLessThan(routes.indexOf(query));
        assertThat(ingestion.getUri().toString()).isEqualTo("http://ingest:8084");
        assertThat(query.getUri().toString()).isEqualTo("http://query:8084");
        assertThat(admin.getUri().toString()).isEqualTo("http://admin:8084");
        assertThat(ingestion.getPredicates().getFirst().getArgs().values())
                .containsExactly("/rag/ingestions", "/rag/ingestions/**");
        assertThat(admin.getPredicates()).anySatisfy(p -> {
            assertThat(p.getName()).isEqualTo("Method");
            assertThat(p.getArgs().values()).containsExactly("POST", "DELETE");
        });
    }

    @Test
    void combinedDeploymentsKeepExistingKnowledgeUri() throws Exception {
        var env = environment().withProperty("KNOWLEDGE_URI", "http://combined:8084");
        var routes = Binder.get(env).bind("spring.cloud.gateway", GatewayProperties.class).get().getRoutes();
        assertThat(routes.stream().filter(r -> r.getId().startsWith("knowledge")))
                .allSatisfy(r -> assertThat(r.getUri().toString()).isEqualTo("http://combined:8084"));
    }

    private MockEnvironment environment() throws Exception {
        var env = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return env;
    }
}
