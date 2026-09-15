# Codex Progress

## 任务目标

Docker Desktop 虚拟盘清空后，恢复共享 `dev-infra`，并把能力门户 http://127.0.0.1:5274/ 里每个项目的 Docker 服务**逐个**拉起。内存不够时停掉上一批项目应用，只保留共享中间件与门户。随后按 `seed-mock` 给**除 langchain4j 外**的门户项目灌测试/演示数据（写入真实库表，不写死页面）。不要重跑 langchain4j `manage.py backup`。不提交、不推送。

## 已完成

- 共享栈已重建并在跑：MySQL 8.4 `:43306`、PostgreSQL 16 `:45432`、Redis 7 `:46379`、Kafka 3.8 `:49092`、MinIO `:49000`、Nacos 2.4.3。未起 RabbitMQ、marketing-obs、Kibana。
- 数据恢复：langchain4j `deploy/.dev-infra-state/20260908-110350`；workflow `pg_restore` 53 张表；risk MySQL dump 17 张表。
- 门户 10 个项目此前已逐个起过并探活通过（12GiB 不能并存）。
- **seed-mock（2026-09-14）**，优先复用各仓已有脚本：
  | 项目 | 做法 | 入库结果 |
  |---|---|---|
  | marketing | `scripts/seed.sh` | 3 活动、权益/受众/旅程闭环 mock |
  | wms | `test-data/init-test-data.sh` | Cell A/B 主数据 + 入库 6 / 出库 3 / 履约 3，`DATA_VERIFY=SUCCESS` |
  | transaction-center | `scripts/seed-demo-business.py` | 3 订单、2 支付、4 退款；wipe 后旧清单已挪到 `demo-business-manifest.json.pre-wipe-20260914` |
  | drools | console `ACTIVITY_MARKETING_SEED_CATALOG_DATA=true` | `__dev__` 2 店 6 商品、`acme` 2 店 4 商品、行政区 3212 |
  | recsys | `import-items` + `seed-ads --ads=200 --advertisers=20` | item 9742；广告主 20（分片 10+10）、广告 200、创意 600、竞价词 614 |
  | benefit | 新增幂等 `scripts/seed-mock.sql` | tenant=`dev-tenant`：2 SKU、2 发放单、1 待补发 |
  | recon | 新增幂等 `scripts/seed-mock.sql` | 场景定义 2；`recon_src_*` 各 2 行；现金 ODS 应发 1 |
  | workflow | 沿用 9 月 8 日 restore | 历史流程 4、流程定义 4（运行中待办 0） |
  | risk | 沿用 dump | 案件 2、决策 2、规则草稿 4；引擎内 `MARKETING_AWARD` 绑定不在 DB |
  | langchain4j | 跳过（已 restore） | — |
- **knowledge-service 百炼拉起（2026-09-15）**：先前 `RAG_EMBEDDING_BASE_URL` 为空导致 crash loop、检索 500。已 `source load-bailian-env.sh` 后 `--force-recreate knowledge-service`；embedding 指向百炼 `text-embedding-v4`。顺带拉起停着的 `lc4j-qdrant` / `lc4j-elasticsearch`。网关未登录打 `/rag/query` 现为 401（服务可达），不再 500。
- **侧栏子菜单不跳转（2026-09-15）**：上次「留在工作台只滚动」不符合预期。已改回与 Agent/工作流一致：点「上传文档」等子菜单整页进入该能力的 `CapabilityRunner`；对话模式仍留在控制台只切模式。相关 vitest 57 项通过。前端容器已按此行为重建，网关仍烘焙 `http://localhost:18080`。

## 当前运行

- 能力门户 `:5274` + Casdoor `:8000` + SpiceDB
- 共享 `dev-infra` core + **lc4j Qdrant / Elasticsearch**
- langchain4j 栈（含已起来的 `knowledge-service`、网关 `:18080`、前端 `:8093`）
- **交易** `trade-api`（演示库已有业务单）
- **Drools MySQL** `:3307`（目录数据在卷里；console 已停）
- **recsys Postgres** `:55432` + Redis `:56379`（电影/广告在卷里）
- WMS 整栈已停（种子在 `wms-local_*` 卷，可再起）

## 未完成 / 已知缺口

- 12GiB Docker VM **无法同时**跑满 10 个项目控制台。
- recsys `ad_embedding=0`：未跑 `backfill-embedding`（需 embedding provider）。
- risk 管理面 `source_rule_binding` 为空；发布绑定需走治理 API，不能拿 E2E 草稿当线上规则。
- workflow 无运行中待办；要新待办需起审批服务走真实发起，不能手插 Flowable 运行表。
- WMS 接口校验跳过：缺 `WMS_TEST_BEARER_TOKEN`，匿名被拒。
- marketing `offer-decision-service` 在 generation 未激活时仍 503。
- 9 月 8 日之后写进旧 Docker 卷的数据无法恢复。

## Git 发布（进行中）

- 工作树仍有 knowledge / deploy / 其它前端脏改动，**不**并入这次提交。
- 本次只发布侧栏跳转修复：`fix/sidenav-capability-page` → 远程 `main`。

## 下一步（一次只起一个控制台）

```bash
unset COMPOSE_PROJECT_NAME

# 交易控制台
cd /Users/liruijun/personal/LLM/transaction-center
docker compose --project-directory deploy -f deploy/compose.yaml --env-file deploy/.env.demo up -d --no-build --wait

# Drools UI（目录已在库）
cd /Users/liruijun/personal/LLM/drools-demo
docker compose -f deploy/docker-compose.yml up -d --no-build

# recsys UI（item/广告已在库；embedding 仍空）
cd /Users/liruijun/personal/LLM/recsys
scripts/dev-local.sh up

# WMS（种子已在隔离卷）
cd /Users/liruijun/personal/LLM/wms-platform
docker compose --env-file .env -f compose.yaml up -d

# 权益/对账幂等补种
bash /Users/liruijun/personal/LLM/benefit-center/scripts/seed-mock.sh
bash /Users/liruijun/personal/LLM/recon-platform/scripts/seed-mock.sh
```

`COMPOSE_PROJECT_NAME` 只在调用共享 `dev-infra/compose.yaml` 且 cwd 不是该目录时设为 `dev-infra`。不要重跑 langchain4j `manage.py backup`。

## 恢复 Prompt

请读取 CODEX_PROGRESS.md。共享库与门户项目测试数据已按 seed-mock 写入。继续时按「下一步」只起一个控制台；未经授权不提交或推送。
