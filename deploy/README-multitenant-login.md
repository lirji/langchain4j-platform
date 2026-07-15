# 多租户登录（方案 C：Casdoor Shared Application + 选组织）——起栈 + 运行时验收 Runbook

把 langchain4j-platform 跑起来、用**同一个 Casdoor Shared Application** 验证多个租户各自登录、互不串。
配套身份夹具由 auth-platform 仓库的 `deploy/casdoor-tenant-provision.sh`（shared-app 版）建。

## 0. 模型速览

- **一个租户 = 一个 Casdoor organization**（token `owner`→tenantId、`sub`→userId）。
- **所有租户共用一个 Shared Application**：base `client_id=ragshared0client00000001`、`isShared=true`、
  `orgChoiceMode=Input`、built-in 拥有。登录/取 token 用派生 `client_id=<base>-org-<tenant>`（同一 secret），
  Casdoor 签出 `aud=[<base>-org-<tenant>]`、`owner=该 org`。
- edge 只需一次性把 **base** 放进 `CASDOOR_AUDIENCES`；它按 `<base>-org-*` 家族 + `(owner,aud)` 绑定放行
  （`CasdoorDecoderConfig.audienceValidator`）。**故新增租户对 edge 零改动、无需重启**——这正是 shared app 的意义。

## 1. 前置

- **auth-platform 栈**已起：Casdoor `:8000`(admin/123)、SpiceDB `:8543`(Bearer `authz_dev_key`)、
  auth-platform-server `:8200`（`AUTHZ_SERVER_SECURITY_ENABLED=true`、token `rag-svc-dev-key`）。
  见 auth-platform `deploy/docker-compose.yml` + `dev.sh`。
- **身份夹具**（在 auth-platform 仓库跑，幂等）：
  ```bash
  TENANT=acme USER=alice PASSWORD='Alice@12345' WIRE_SPICEDB=1 bash deploy/casdoor-tenant-provision.sh
  TENANT=beta USER=carol PASSWORD='Carol@12345' WIRE_SPICEDB=1 bash deploy/casdoor-tenant-provision.sh
  ```
  脚本会（幂等）确保 shared app `rag-shared` 存在、建 org/user/设密码、验证 `<base>-org-<tenant>` password grant。

## 2. ⚠️ 头号坑：镜像必须从最新代码重建

各服务 Dockerfile 是 `COPY target/*.jar`（edge）/ 构建期烘焙 VITE_*（前端）——**`--no-build` 复用旧镜像**。
若 edge 镜像早于 shared-app 提交（`ba9200e`），它带的是**旧的 aud 精确匹配校验**（无 `<base>-org-*` 家族），
则**所有 shared-app token 一律被 edge 静默 401**（`onErrorReturn` 吞掉 decode 错误、无日志），排查极耗时。

**自检**（部署类是否含家族逻辑）：
```bash
docker cp <edge容器>:/app/app.jar /tmp/e.jar
cd /tmp && unzip -o -q e.jar 'BOOT-INF/classes/com/lrj/platform/edge/CasdoorDecoderConfig*.class'
# 类里应能找到常量 "-org-"；找不到即为旧镜像，必须重建
```

**重建 edge**（改了 edge/其依赖后）：
```bash
# 仓库根：先打 jar（Dockerfile 只 COPY jar，不打包）
mvn -q -pl edge-gateway -am -DskipTests package -Dmaven.repo.local=/Users/liruijun/personal/repository
cd deploy && EDGE_HOST_PORT=18080 docker compose build edge-gateway
```

**重建前端**（切 oidc + shared base；VITE_* 是构建期 build args）：
```bash
cd deploy
SHOWCASE_AUTH_MODE=oidc SHOWCASE_CASDOOR_CLIENT_ID=ragshared0client00000001 \
SHOWCASE_EDGE_BASE_URL=http://localhost:18080 SHOWCASE_CASDOOR_ISSUER=http://localhost:8000 \
docker compose build capability-showcase-frontend
```

## 3. 起栈（enforce + Casdoor-only）

