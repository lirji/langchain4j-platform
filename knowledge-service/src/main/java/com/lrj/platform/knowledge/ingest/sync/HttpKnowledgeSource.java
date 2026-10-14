package com.lrj.platform.knowledge.ingest.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import static com.lrj.platform.protocol.knowledge.KnowledgeSourceFeed.*;

/** 固定端点分页拉取，禁止重定向；响应、记录数、读取时间均有界，不转发内部JWT。 */
public class HttpKnowledgeSource {
    public static final int PAGE_SIZE = 25;
    private static final int MAX_BYTES = 1_048_576;
    private final ObjectMapper json;
    public HttpKnowledgeSource(ObjectMapper json) {
        this.json = json.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    /** cursor是数据参数，绝不能被解释成下一页URL。 */
    public Page fetch(KnowledgeSyncProperties.Source source, String cursor) {
        source.validate();
        HttpURLConnection connection = null;
        try {
            String query = "?limit=" + PAGE_SIZE + "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            connection = (HttpURLConnection) URI.create(source.endpoint().toASCIIString() + query).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");
            if (source.bearerToken() != null && !source.bearerToken().isBlank())
                connection.setRequestProperty("Authorization", "Bearer " + source.bearerToken());
            if (connection.getResponseCode() != 200) throw new SyncFailure("SOURCE_HTTP_ERROR");
            if (connection.getContentLengthLong() > MAX_BYTES) throw new SyncFailure("SOURCE_TOO_LARGE");
            byte[] bytes;
            try (var input = connection.getInputStream(); var output = new java.io.ByteArrayOutputStream()) {
                // readTimeout只限制单次阻塞，额外检查总时限，防止慢速持续响应无限占用调度线程。
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (System.nanoTime() > deadline) throw new SyncFailure("SOURCE_TIMEOUT");
                    if (output.size() + count > MAX_BYTES) throw new SyncFailure("SOURCE_TOO_LARGE");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            if (bytes.length > MAX_BYTES) throw new SyncFailure("SOURCE_TOO_LARGE");
            return validate(json.readValue(bytes, Page.class), cursor);
        } catch (SyncFailure failure) { throw failure; }
        catch (Exception failure) { throw new SyncFailure("SOURCE_UNAVAILABLE_OR_INVALID"); }
        finally { if (connection != null) connection.disconnect(); }
    }

    /** 整页先通过质量门禁；删除必须由显式事件表达，不能从缺失记录推断删除。 */
    static Page validate(Page page, String cursor) {
        if (page == null || page.items() == null || page.items().size() > PAGE_SIZE
                || page.nextCursor() == null || page.nextCursor().length() > 1024
                || !page.items().isEmpty() && page.nextCursor().equals(cursor))
            throw new SyncFailure("INVALID_PAGE");
        var ids = new HashSet<String>();
        for (Item item : page.items()) {
            if (item == null) throw new SyncFailure("INVALID_ITEM");
            if (item.id() == null || item.id().isBlank() || item.id().length() > 256 || !ids.add(item.id())
                    || item.revision() < 1 || !item.deleted() && (item.title() == null || item.title().isBlank() || item.title().length() > 256
                    || item.content() == null || item.content().isBlank() || item.content().length() > 100_000))
                throw new SyncFailure("INVALID_ITEM");
        }
        return page;
    }
}
