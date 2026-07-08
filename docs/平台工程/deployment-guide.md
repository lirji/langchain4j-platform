# 部署指南

平台有两种部署形态：**本地 docker-compose 一体化栈**（开发/演示）和 **k8s / Helm 伞状 chart**（生产）。二者共享同一套服务命名与跨服务 base-url——k8s Service 名 == compose 服务名 == 各服务 env 里硬编码的主机名，因此从 compose 迁到 k8s 近零改动。

前置：JDK 21、Maven、Docker/Docker Compose、可用的 LiteLLM（可再接 Ollama/OpenAI/Anthropic）。默认所有增强能力（Kafka 事件总线、RS256、GraphRAG、语义缓存、agent 高级动作等）**关闭**、走内存/确定性实现，零外部依赖。

---

## 一、本地 docker-compose

```bash
# 1. 构建产物
mvn -DskipTests package

# 2. 起整套栈（LiteLLM + Redis + MySQL + Kafka + Qdrant + 各业务服务）
docker compose -f deploy/docker-compose.yml up --build

# 3. 打一条 /chat（走 edge-gateway，用 api-key，网关内部换内部 JWT 转发）
curl -s -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}'
```

`deploy/` 下的辅助编排与冒烟脚本：

| 文件 | 用途 |
|---|---|
| `deploy/docker-compose.yml` | 完整本地栈 |
| `deploy/docker-compose.failover.yml` + `deploy/litellm/config.failover.yaml` | LiteLLM 双上游 + fallback 故障转移冒烟（配 `deploy/smoke-failover.sh`） |
| `deploy/docker-compose.oracle.yml` | 起冻结单体作 oracle，供 eval 双跑回归门禁（D4）真实比对 |
| `deploy/smoke-nl2sql.sh` | NL2SQL / ChatBI 冒烟 |
| `deploy/smoke-qdrant-rag.sh` | Qdrant 向量库 RAG 冒烟 |
| `deploy/smoke-failover.sh` | 干掉一个 LiteLLM 上游、验证 fallback |

compose 编排的各服务端口：edge-gateway `8080`、config-server `8888`、conversation `8081`、workflow `8082`、analytics `8083`、knowledge `8084`、agent `8085`、async-task `8086`、channel `8087`、interop `8088`、eval `8089`、vision `8090`、voice `8091`。

> **voice-service（:8091）** 已纳入 `docker-compose.yml`（`build` 上下文 `../voice-service`，其 Dockerfile 已随模块提供）与 Helm 伞状 chart。默认关闭（`VOICE_ENABLED=false` → voice 相关 Bean 全不装配、零依赖零网络），此时容器空跑仅占端口。启用时按 OpenAI 兼容 ASR/TTS 协议配置：`VOICE_ENABLED=true`、`VOICE_PROVIDER=openai`、`VOICE_BASE_URL=https://api.openai.com/v1`（可指云 OpenAI / Azure / 本地 whisper+tts 网关）、`VOICE_API_KEY=<key>`（生产走 Secret/ESO，不入 ConfigMap）。它的「大脑」是 conversation-service，容器/集群内已默认注入 `VOICE_CONVERSATION_BASE_URL=http://conversation-service:8081`。完整语音开关（`VOICE_ASR_MODEL`/`VOICE_TTS_MODEL`/`VOICE_TTS_VOICE` 等）见 [operations.md](../参考/operations.md)。

全部启用开关见 [operations.md](../参考/operations.md)。

---

## 二、k8s / Helm 伞状 chart

完整清单在 `deploy/helm/platform/`，深度参数说明见 **[deploy/helm/README.md](../../deploy/helm/README.md)**。这里给关键点。

### 结构
伞状 chart + vendored 库 chart `platform-lib`（`type: library`，`charts/` 下已 vendored，`helm lint`/`template` 无需联网 `helm dependency build`）。`templates/workloads.yaml` 遍历 `values.services` 渲染 13 个业务服务（edge-gateway、config-server、conversation、workflow、analytics、knowledge、agent、async-task、channel、interop、eval、vision、voice）的 Deployment/Service/(可选)HPA；`external-services.yaml` 为 MySQL/Redis/Kafka/Qdrant/LiteLLM 渲染 **ExternalName** Service（不自建 StatefulSet）。

