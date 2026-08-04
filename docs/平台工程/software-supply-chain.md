# 软件供应链与可信发布

本文定义 Java 平台的依赖审计、SBOM、镜像扫描、OIDC 签名、来源证明和回滚规则。唯一可信
发布入口是 `.github/workflows/supply-chain.yml`。部署清单必须引用已验证的镜像 digest，不能
把版本 tag 或 `sha-*` tag 本身当作信任依据。

## 门禁覆盖

- Pull Request、`main` push 和手工运行只有 `contents: read` 权限，先执行完整 Maven Reactor
  测试，再打包所有 JAR，生成包含生产依赖的 CycloneDX 1.6 aggregate SBOM，并用 Trivy 对
  `HIGH,CRITICAL` 漏洞做阻断扫描。
- `image-scan` 矩阵从仓库实际 Dockerfile 派生并由静态测试核对，当前覆盖 17 个可部署镜像：
  Java 服务、数据库迁移器和能力展示前端。任何镜像扫描失败都会阻止发布。
- 只有 `v*` tag 的 release jobs 拥有 GHCR、OIDC 和 attestation 写权限。每个实际推送 digest
  会再次扫描，生成独立 CycloneDX SBOM，再通过 Cosign GitHub OIDC 签名并绑定 SLSA
  provenance 与 SBOM attestation；JAR 及 aggregate SBOM 也绑定来源证明。
- 所有第三方 Action 固定到完整 commit SHA。Dependabot 只负责提出升级 PR；合并前必须核对
  上游 release、commit 和 security advisory，尤其要复核 Trivy Action 的历史 tag 供应链事件。
  禁止改回 `@vN`、分支或其他可移动引用。
- 工作流不保存长期签名私钥，也不读取生产运行时凭据。签名只使用 GitHub OIDC 短时身份，
  工作流不会自动部署发布物。

registry digest 只有推送后才能确定，所以 digest 二次扫描失败时 GHCR 可能留下一份未签名、
未证明的孤立镜像。该 digest 必须被视为失败制品并加入 denylist；生产 admission 必须同时校验
签名和 provenance，不能只验证仓库中存在镜像。

## 本地门禁

```bash
bash deploy/test-supply-chain-config.sh
mvn -B -DskipITs test
mvn -B -Dmaven.test.skip=true package
mvn -B org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom \
  -DschemaVersion=1.6 \
  -DincludeTestScope=false \
  -DoutputFormat=json \
  -DoutputName=langchain4j-platform.cdx
```

静态脚本会拒绝浮动 Action、`pull_request_target`、提前授予的 OIDC/write 权限、缺失扫描/
签名/证明步骤，以及与 17 个 Dockerfile 不一致的镜像矩阵。实际容器构建与扫描需要 Docker
daemon；签名和 attestation 需要 GitHub tag、OIDC 与 GHCR，不能由本地结果替代。

## 发布后验证

AgentScope 镜像使用其仓库名；Java 服务镜像命名为
`ghcr.io/OWNER/langchain4j-platform-SERVICE`。从 workflow run 或 registry 取得实际 digest 后执行：

```bash
REPO=OWNER/langchain4j-platform
SERVICE=agent-service
IMAGE=ghcr.io/OWNER/langchain4j-platform-${SERVICE}@sha256:0123456789abcdef

cosign verify "$IMAGE" \
  --certificate-identity-regexp \
  "^https://github.com/${REPO}/.github/workflows/supply-chain\\.yml@refs/tags/v.+$" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

gh attestation verify "oci://${IMAGE}" \
  --repo "$REPO" \
  --signer-workflow "$REPO/.github/workflows/supply-chain.yml"
```

验证必须绑定预期仓库、`supply-chain.yml`、`refs/tags/v*` 与确切 digest。还要下载对应
`release-evidence-*`、`java-supply-chain-*` 和 `image-scan-*` 证据，确认 aggregate/image
CycloneDX JSON 可解析、扫描无阻断漏洞，并记录 workflow URL、run ID、source commit、tag、
digest、签名与 attestation 输出。

命令语义以 [Sigstore Cosign verification](https://docs.sigstore.dev/cosign/verifying/verify/)
和 [GitHub CLI attestation verification](https://cli.github.com/manual/gh_attestation_verify)
为准。

## 失败处置与回滚

1. Reactor、SBOM、依赖或预发布镜像扫描失败：禁止发布，修复后重新走 PR 和全量测试。
2. 已推送 digest 的二次扫描失败：保留报告、拒绝该 digest，不签名、不证明、不部署。
3. OIDC、Cosign 或 attestation 失败：制品仍不可信，禁止手工豁免；恢复外部服务后从同一
   source tag 重新构建，不能为来源不明的旧镜像补签。
4. 线上回滚：选择前一已验证 digest，重新验证签名和 provenance，再按服务排空/回滚手册部署。
   不得使用 mutable tag，不删除签名/证明，不赋予发布 job 生产集群凭据。
5. Action 或构建器安全事件：冻结发布、在 admission/registry 策略中拒绝受影响 digest、保全
   workflow/SBOM/扫描证据，升级至已核验 immutable commit 后从已知良好源码全量重建 17 个镜像。

## 目标环境证据

生产闭环仍必须提供：真实 `v*` GitHub Actions run、17 个 GHCR digest 的扫描/签名/来源证明、
JAR provenance、registry/admission 拒绝未签名 digest 的测试，以及至少一次回滚到前一可信
digest 的演练。缺少这些外部证据时，本地测试通过不等于可以生产放行。
