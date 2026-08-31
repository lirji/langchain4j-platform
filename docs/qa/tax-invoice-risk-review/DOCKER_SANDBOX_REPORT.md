# Docker 沙箱验证报告

## 目标

证明财税模块测试可在 Coding Agent 现有强隔离执行器中完成，且不会退回宿主机执行或隐式联网。

## 隔离合同

`runSandboxCommand` 生成的容器包含以下约束：

- `--pull=never`、`--network none`。
- `--read-only`，源码以 `/source:ro` 挂载。
- `--cap-drop ALL`、`no-new-privileges`。
- `--user 65532:65532`，不挂载 Docker socket。
- 工作目录和临时目录使用受限 `tmpfs`。
- Maven 使用只读本地仓库并强制离线 `-o`。
- 本次资源上限：3,072 MiB、2 CPU、480 秒超时。

## 沙箱合同自测

```bash
bash tools/coding-agent-eval/test/sandbox-smoke.sh
```

结果：通过。验证了源码不可写、UID 65532、无 Docker socket、超时终止和 Docker 隔离标识。

## 财税模块实测

容器内实际命令：

```bash
mvn -pl tax-service -am test
```

最终结果：

- `isolation=docker`。
- `exitCode=0`、`timedOut=false`。
- 墙钟耗时 28,183 ms；Maven 测试耗时 16.981 s。
- 上游共享模块与 `tax-service` 全部 `SUCCESS`。
- 财税测试 21/21，0 failure、0 error、0 skipped。

## 前置条件与限制

宿主机必须已缓存 `eclipse-temurin:21-jdk`、Maven 可执行文件和所需依赖；缺失时按设计 fail-closed，不拉镜像、不联网，也不回退宿主机。该验证不等同于容器镜像漏洞扫描，镜像扫描由供应链 Workflow 负责。
