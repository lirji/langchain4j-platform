# langchain4j-platform Helm 部署

伞状（umbrella）Helm chart，把 docker-compose 的整套微服务栈翻译成 k8s 部署清单。
纯部署清单，不含任何 Java 代码改动。

```
deploy/helm/platform/
├── Chart.yaml                       # 伞状 chart（依赖 vendored 库 chart platform-lib）
├── values.yaml                      # 全部可调参数（见下）
├── charts/platform-lib/             # 库 chart（type: library，不单独安装）
│   └── templates/
│       ├── _helpers.tpl             # labels / env / envFrom 渲染
│       ├── _deployment.tpl          # 可复用 Deployment 模板
│       ├── _service.tpl             # 可复用 Service 模板
│       ├── _serviceaccount.tpl      # 独立且不挂 token 的 ServiceAccount
│       ├── _pdb.tpl                 # 可复用 PDB 模板
│       └── _hpa.tpl                 # 可复用 HPA 模板
└── templates/
    ├── workloads.yaml               # 遍历 values.services，渲染 SA/Deployment/Service/HPA/PDB
    ├── networkpolicy.yaml           # 默认拒绝 + 必要 ingress/egress
    ├── migration-secret.yaml        # 仅本地占位迁移 Secret Hook
    ├── migrations.yaml              # 发布前版本化迁移 Hook Job
    ├── external-services.yaml       # 外部基础设施 ExternalName Service
    ├── configmap.yaml               # 非敏感 base-url/flag → platform-config
    ├── secret.yaml                  # 敏感项占位 Secret（含 edge、AgentScope 与最小权限业务 Secret）
    ├── externalsecret-sample.yaml   # External Secrets Operator 样例 CRD（默认关）
    └── ingress.yaml                 # edge-gateway 对外 Ingress（默认关）
deploy/helm/platform-migration-externalsecret.example.yaml # 生产迁移 Secret 预置样例（chart 外）
```

## 快速开始

```bash
# 校验
helm lint deploy/helm/platform
helm template platform deploy/helm/platform            # 离线渲染全部资源，无需联网

# 安装（先备好命名空间与镜像仓库/外部基础设施）
helm install platform deploy/helm/platform -n platform --create-namespace \
  --set global.image.registry=<your-registry> \
  --set global.image.tag=<git-sha> \
  --set config.RAG_EMBEDDING_BASE_URL=https://REPLACE_WITH_WORKSPACE_ID.cn-beijing.maas.aliyuncs.com/compatible-mode/v1 \
  --set config.RAG_RERANK_BAILIAN_BASE_URL=https://REPLACE_WITH_WORKSPACE_ID.cn-beijing.maas.aliyuncs.com/compatible-api/v1 \
  --set-string secrets.shared.RAG_EMBEDDING_API_KEY=REPLACE_WITH_BAILIAN_API_KEY \
  --set-string secrets.shared.RAG_RERANK_BAILIAN_API_KEY=REPLACE_WITH_BAILIAN_API_KEY
```

> 库 chart `platform-lib` 已 vendored 在 `charts/` 下，`helm template`/`lint` 无需 `helm dependency build`（离线可用）。
> 生产不要把 API Key 留在命令历史中；上例只展示字段，实际使用 ESO/Vault 或受控 values 文件。

## 百炼模型栈

Chart 与 Docker 默认口径一致：

- embedding：`text-embedding-v4`，1024 维、单批 10 条，collection 基名
  `knowledge_segments_bailian_v4`；
- rerank：`qwen3-rerank`，`RAG_RERANK_TYPE=bailian`；
- vision：应用侧使用逻辑名 `vision-default`。本 Chart 的 LiteLLM 是外部托管服务，必须在外部
  LiteLLM 配置中将该逻辑名映射到百炼 `qwen3-vl-plus`（或 `qwen3.7-plus`/`qwen3.6-flash`）。

embedding 使用 `compatible-mode/v1`，rerank 使用 `compatible-api/v1`，两个 base URL 不能互换。
API Key 放 `platform-secrets`；启用 ESO 时，模板会从
`<vaultPath>/bailian` 的 `api-key` 属性同步到 embedding 与 rerank 两个环境变量。

