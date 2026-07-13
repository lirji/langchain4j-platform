package com.lrj.platform.knowledge.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ES 网关真实现（阶段3，es-hybrid-rerank）。刻意用低层 {@link RestClient} + 手工 JSON（非强类型 client），
 * API 面小、稳定，便于在无真实 ES 环境下可靠编译；行为验证走 fake 单测 + 可选真实 ES smoke。
 */
public class ElasticsearchEsGateway implements EsGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchEsGateway.class);

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String index;
    private final String analyzer;

    public ElasticsearchEsGateway(EsRagProperties props) {
        this.index = props.getIndexName();
        this.analyzer = props.getAnalyzer();
        this.client = buildClient(props);
    }

    private static RestClient buildClient(EsRagProperties props) {
        HttpHost[] hosts = parseHosts(props.getUris());
        var builder = RestClient.builder(hosts);
        boolean hasApiKey = props.getApiKey() != null && !props.getApiKey().isBlank();
        if (hasApiKey) {
            builder.setDefaultHeaders(new Header[]{new BasicHeader("Authorization", "ApiKey " + props.getApiKey())});
        } else if (props.getUsername() != null && !props.getUsername().isBlank()) {
            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(props.getUsername(), props.getPassword()));
            builder.setHttpClientConfigCallback(hc -> hc.setDefaultCredentialsProvider(cp));
        }
        builder.setRequestConfigCallback(rc -> rc
                .setConnectTimeout(props.getConnectTimeoutMs())
                .setSocketTimeout(props.getSocketTimeoutMs()));
        return builder.build();
    }

    private static HttpHost[] parseHosts(String uris) {
        List<HttpHost> hosts = new ArrayList<>();
        for (String raw : uris.split(",")) {
            String u = raw.trim();
            if (u.isEmpty()) {
                continue;
            }
            URI uri = URI.create(u);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(scheme) ? 443 : 9200);
            hosts.add(new HttpHost(uri.getHost() == null ? "localhost" : uri.getHost(), port, scheme));
        }
        if (hosts.isEmpty()) {
            hosts.add(new HttpHost("localhost", 9200, "http"));
        }
        return hosts.toArray(new HttpHost[0]);
    }

    @Override
    public void ensureIndex() {
        try {
            Response resp = client.performRequest(new Request("HEAD", "/" + index));
            if (resp.getStatusLine().getStatusCode() == 200) {
                return;
            }
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() != 404) {
                throw new UncheckedIOException(new IOException("ES index HEAD failed", e));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ObjectNode properties = mapper.createObjectNode();
        for (String field : List.of("tenantId", "docId", "displayName", "category", "index", "version")) {
            properties.set(field, mapper.createObjectNode().put("type", "keyword"));
        }
        properties.set("createdAt", mapper.createObjectNode().put("type", "long"));
        properties.set("text", mapper.createObjectNode().put("type", "text").put("analyzer", analyzer));
        ObjectNode body = mapper.createObjectNode();
        body.set("mappings", mapper.createObjectNode().set("properties", properties));
        try {
            Request create = new Request("PUT", "/" + index);
            create.setJsonEntity(mapper.writeValueAsString(body));
            client.performRequest(create);
            log.info("created ES index {} analyzer={}", index, analyzer);
        } catch (ResponseException e) {
            // #3：只吞并发下的 resource_already_exists；analyzer 缺失/mapping 非法等其它 400 必须暴露清晰错误。
            String errBody = responseBody(e);
            if (errBody.contains("resource_already_exists_exception")) {
                return;
            }
            throw new UncheckedIOException(new IOException(
                    "ES index create failed (analyzer=" + analyzer + " 插件是否已装? mapping 是否合法?): " + errBody, e));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String responseBody(ResponseException e) {
        try {
            return new String(e.getResponse().getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public void bulkUpsert(List<EsSegmentDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        try {
            for (EsSegmentDocument d : docs) {
                ObjectNode meta = mapper.createObjectNode();
                meta.put("_index", index).put("_id", d.id());
                ndjson.append(mapper.writeValueAsString(mapper.createObjectNode().set("index", meta))).append('\n');
                ObjectNode src = mapper.createObjectNode();
                src.put("tenantId", d.tenantId());
                src.put("docId", d.docId());
                src.put("displayName", d.displayName());
                src.put("category", d.category());
                src.put("index", d.index());
                src.put("version", d.version());
                src.put("text", d.text());
                src.put("createdAt", d.createdAt());
                ndjson.append(mapper.writeValueAsString(src)).append('\n');
            }
            Request req = new Request("POST", "/_bulk");
            req.setEntity(new StringEntity(ndjson.toString(),
                    ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
            Response resp = client.performRequest(req);
            JsonNode root = mapper.readTree(resp.getEntity().getContent());
            if (root.path("errors").asBoolean(false)) {
                throw new IllegalStateException("ES bulk had item errors: " + firstError(root));
            }
        } catch (ResponseException e) {
            throw new UncheckedIOException(new IOException("ES bulk failed", e));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String firstError(JsonNode root) {
        for (JsonNode item : root.path("items")) {
            JsonNode err = item.path("index").path("error");
            if (!err.isMissingNode()) {
                return err.toString();
            }
        }
        return "unknown";
    }

    @Override
    public void deleteByDoc(String tenantId, String docId) {
        ArrayNode filter = mapper.createArrayNode();
        filter.add(mapper.createObjectNode().set("term", mapper.createObjectNode().put("tenantId", tenantId)));
        filter.add(mapper.createObjectNode().set("term", mapper.createObjectNode().put("docId", docId)));
        ObjectNode body = mapper.createObjectNode();
        body.set("query", mapper.createObjectNode().set("bool", mapper.createObjectNode().set("filter", filter)));
        try {
            Request req = new Request("POST", "/" + index + "/_delete_by_query");
            req.addParameter("refresh", "true");
            req.setJsonEntity(mapper.writeValueAsString(body));
            client.performRequest(req);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return;
            }
            throw new UncheckedIOException(new IOException("ES delete_by_query failed", e));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<EsSearchHit> search(String tenantId, String category, String queryText, int limit) {
        ArrayNode filter = mapper.createArrayNode();
        filter.add(mapper.createObjectNode().set("term", mapper.createObjectNode().put("tenantId", tenantId)));
        if (category != null && !category.isBlank()) {
            filter.add(mapper.createObjectNode().set("term", mapper.createObjectNode().put("category", category)));
        }
        ObjectNode bool = mapper.createObjectNode();
        bool.set("must", mapper.createObjectNode().set("match", mapper.createObjectNode().put("text", queryText)));
        bool.set("filter", filter);
        ObjectNode body = mapper.createObjectNode();
        body.put("size", Math.max(1, limit));
        body.set("query", mapper.createObjectNode().set("bool", bool));
        try {
            Request req = new Request("POST", "/" + index + "/_search");
            req.setJsonEntity(mapper.writeValueAsString(body));
            Response resp = client.performRequest(req);
            JsonNode root = mapper.readTree(resp.getEntity().getContent());
            List<EsSearchHit> out = new ArrayList<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode src = hit.path("_source");
                double score = hit.path("_score").asDouble(0.0);
                out.add(new EsSearchHit(
                        text(src, "docId"), text(src, "displayName"), text(src, "category"),
                        text(src, "index"), text(src, "version"), text(src, "text"), score));
            }
            return out;
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return List.of();
            }
            throw new UncheckedIOException(new IOException("ES search failed", e));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            log.warn("ES client close failed: {}", e.toString());
        }
    }
}
