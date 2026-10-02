# Java 发布门禁新增依赖整改提案

## 后续技术债 (本轮已接受合并例外)

七类可靠性实现已完成本地验收。远程 supply-chain run 36978975220 在固定任务提交
1ac14a0 的聚合 SBOM 上报告 85 条 HIGH/CRITICAL (按扫描记录计数, 非85个独立包)。
完整 reactor 与打包通过, 安全扫描未通过; 不忽略漏洞或下调门禁, 用户2026-10-02明确接受版本扫描结果并要求合并main, 见MERGE_EXCEPTION。
原始 JSON 保存在本仓 .git/codex-cross-runtime-reliability-baseline/java-ci-summary/。
这是已有框架/传递依赖债务, 不能仅凭当前7项功能的单元测试证明升级安全。

## 后续可选技术路线 (本轮不执行)

继续使用Java21。新增迁移有两个实际取舍:

- 长期维护路线: Boot4.x / Cloud2025.1.x, 迁移成本更高, 需要评估Jackson/Servlet/API与所有SDK兼容。
- 较小兼容过渡: Boot3.5.16 / Cloud2025.0.3, 可减少当前3.3到4的迁移跨度;
  但官方当前Cloud矩阵已将2025.0.x标为EOL, 不能当作长期受支持方案。