## values 结构

| 顶层键 | 作用 |
| --- | --- |
| `global.image` | 镜像仓库前缀 / tag / 拉取策略。单服务镜像 = `<registry>/<服务名>:<tag>`，可在 `services.<svc>.image` 覆盖。 |
| `global.envFrom` | 所有服务只注入非敏感 `platform-config`；Secret 禁止整包 envFrom。 |
| `global.podSecurityContext` / `global.securityContext` | 非 root、seccomp、只读根文件系统、drop capabilities 等默认限制。 |
| `global.topologySpread` | hostname/zone 拓扑分散。 |
| `global.probes` | 存活/就绪探针，复用 actuator health group（liveness/readiness 路径与阈值）。 |
| `global.resources` | 默认 requests/limits，可被 `services.<svc>.resources` 覆盖。 |
| `config.*` | **非敏感** base-url / feature flag → ConfigMap `platform-config`。 |
| `secrets.*` | **敏感项**占位值 → Secret；生产用 ESO 覆盖（见下）。 |
| `externalSecrets.*` | External Secrets Operator 对接开关与 Vault 路径。 |
| `networkPolicy.*` | 默认拒绝、ingress namespace 和显式 egress CIDR。 |
| `externalInfra.*` | 外部托管基础设施的 ExternalName 目标 FQDN。 |
| `services.*` | 每个业务服务的 `port` / `replicaCount` / `hpa` / 覆盖项。 |
| `ingress.*` | edge-gateway 对外暴露。 |

## Service DNS 与 docker-compose 名对应

**核心不变量：k8s Service 名 == docker-compose 服务名 == 各服务 env 里硬编码的主机名。**
因此各服务现有的跨服务 base-url（如 `KNOWLEDGE_BASE_URL=http://knowledge-service:8084`）在 k8s 内近零改动即可解析，无需改 Java/配置。

业务服务（`workloads.yaml` 渲染 ClusterIP Service，端口 == 容器端口）：

| Service / compose 名 | 端口 | 被谁按 DNS 调用 |
| --- | --- | --- |
| `edge-gateway` | 8080 | 对外入口；eval → `http://edge-gateway:8080` |
| `config-server` | 8888 | 各服务 `CONFIG_SERVER_URI=http://config-server:8888` |
| `conversation-service` | 8081 | edge-gateway 路由 |
| `workflow-service` | 8082 | edge-gateway 路由 |
| `analytics-service` | 8083 | agent → `ANALYTICS_BASE_URL` |
| `knowledge-service` | 8084 | conversation/agent → `KNOWLEDGE_BASE_URL` |
| `agentscope-orchestrator` | 8085 | edge/interop 默认 Agent 目标 |
| `agent-service` | 8085 | 旧 Java 整服务回滚目标 |
| `async-task-service` | 8086 | workflow/agent → `ASYNC_TASK_BASE_URL` |
| `channel-service` | 8087 | edge-gateway 路由 |
| `interop-service` | 8088 | edge-gateway 路由 |
| `eval-service` | 8089 | edge-gateway 路由（回归客户端） |
| `vision-service` | 8090 | agent → `VISION_BASE_URL` |
| `voice-service` | 8091 | edge-gateway 路由（`VOICE_URI`） |
| `auth-service` | 8092 | edge-gateway 路由（`AUTH_URI`）；登录 + RBAC |

> 前端 `capability-showcase-frontend` 是独立静态站（Vite/nginx），不在本 chart 内，按需单独部署或托管。

外部基础设施（`external-services.yaml` 渲染 ExternalName Service，同名指向外部实例）：

| Service / compose 名 | 客户端 env 里的引用 | ExternalName 目标（`externalInfra.<x>.externalName`） |
| --- | --- | --- |
| `mysql` | `jdbc:mysql://mysql:3306/...` | `mysql.external.example.com` |
| `redis` | `redis:6379` | `redis.external.example.com` |
| `kafka` | `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` | `kafka.external.example.com` |
| `qdrant` | `QDRANT_HOST=qdrant` `QDRANT_PORT=6334` | `qdrant.external.example.com` |
| `elasticsearch` | `RAG_ES_URIS=http://elasticsearch:9200` | `elasticsearch.external.example.com` |
| `litellm` | `GATEWAY_BASE_URL=http://litellm:4000/v1` | `litellm.external.example.com` |

