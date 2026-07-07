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
│       └── _hpa.tpl                 # 可复用 HPA 模板
└── templates/
    ├── workloads.yaml               # 遍历 values.services，渲染 Deployment/Service/HPA
    ├── external-services.yaml       # 外部基础设施 ExternalName Service
    ├── configmap.yaml               # 非敏感 base-url/flag → platform-config
    ├── secret.yaml                  # 敏感项占位 Secret（platform-secrets / edge-gateway-jwt / edge-gateway-apikeys）
    ├── externalsecret-sample.yaml   # External Secrets Operator 样例 CRD（默认关）
    └── ingress.yaml                 # edge-gateway 对外 Ingress（默认关）
```

## 快速开始

```bash
# 校验
helm lint deploy/helm/platform
helm template platform deploy/helm/platform            # 离线渲染全部资源，无需联网

# 安装（先备好命名空间与镜像仓库/外部基础设施）
helm install platform deploy/helm/platform -n platform --create-namespace \
  --set global.image.registry=<your-registry> \
  --set global.image.tag=<git-sha>
```

> 库 chart `platform-lib` 已 vendored 在 `charts/` 下，`helm template`/`lint` 无需 `helm dependency build`（离线可用）。

## values 结构

| 顶层键 | 作用 |
| --- | --- |
| `global.image` | 镜像仓库前缀 / tag / 拉取策略。单服务镜像 = `<registry>/<服务名>:<tag>`，可在 `services.<svc>.image` 覆盖。 |
| `global.envFrom` | 所有服务默认注入的 ConfigMap/Secret（`platform-config` + `platform-secrets`）。 |
| `global.probes` | 存活/就绪探针，复用 actuator health group（liveness/readiness 路径与阈值）。 |
| `global.resources` | 默认 requests/limits，可被 `services.<svc>.resources` 覆盖。 |
| `config.*` | **非敏感** base-url / feature flag → ConfigMap `platform-config`。 |
| `secrets.*` | **敏感项**占位值 → Secret；生产用 ESO 覆盖（见下）。 |
| `externalSecrets.*` | External Secrets Operator 对接开关与 Vault 路径。 |
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
| `agent-service` | 8085 | interop → `AGENT_BASE_URL` |
| `async-task-service` | 8086 | workflow/agent → `ASYNC_TASK_BASE_URL` |
| `channel-service` | 8087 | edge-gateway 路由 |
| `interop-service` | 8088 | edge-gateway 路由 |
| `eval-service` | 8089 | edge-gateway 路由（回归客户端） |
| `vision-service` | 8090 | agent → `VISION_BASE_URL` |

外部基础设施（`external-services.yaml` 渲染 ExternalName Service，同名指向外部实例）：

| Service / compose 名 | 客户端 env 里的引用 | ExternalName 目标（`externalInfra.<x>.externalName`） |
| --- | --- | --- |
| `mysql` | `jdbc:mysql://mysql:3306/...` | `mysql.external.example.com` |
| `redis` | `redis:6379` | `redis.external.example.com` |
| `kafka` | `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` | `kafka.external.example.com` |
| `qdrant` | `QDRANT_HOST=qdrant` `QDRANT_PORT=6334` | `qdrant.external.example.com` |
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

- `platform-config` 携带 `PLATFORM_SECURITY_JWT_ALGORITHM=RS256`（非敏感）。
- `platform-secrets` 携带 `PLATFORM_SECURITY_JWT_PUBLIC_KEY`（验签公钥）→ envFrom 注入**所有**服务，下游只验签。
- `edge-gateway-jwt` 携带 `PLATFORM_SECURITY_JWT_PRIVATE_KEY`（签发私钥）→ **仅 edge-gateway** envFrom。
  下游 Deployment 渲染结果不含私钥，验证：
  ```bash
  helm template platform deploy/helm/platform -s templates/workloads.yaml | grep -c PRIVATE_KEY   # 私钥只出现在 gateway 引用处
  ```

生成一对密钥（示例）：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```

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

## 有状态语义与水平扩展

- **async-task-service**：多副本必须 `--set config.ASYNC_TASK_STORE=jdbc`（持久化到 MySQL），
  否则内存态各副本分裂（任务/租约/webhook 状态不共享）。默认 `replicaCount=1` + in-memory。
- **agent-service**：多副本必须 `--set config.AGENT_ASYNC_EXTERNAL_ENABLED=true`
  `--set config.AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=true`，让 async-task-service 成为异步任务权威，
  避免各副本重复领取。默认单副本。
- **workflow-service**：Flowable 在共享 MySQL 自管表，多副本安全。
- **eval-service**：回归测试客户端，非常驻。默认 `replicaCount=0`；按需
  `--set services.eval-service.replicaCount=1` 触发，或改造成 Job/CronJob。

HPA 默认全部关闭；开启示例：`--set services.knowledge-service.hpa.enabled=true`。

## edge-gateway 对外暴露

二选一：

```bash
# A) Ingress
--set ingress.enabled=true --set ingress.host=platform.example.com --set ingress.className=nginx
# B) LoadBalancer Service
--set services.edge-gateway.service.type=LoadBalancer
```
