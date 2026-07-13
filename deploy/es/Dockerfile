# Elasticsearch + 中文分析器（analysis-smartcn），供 es-hybrid-rerank 全文混排使用。
# 版本对齐 knowledge-service 的 elasticsearch-java 客户端（Spring Boot 3.3.5 BOM ≈ 8.15.x）。
FROM docker.elastic.co/elasticsearch/elasticsearch:8.15.3
RUN bin/elasticsearch-plugin install --batch analysis-smartcn