## 指向外部基础设施（不自建 StatefulSet）

chart 只为 MySQL/Redis/Kafka/Qdrant/LiteLLM 建 **ExternalName** Service 指向外部托管实例：

```bash
--set externalInfra.mysql.externalName=my-rds.abcd.rds.amazonaws.com \
--set externalInfra.kafka.externalName=b-1.msk.amazonaws.com \
--set externalInfra.litellm.externalName=litellm.internal.corp
```

**只有 IP、没有 DNS 名时**（ExternalName 只接受 CNAME，不接受裸 IP）：把该项 `enabled=false`，
改为直接在 `config.*` 覆盖对应 base-url 指向 IP，例如：

```bash
--set externalInfra.litellm.enabled=false \
--set config.GATEWAY_BASE_URL=http://10.0.0.9:4000/v1
```

## 内部 JWT RS256（gateway 私钥 / 下游公钥）

对应 `platform.security.jwt.*`（`InternalSecurityProperties`）。密钥拆两个 Secret 以缩小轮转爆炸半径：

- `platform-config` 携带 algorithm、issuer、唯一 audience、key-id、clock-skew（非敏感）；
  AgentScope 的等价 `INTERNAL_JWT_*` 值必须一致。
- `platform-secrets` 携带 `PLATFORM_SECURITY_JWT_PUBLIC_KEY`（验签公钥）→ 每个 workload 只通过
  `secretKeyRef` 注入这一项；禁止 envFrom 整个 Secret。
- `edge-gateway-jwt` 携带 `PLATFORM_SECURITY_JWT_PRIVATE_KEY`（签发私钥）→ **仅 edge-gateway** envFrom。
  下游 Deployment 渲染结果不含私钥，验证：
  ```bash
  helm template platform deploy/helm/platform -s templates/workloads.yaml | grep -c PRIVATE_KEY   # 私钥只出现在 gateway 引用处
  ```

token 还必须包含 `token_use=internal_access`、`jti/iat/exp`，有效期不得超过配置的 `jwt-ttl`；
service callback token 不能直接进入业务服务。升级既有集群时，先只升级全部 edge signer，等待至少
一个旧 token TTL，再升级 AgentScope 和 Java reader，避免滚动窗口内旧形态 token 被严格 reader 拒绝。

## async-task worker 凭据隔离

`async-task-worker` Secret 只挂载到 `async-task-service` 和实际调用 lease/status/events 的 worker。
非敏感的 header/issuer/audience/kid/TTL/skew 由 `platform-config` 统一注入 Java 与 Python，
`deploy/test-production-cutover-config.sh` 会同时检查值一致性、密钥长度/不复用和 Deployment
挂载白名单。轮换或回滚时必须把 signer/verifier 视为一个兼容单元；禁止回退为普通内部 JWT。

生成一对密钥（示例）：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```

AgentScope 另外使用两个只注入 `agentscope-orchestrator` 的 Secret：`agentscope-confirmation`
负责参数绑定确认，`agentscope-downstream` 负责 MCP/Browser/Code 的 audience/action 绑定短时委托。
两把 key 不得互相复用，也不得复用内部 JWT key；`deploy/test-production-cutover-config.sh` 会检查引用隔离。

## callback allowlist 与签名密钥隔离

async-task、workflow、interop 三个 HTTP callback 发送端均显式启用 HTTPS origin allowlist、DNS/SSRF 重校验和禁止重定向。允许的公网 origin 分别配置在 `config.ASYNC_TASK_CALLBACK_ALLOWED_ORIGINS`、`config.WORKFLOW_CALLBACK_ALLOWED_ORIGINS`、`config.INTEROP_CALLBACK_ALLOWED_ORIGINS`。只有 async-task-service 注入精确内网 URL `http://interop-service:8088/interop/a2a/push-callback`，不得扩大为整个 origin。