全栈（`start-all.sh` 默认 `--build` 会先 `mvn package` 再 compose up）：
```bash
cd deploy
EDGE_CASDOOR_ENABLED=true EDGE_CASDOOR_MODE=only CASDOOR_AUDIENCES=ragshared0client00000001 \
RAG_AUTHZ_MODE=enforce AUTHZ_SERVER_URL=http://host.docker.internal:8200 AUTHZ_SERVER_TOKEN=rag-svc-dev-key \
SHOWCASE_AUTH_MODE=oidc SHOWCASE_CASDOOR_CLIENT_ID=ragshared0client00000001 \
SHOWCASE_EDGE_BASE_URL=http://localhost:18080 SHOWCASE_CASDOOR_ISSUER=http://localhost:8000 \
bash start-all.sh --recreate
```

只重建/重指向 edge（栈已在跑、只想改 audiences）：
```bash
cd deploy
EDGE_HOST_PORT=18080 EDGE_CASDOOR_ENABLED=true EDGE_CASDOOR_MODE=only \
CASDOOR_AUDIENCES=ragshared0client00000001 \
docker compose up -d --no-deps --force-recreate edge-gateway
```
> 端口：edge `${EDGE_HOST_PORT:-8080}`（脚本强制 18080 避开 apollo）、前端 `:8093`、knowledge `:8084`。
> `INTERNAL_JWT_SECRET`/`SESSION_JWT_SECRET` edge 与 knowledge 必须一致（compose 默认值已一致，重建勿覆盖）。

## 4. curl E2E 验收（权威）

```bash
BASE=ragshared0client00000001; SEC=ragshared0secret000000000000000001; EDGE=http://localhost:18080
tok(){ curl -s -X POST http://localhost:8000/api/login/oauth/access_token \
  -d grant_type=password -d "client_id=${BASE}-org-$1" -d "client_secret=$SEC" \
  -d "username=$2" -d "password=$3" -d 'scope=openid profile email' \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))"; }
ATOK=$(tok acme alice 'Alice@12345'); BTOK=$(tok beta carol 'Carol@12345')

# acme 登录 → 只见本租户（授权）文档
curl -s -X POST $EDGE/rag/query -H "Authorization: Bearer $ATOK" \
  -H 'Content-Type: application/json' -d '{"query":"...","topK":5}'
# beta 登录 → 见不到 acme 文档（租户隔离）
curl -s -X POST $EDGE/rag/query -H "Authorization: Bearer $BTOK" \
  -H 'Content-Type: application/json' -d '{"query":"...","topK":5}'
# 无 token → 401（only 模式）
curl -s -o /dev/null -w '%{http_code}\n' -X POST $EDGE/rag/query \
  -H 'Content-Type: application/json' -d '{"query":"x","topK":1}'
```
判据：acme token → `tenant=acme`（看 knowledge 日志 `knowledge query tenant=...`）、命中本租户授权文档；
beta token → `tenant=beta`、命中不到 acme 文档；无 token → 401。
> enforce 下命中数取决于 SpiceDB 里该用户对当前 ES 文档是否有 `view`（doc-level ReBAC）。
> 若 ES 内容是新灌的、无对应关系元组，会全部被判权过滤 → 0 命中（属夹具/关系缺失，非登录问题）。

也可跑官方 `deploy/smoke-rag-tenant-authz.sh`（需带 `EDGE_URL/CASDOOR_CLIENT_ID/CASDOOR_CLIENT_SECRET/A_VISIBLE_DOC/B_DOC`）。

## 5. 浏览器登录

1. shared app 的 `redirectUris` 须含前端源的 `callback/login/oidc-silent`（`:8093` 或 dev `:5173`）——
   provision 脚本创建 shared app 时已带；已存在的 app 用 Casdoor `update-application` 追加。
2. 访问 `http://localhost:8093` → OIDC 登录页有**租户输入框** → 输 `acme`（alice/`Alice@12345`）或
   `beta`（carol/`Carol@12345`）→ 前端用 `<base>-org-<tenant>` 走 authorization_code+PKCE → 回调换 token → 调 edge。
3. 硬刷新清缓存（CSP/构建期端口曾漂移，见 `nginx.conf` envsubst 模板）。

## 6. 秒级回滚（不删关系）

- langchain4j：`RAG_AUTHZ_MODE=shadow/disabled`、`EDGE_CASDOOR_MODE=dual`、`SHOWCASE_AUTH_MODE=apikey` 后
  `start-all.sh --recreate`。
- auth-platform：`AUTHZ_SERVER_SECURITY_ENABLED=false` 后重启 server。
