package com.lrj.platform.knowledge.es;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.lifecycle.ParsedDocument;
import com.lrj.platform.knowledge.search.*;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 ES 隔离索引验证 mapping 加法升级/来源传递/租户过滤；只清理本测试随机索引。 */
class StructuredProvenanceEsTest {
    @Test
    void upgradesExistingMappingAndPreservesProvenanceThroughSearchAndFusion() throws Exception {
        String endpoint=System.getenv("KDP_ES_TEST_URL");
        assumeTrue(endpoint!=null && !endpoint.isBlank(),"requires isolated local ES test endpoint");
        String index="kdp06b-test-"+UUID.randomUUID().toString().replace("-","");
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var mapper=new ObjectMapper();
        try {
            request(http,endpoint,"PUT","/"+index,"{\"mappings\":{\"properties\":{\"tenantId\":{\"type\":\"keyword\"},\"text\":{\"type\":\"text\"}}}}");
            var props=new EsRagProperties();props.setUris(endpoint);props.setIndexName(index);props.setAnalyzer("standard");
            props.setEnabled(true);props.setIndexEnabled(true);props.setQueryEnabled(true);props.setFailFast(true);
            try(var gateway=new ElasticsearchEsGateway(props)) {
                var indexer=new ElasticsearchSegmentIndexer(gateway,props);
                var metadata=Metadata.from(Map.of("tenantId","alpha","docId","manual","index","0","version","2",
                        "source_page","3","source_location","page=3",ParsedDocument.SOURCE_TEXT,"original manual evidence"));
                var segment=TextSegment.from("generated summary original manual evidence",metadata);
                indexer.index(List.of(segment)); indexer.index(List.of(segment));
                gateway.ensureIndex(); // 已建立 mapping 的再次接入只检查，不需要重建索引。
                gateway.bulkUpsert(List.of(new EsSegmentDocument("beta","manual","private",null,"0","2","secret manual",0)));
                request(http,endpoint,"POST","/"+index+"/_refresh",null);
                var mapping=mapper.readTree(request(http,endpoint,"GET","/"+index+"/_mapping",null));
                assertThat(mapping.path(index).path("mappings").path("properties").path("sourceMetadata").path("enabled").asBoolean(true)).isFalse();
                var hits=gateway.search("alpha",null,"manual",10);
                assertThat(hits).hasSize(1);assertThat(hits.getFirst().sourceMetadata()).containsEntry("source_page","3");
                var source=new EsKeywordRetrievalSource(gateway,props,1);
                var request=new RetrievalRequest("manual",List.of("manual"),"alpha",null,10,0,null);
                var candidates=source.retrieve(request);
                assertThat(candidates).singleElement().satisfies(hit -> {
                    assertThat(hit.text()).isEqualTo("original manual evidence");
                    assertThat(hit.sourceMetadata()).containsEntry("source_page","3").doesNotContainKeys("tenantId",ParsedDocument.SOURCE_TEXT);
                });
                for(var strategy:List.of(FusionStrategy.WEIGHTED_MAX,FusionStrategy.RRF)) {
                    var fused=new HybridFusionService().fuse(List.of(candidates),strategy,60);
                    assertThat(fused.getFirst().sourceMetadata()).containsEntry("source_location","page=3");
                    assertThat(fused.getFirst().text()).doesNotContain("generated");
                }
                assertThat(gateway.search("unrelated",null,"manual",10)).isEmpty();
            }
        } finally { request(http,endpoint,"DELETE","/"+index,null); }
    }
    private String request(HttpClient client,String endpoint,String method,String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200,299);return response.body();
    }
}