### 校验与安装
```bash
helm lint deploy/helm/platform
helm template platform deploy/helm/platform            # 离线渲染全部资源

helm install platform deploy/helm/platform -n platform --create-namespace \
  --set global.image.registry=<your-registry> \
  --set global.image.tag=<git-sha>
```

### Service DNS（核心不变量）
**k8s Service 名 == compose 服务名 == 各服务 env 硬编码主机名**，所以 `KNOWLEDGE_BASE_URL=http://knowledge-service:8084`、`jdbc:mysql://mysql:3306/...`、`KAFKA_BOOTSTRAP_SERVERS=kafka:9092` 等在集群内直接解析，无需改代码。外部托管实例用同名 ExternalName CNAME 过去：
```bash
--set externalInfra.mysql.externalName=my-rds.xxx.rds.amazonaws.com \
--set externalInfra.kafka.externalName=b-1.msk.amazonaws.com \
--set externalInfra.litellm.externalName=litellm.internal.corp
```
仅有裸 IP（ExternalName 不接受 IP）时：`--set externalInfra.litellm.enabled=false --set config.GATEWAY_BASE_URL=http://10.0.0.9:4000/v1`。

### 密钥与 RS256 内部 JWT
- 非敏感 base-url/flag → ConfigMap `platform-config`；敏感项 → Secret。
- **RS256 私钥隔离**（缩小轮转爆炸半径）：`PLATFORM_SECURITY_JWT_PUBLIC_KEY`（验签公钥）经 `platform-secrets` 注入**所有**服务；`PLATFORM_SECURITY_JWT_PRIVATE_KEY`（签发私钥）仅 `edge-gateway-jwt` Secret、**只挂 edge-gateway**。验证私钥不渗漏到下游：
  ```bash
  helm template platform deploy/helm/platform -s templates/workloads.yaml | grep -c PRIVATE_KEY
  ```
  生成 keypair：`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem && openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem`。
- **External Secrets**（生产）：默认 `secrets.create=true` 渲染**占位** Secret（`change-me`，勿用于生产）。迁 ESO + Vault：
  ```bash
  helm upgrade platform deploy/helm/platform \
    --set secrets.create=false --set externalSecrets.enabled=true \
    --set externalSecrets.vaultPath=secret/data/langchain4j-platform \
    --set externalSecrets.secretStoreRef.name=vault-backend
  ```

### 有状态语义与水平扩展（重要）
默认各服务单副本 + 内存态。多副本前必须切持久化，否则副本分裂丢状态：
- **async-task**：多副本 → `--set config.ASYNC_TASK_STORE=jdbc`（任务/租约/webhook 落 MySQL）。
- **agent**：多副本 → `--set config.AGENT_ASYNC_EXTERNAL_ENABLED=true --set config.AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=true`（async-task 成异步任务权威，避免各副本重复领取）。
- **workflow**：Flowable 共享 MySQL 自管表，多副本安全。
- **eval**：回归客户端非常驻，默认 `replicaCount=0`，按需触发或改 Job/CronJob。
- HPA 默认全关：`--set services.knowledge-service.hpa.enabled=true`。

### 对外暴露
`--set ingress.enabled=true --set ingress.host=platform.example.com` 或 `--set services.edge-gateway.service.type=LoadBalancer`。

---

## 三、配置中心（Spring Cloud Config Server）

`config-server` 作为集群内服务部署（native profile，读打包进 jar 的 `config/`）。各服务经 `CONFIG_SERVER_URI=http://config-server:8888`（在 `platform-config` 里）+ 各自 `application.yml` 的 `spring.config.import=optional:configserver:...` 接入。

**关键：optional import**——config-server 不可达也**不阻断启动**（`optional:` + 各服务 `${ENV:default}` 兜底）。故 dev/test 无需起 config-server。切 git 后端：`SPRING_PROFILES_ACTIVE=git` + `SPRING_CLOUD_CONFIG_SERVER_GIT_URI`。

---

## 相关文档
- 全部环境变量与启用开关：[operations.md](../参考/operations.md)
- 事件总线 / Kafka 可靠投递的生产启用：[eventbus-guide.md](eventbus-guide.md)
- Helm 参数逐项说明：[deploy/helm/README.md](../../deploy/helm/README.md)