前次异步选项的Boot3.5建议只适用于过渡, 官方维护状态核实后更正为上述两条路线。
推荐长期路线先做有界兼容设计, 不直接大批改POM。实际固定版本、扫描和兼容测试需通过才能发布,
不把候选版本当作已经清零或兼容通过。
[官方 Cloud兼容矩阵](https://spring.io/projects/spring-cloud/)、
[Boot3.5 Java兼容要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、
[现有Boot受影响的官方公告](https://spring.io/security/cve-2026-40973)。

除 BOM 外, 按扫描结果处理 LangChain4j pgvector、固定gRPC、OpenNLP、LZ4与密码学依赖;
优先已有管理机制和最小受支持修复版本, 不逐个覆盖Spring子模块制造未支持组合。
Pgvector公告见 [供应方安全公告](https://github.com/advisories/GHSA-2mfg-cc43-9pcj)。
LangChain4j升级必须重新验证MCP beta接口、Qdrant/Milvus启动兼容和流式取消句柄;
gRPC升级必须做真实向量组件兼容验证, 不仅编译。

## 实施和验证顺序

1. 固定新BOM/客户端组合, 解析依赖树、许可证与新旧API/config差异; 保留原库版本与回退点。
2. 处理Gateway新配置前缀与可信代理、Security、Actuator、ConfigServer兼容。
3. 迁移SDK/向量/文本解析客户端变化; 复验跨Java/Python固定契约与预算/worker/JWT链路。
4. 全reactor、Flowable/MySQL、Redis、worker/SSE跨进程、静态Compose/Helm和Code Hygiene复验。
5. 聚合SBOM、18镜像扫描全部通过后, producer→consumer正常合并和推送main。

不修改业务数据库、生产部署与凭据。运行时镜像扫描尚未执行, 若暴露系统包问题应单独记录。

## 受影响依赖清单

| 包 | 扫描版本 | 记录数 | 扫描修复版本集合 |
|---|---|---|---|
| com.fasterxml.jackson.core:jackson-core | 2.17.2 | 3 | 2.18.11, 2.21.7, 2.22.3; 2.18.8, 2.21.4; 2.21.7, 2.22.3, 2.18.11 |
| com.fasterxml.jackson.core:jackson-databind | 2.17.2 | 5 | 2.18.10, 2.21.6, 2.22.2; 2.18.11, 2.21.7, 2.22.3; 2.18.8, 2.21.4, 3.1.4; 2.18.8, 3.1.4, 2.21.4; 2.21.7, 2.18.11, 2.22.3 |
| dev.langchain4j:langchain4j-pgvector | 1.13.1-beta23 | 1 | 1.2.1-beta8, 1.5.1-beta11, 1.11.8-beta19, 1.16.3-beta26 |
| io.grpc:grpc-netty-shaded | 1.59.1 | 1 | 1.75.0 |
| io.micrometer:micrometer-core | 1.13.6 | 1 | 1.16.6, 1.15.12 |
| io.netty:netty-codec | 4.1.114.Final | 2 | 4.1.133.Final; 4.1.136.Final |
| io.netty:netty-codec-dns | 4.1.114.Final | 1 | 4.2.13.Final, 4.1.133.Final |
| io.netty:netty-codec-http | 4.1.114.Final | 6 | 4.1.132.Final, 4.2.10.Final; 4.2.13.Final, 4.1.133.Final; 4.2.16.Final, 4.1.136.Final |
| io.netty:netty-codec-http2 | 4.1.114.Final | 4 | 4.1.132.Final, 4.2.11.Final; 4.2.13.Final, 4.1.133.Final; 4.2.16.Final, 4.1.136.Final; 4.2.4.Final, 4.1.124.Final |
| io.netty:netty-handler | 4.1.114.Final | 5 | 4.1.118.Final; 4.2.15.Final, 4.1.135.Final; 4.2.17.Final, 4.1.137.Final |
| io.netty:netty-resolver-dns | 4.1.114.Final | 2 | 4.2.15.Final, 4.1.135.Final |
| org.apache.httpcomponents.core5:httpcore5 | 5.2.5 | 1 | 5.4.3, 5.5-beta2 |
| org.apache.httpcomponents.core5:httpcore5-h2 | 5.2.5 | 1 | 5.4.3, 5.5-beta2 |
| org.apache.kafka:kafka-clients | 3.7.1 | 1 | 3.9.2, 4.0.2, 4.1.2 |
| org.apache.opennlp:opennlp-tools | 2.5.4 | 3 | 2.5.9, 3.0.0-M3; 2.5.9, 3.0.0-M3, 1.9.5 |
| org.apache.tomcat.embed:tomcat-embed-core | 10.1.31 | 21 | 11.0.10, 10.1.44, 9.0.108; 11.0.11, 10.1.45, 9.0.109; 11.0.18, 10.1.52, 9.0.115; 11.0.2, 10.1.34, 9.0.98; 11.0.25, 10.1.58, 9.0.121; 11.0.3, 10.1.35, 9.0.99; 11.0.8, 10.1.42, 9.0.106; 11.0.9, 10.1.43, 9.0.107; 9.0.107, 10.1.43, 11.0.9; 9.0.116, 10.1.52, 11.0.20; 9.0.116, 10.1.54, 11.0.21; 9.0.117, 10.1.54, 11.0.21; 9.0.118, 10.1.55, 11.0.22 |
| org.bouncycastle:bcprov-jdk18on | 1.78 | 3 | 1.80.2, 1.81.1, 1.84; 1.85 |
| org.bouncycastle:bcprov-jdk18on | 1.81 | 3 | 1.80.2, 1.81.1, 1.84; 1.85 |
| org.lz4:lz4-java | 1.8.0 | 1 | 1.8.1 |
| org.postgresql:postgresql | 42.7.4 | 3 | 42.7.11; 42.7.12; 42.7.7 |
| org.springframework.boot:spring-boot | 3.3.5 | 2 | 3.3.11, 3.4.5; 4.0.6, 3.5.14 |
| org.springframework.boot:spring-boot-starter-actuator | 3.3.5 | 1 | 4.0.4, 3.5.12 |
| org.springframework.cloud:spring-cloud-config-server | 4.1.3 | 4 | 4.3.2, 5.0.2; 4.3.3, 5.0.3 |
| org.springframework.cloud:spring-cloud-gateway-server | 4.1.5 | 2 | 4.2.3, 4.1.8, 3.1.10; 4.3.2, 4.2.6 |
| org.springframework.data:spring-data-commons | 3.3.5 | 1 | 4.0.6, 3.5.12 |
| org.springframework.kafka:spring-kafka | 3.2.4 | 1 | 4.0.6, 3.3.16 |
| org.springframework.security:spring-security-crypto | 6.3.4 | 1 | 6.3.8, 6.4.4, 6.2.10, 6.1.14, 6.0.16, 5.8.18, 5.7.16 |
| org.springframework:spring-core | 6.1.14 | 1 | 6.2.11 |
| org.springframework:spring-expression | 6.1.14 | 1 | 7.0.8, 6.2.19 |
| org.springframework:spring-webflux | 6.1.14 | 1 | 7.0.8, 6.2.19 |
| org.springframework:spring-webmvc | 6.1.14 | 2 | 7.0.8, 6.2.19 |