签名 key 分别位于 `async-task-callback`、`workflow-callback`、`interop-callback` Secret，并只挂对应 Deployment；ESO 模板从三个独立 Vault 路径读取。三把 key 至少 32 字节且互不复用。应用层 DNS 检查不能消除解析与连接间的 TOCTOU，生产还必须配置 egress NetworkPolicy/防火墙，禁止访问 metadata、控制面和非必要私网。接收协议与安全回滚见 [webhook-security.md](../../docs/平台工程/webhook-security.md)。

## 会话 JWT 与 auth-service（登录 / RBAC）

登录会话 JWT 由 auth-service 签发、edge-gateway 验签后换发内部 JWT。其密钥 `SESSION_JWT_SECRET` 放**第三个** Secret
`auth-session-jwt`，**只** envFrom 注入 `auth-service` 与 `edge-gateway`（`secrets.sessionJwt`）——绝不注入其它下游（下游只认内部 JWT）。验证只有这两个 Deployment 引用：

```bash
helm template platform deploy/helm/platform | grep -c auth-session-jwt   # 期望 2 处 envFrom 引用（auth + edge）+ 1 处 Secret 定义
```

RBAC 相关 env 在 `services.auth-service.env`，**生产默认全关**分阶段灰度（`AUTH_RBAC_ENABLED=false`、`AUTH_RBAC_ADMIN_WRITES_ENABLED=false`、`AUTH_SEED_ENABLED=false`），开启前须设 `AUTH_RBAC_BOOTSTRAP_ADMIN_USERS` 真实名单并确认迁移完成。详见 `docs/平台工程/rbac-and-public-kb.md`。

api-key→租户 目录以 Secret（`edge-gateway-apikeys`）卷挂载到 `/etc/platform/apikeys/apikeys.yaml`，
gateway 用 `SPRING_CONFIG_IMPORT` 追加 `optional:file:` 导入，避免把 api-key 目录烤进镜像。

## 配置中心（Spring Cloud Config Server）

`config-server` 作为集群内服务部署（native profile，读打包进 jar 的 `config/`）。各服务
`CONFIG_SERVER_URI=http://config-server:8888`（在 `platform-config` 里），配合各服务 `application.yml`
的 `spring.config.import=optional:configserver:...` 接入；config-server 不可达也不阻断启动（optional + `${ENV:default}` 兜底）。
切 git 后端：`--set services.config-server.env[0].value=git` 并补 `SPRING_CLOUD_CONFIG_SERVER_GIT_URI`。

## 密钥：从占位 Secret 迁到 External Secrets Operator

默认 `secrets.create=true` 渲染**占位** Secret（值为 `change-me`/`REPLACE_WITH_...`，切勿用于生产）。
生产迁移到 ESO + Vault：

```bash
# 1) 集群装 External Secrets Operator 并配 ClusterSecretStore(vault-backend) 指向 Vault
# 2) 关占位 Secret，开 ExternalSecret：
helm upgrade platform deploy/helm/platform \
  --set secrets.create=false \
  --set externalSecrets.enabled=true \
  --set externalSecrets.vaultPath=secret/data/langchain4j-platform \
  --set externalSecrets.secretStoreRef.name=vault-backend
```

`templates/externalsecret-sample.yaml` 会为 `platform-secrets` / `edge-gateway-jwt` 渲染
`ExternalSecret` CRD，从 Vault 拉取真值填充**同名** Secret（服务 envFrom 引用不变）。
百炼凭据应存为 `<vaultPath>/bailian.api-key`，模板会同时映射
`RAG_EMBEDDING_API_KEY` 和 `RAG_RERANK_BAILIAN_API_KEY`。

## 数据库迁移阶段

`database-migrations` 镜像以 `pre-install,pre-upgrade` Hook Job 在业务 Deployment 前迁移 schema。
生产先由 IaC/DBA 建库和创建独立 migrator/app 用户；迁移密码只写入各
`platform-migration-secrets` 的 `*_MIGRATION_DB_PASSWORD` key，不会写入 `platform-secrets`，也不会
注入业务容器。默认迁移 auth、async-task、workflow、
order；Knowledge/channel 按持久化能力显式开启，`analytics-demo` 不用于生产库。

迁移 Job 是 pre-install Hook，所以不能依赖同一次 Helm release 才创建的普通 Secret 或
ExternalSecret。生产必须设置 `secrets.create=false`，并在 `helm install/upgrade` **之前**通过
ESO/IaC 预置 `platform-migration-secrets`。可复制 chart 外的样例并替换 Vault 路径：

