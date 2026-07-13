# ES 混排数据查询速查（Kibana + curl）

配套 [es-hybrid-rerank.md](es-hybrid-rerank.md)。用于**直接查看 / 排障** ES 全文索引里的数据。

**索引**：`knowledge_segments_text`
**字段**：`tenantId` `docId` `displayName` `category` `index` `version`（都是 keyword，精确匹配用 `term`）、`text`（text，analyzer=`smartcn`，全文用 `match`）、`createdAt`（long）
**租户**：示例数据都在 `acme`（走 `dev-key-acme-ingest` 上传）
**Kibana**：`http://localhost:5601` → 左侧 **Dev Tools**，逐条点 ▶ 运行

> 关键规则：**keyword 字段用 `term`**（不分词、精确）；**`text` 字段用 `match`**（走 smartcn 分词 + BM25）。搞反了要么查不到、要么不走中文分词。

---

## 一、Kibana Dev Tools（DSL）

```jsonc
// ── 1. 索引概况 ──
GET _cat/indices/knowledge_segments_text?v
GET knowledge_segments_text/_count
GET knowledge_segments_text/_mapping           // 看 text 的 analyzer 是不是 smartcn

// ── 2. 浏览全部 chunk ──
GET knowledge_segments_text/_search
{
  "size": 20,
  "_source": ["displayName","category","index","text"],
  "query": { "match_all": {} }
}

// ── 3. 全文检索（= 应用 ES 分支实际发的 DSL：match text + tenant/category 过滤 + 高亮）──
GET knowledge_segments_text/_search
{
  "size": 5,
  "query": {
    "bool": {
      "must":   [ { "match": { "text": "退款怎么退" } } ],
      "filter": [
        { "term": { "tenantId": "acme" } },
        { "term": { "category": "客服" } }
      ]
    }
  },
  "highlight": { "fields": { "text": {} } }   // 命中的中文词被 <em> 标出
}

// ── 4. 看 smartcn 中文分词（理解 BM25 召回，最有用）──
GET knowledge_segments_text/_analyze
{ "analyzer": "smartcn", "text": "退款政策是什么，7天无理由退货，顺丰次日达" }

GET knowledge_segments_text/_analyze
{ "field": "text", "text": "无理由退货运费谁承担" }   // 用索引里 text 字段的实际分析器

// ── 5. 精确过滤（keyword 用 term）──
// 某篇文档的所有 chunk（<docId> 换成真实 id）
GET knowledge_segments_text/_search
{ "query": { "term": { "docId": "<docId>" } }, "sort": [ { "index": "asc" } ] }

// 按 category 浏览
GET knowledge_segments_text/_search
{ "query": { "term": { "category": "客服" } }, "_source": ["displayName","index","text"] }

// ── 6. 聚合：每个 category / 每篇文档有多少 chunk ──
GET knowledge_segments_text/_search
{
  "size": 0,
  "aggs": {
    "按category": { "terms": { "field": "category", "size": 50 } },
    "按文档":     { "terms": { "field": "displayName", "size": 50 } },
    "按租户":     { "terms": { "field": "tenantId", "size": 20 } }
  }
}

// ── 7. 为什么这条打这个分（BM25 explain，排障利器）──
GET knowledge_segments_text/_search
{ "explain": true, "size": 3, "query": { "match": { "text": "退款" } } }

// ── 8. 进阶匹配（or + 至少命中一半词）──
GET knowledge_segments_text/_search
{ "query": { "match": { "text": { "query": "无理由 退货 运费", "operator": "or", "minimum_should_match": "50%" } } } }

// ── 9. 某租户文档数 ──
GET knowledge_segments_text/_count
{ "query": { "term": { "tenantId": "acme" } } }

// ── 10. 清理（删某文档 chunk，谨慎）──
POST knowledge_segments_text/_delete_by_query
{ "query": { "bool": { "filter": [
  { "term": { "tenantId": "acme" } },
  { "term": { "docId": "<docId>" } }
]}}}
```

**图形化浏览**：左侧 **Discover** → 建 Data View，index pattern 填 `knowledge_segments_text`，time field 选「None」（或 `createdAt`），即可可视化筛选/翻看。

---

## 二、curl 版（不装 Kibana 也能查）

ES REST 端口 `9200`。加 `| python3 -m json.tool` 或 `| jq` 美化。

```bash
# 索引概况
curl -s 'http://localhost:9200/_cat/indices/knowledge_segments_text?v'
curl -s 'http://localhost:9200/knowledge_segments_text/_count'

# 全文检索（= 应用 ES 分支 DSL）+ 高亮
curl -s 'http://localhost:9200/knowledge_segments_text/_search' -H 'Content-Type: application/json' -d '{
  "size": 5,
  "query": { "bool": {
    "must":   [ { "match": { "text": "退款怎么退" } } ],
    "filter": [ { "term": { "tenantId": "acme" } }, { "term": { "category": "客服" } } ] } },
  "highlight": { "fields": { "text": {} } }
}' | python3 -m json.tool

# smartcn 分词
curl -s 'http://localhost:9200/knowledge_segments_text/_analyze' -H 'Content-Type: application/json' \
  -d '{"analyzer":"smartcn","text":"退款政策是什么，7天无理由退货"}' | python3 -m json.tool

# 某文档所有 chunk
curl -s 'http://localhost:9200/knowledge_segments_text/_search' -H 'Content-Type: application/json' \
  -d '{"query":{"term":{"docId":"<docId>"}},"sort":[{"index":"asc"}]}' | python3 -m json.tool

# 聚合：每 category chunk 数
curl -s 'http://localhost:9200/knowledge_segments_text/_search' -H 'Content-Type: application/json' \
  -d '{"size":0,"aggs":{"by_cat":{"terms":{"field":"category","size":50}}}}' | python3 -m json.tool

# 删某文档 chunk
curl -s -X POST 'http://localhost:9200/knowledge_segments_text/_delete_by_query?refresh=true' -H 'Content-Type: application/json' \
  -d '{"query":{"bool":{"filter":[{"term":{"tenantId":"acme"}},{"term":{"docId":"<docId>"}}]}}}'
```

**对比：应用层混合检索**（向量+ES+图谱 融合后的最终结果，带 `source=vector/es/hybrid`）走网关 `/rag/query`：

```bash
curl -s -X POST 'http://localhost:18080/rag/query' \
  -H 'X-Api-Key: dev-key-acme-ingest' -H 'Content-Type: application/json' \
  -d '{"query":"退款怎么退","topK":5,"category":"客服"}' | python3 -m json.tool
# 每个 hit 的 source 字段：vector=只向量 / es=只ES / hybrid=两者都命中 / graph=图谱
```

> ES DSL（上面 `/knowledge_segments_text/_search`）看的是**纯 BM25 那一路**；`/rag/query` 看的是**融合+RRF 后的最终排序**。排查"ES 分支到底召回了什么"用前者，排查"最终给用户什么"用后者。