```bash
kubectl apply -n platform \
  -f deploy/helm/platform-migration-externalsecret.example.yaml
kubectl wait -n platform --for=condition=Ready \
  externalsecret/platform-migration-secrets --timeout=120s
kubectl get -n platform secret platform-migration-secrets

helm upgrade --install platform deploy/helm/platform -n platform \
  --set secrets.create=false \
  --set externalSecrets.enabled=true
```

默认 `secrets.create=true` 只为本地模板演示创建含 `change-me` 的 Hook Secret；它不是生产凭据，
且 Hook 资源可能在卸载 release 后保留，应由本地环境自行清理或轮换。不要给业务 app 用户 DDL 权限。

```bash
mvn -pl database-migrations -am test
bash deploy/test-database-migration-config.sh
helm template platform deploy/helm/platform | grep 'name: schema-migration-'
```

迁移失败时 Hook 阻止 rollout。应用回滚保留兼容的扩展结构，不执行 down migration；完整的
expand-contract、已有库 baseline 和恢复流程见
[`docs/平台工程/database-migrations.md`](../../docs/平台工程/database-migrations.md)。

## 运行时安全、网络与高可用

每个 Deployment 使用自己的 ServiceAccount，不创建 RoleBinding，并同时在 ServiceAccount 和 Pod
关闭 token 自动挂载。Pod 固定非 root UID/GID 10001、`RuntimeDefault` seccomp；容器禁止提权、
根文件系统只读、删除全部 Linux capabilities，仅 `/tmp` 使用 256Mi `emptyDir`。Compose 的应用服务
使用等价的 numeric user、read-only、drop ALL、no-new-privileges 和受限 tmpfs。

`platform-default-deny` 默认拒绝所有业务 Pod ingress/egress；`platform-allow-required` 仅开放同
namespace、kube-dns、配置的 ingress namespace 和公网目标。公网规则排除 RFC1918、CGNAT、loopback、
link-local/metadata、multicast 和保留地址。若 MySQL/Redis/Kafka/Qdrant/LiteLLM 等 ExternalName
解析到私网，必须在环境 values 的 `networkPolicy.allowedEgressCidrs` 精确加入其网段；不要用
`0.0.0.0/0` 绕过。需要不同 ingress controller 时修改 `networkPolicy.ingressNamespaces`。

edge、AgentScope 和 async-task 默认 2 副本、HPA 最小 2，并用 PDB 保留至少 1 个可用副本；所有
workload 同时按 hostname/zone 尽力分散。AgentScope/async-task 的多副本安全依赖 AC-09 的唯一
owner、lease epoch 与 outbox claim fencing。静态验收运行：

```bash
bash deploy/test-runtime-hardening-config.sh
```

## 有状态语义与水平扩展

- **async-task-service**：默认 `replicaCount=2`、HPA min=2，且固定 JDBC store；任务租约与 outbox
  claim 都有 owner/TTL fencing。不得在多副本环境回退到 in-memory。
- **agentscope-orchestrator**：默认 `replicaCount=2`、HPA min=2，以 async-task-service 为任务权威；
  每进程唯一 worker owner + lease epoch 防止迟到写入。使用独立 AgentScope 镜像仓库。
- **agent-service**：只作回滚；若回滚后扩成多副本，仍须开启 Java 的 external authoritative
  模式避免重复领取。
- **workflow-service**：Flowable 在共享 MySQL 运行，多副本安全；表结构由发布前 migration Hook 管理。
- **eval-service**：回归测试客户端，非常驻。默认 `replicaCount=0`；按需
  `--set services.eval-service.replicaCount=1` 触发，或改造成 Job/CronJob。

edge、AgentScope、async-task 的 HPA/PDB 默认开启；其它服务仍按其持久化/迁移能力显式开启。

## edge-gateway 对外暴露

二选一：

```bash
# A) Ingress
--set ingress.enabled=true --set ingress.host=platform.example.com --set ingress.className=nginx
# B) LoadBalancer Service
--set services.edge-gateway.service.type=LoadBalancer
```
