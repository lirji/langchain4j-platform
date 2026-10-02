# 跨运行时可靠性QA

验证任务提交独立于原有dirty工作树：两仓已有detached验证worktree，Python强制
`PYTHONPATH=<clean>/src`以避免共享editable venv误导入原工作树。
所有真实组件试验只用本轮UUID临时数据库/Redis key，finally清理；不故障注入共享实例。

| 验收 | 可复现命令/结果 |
|---|---|
| Java全量 | clean worktree `mvn -o -B test`，全reactor 1406/0fail/0error/13skip BUILD SUCCESS |
| Python全量 | clean PYTHONPATH + pytest --cov --cov-fail-under=80：518 passed，89.27%；Ruff/format/mypy97源文件PASS |
| S1租户边界 | 旧代码新增缺失/null/空tenant用例3fail；修复后匹配/不同/缺失均按授权验收 |
| S2真实持久化 | `python3 deploy/test-channel-inbox-mysql.py`：MySQL8.4临时库10项PASS，含崩溃后重领和旧epoch写回 |
| S3真实原子预算 | Redis7 UUID namespace4项PASS；假OpenAI HTTP/SSE验证5个工厂出口、一次完整usage记账；专用RPC JWT认证/参数绑定测试 |
| S5真实退款 | `python3 deploy/test-workflow-receipt-mysql.py`：真实Flowable/MySQL lost-response回执PASS；`-Pflowable-it` H2/JDBC原子性15项PASS |
| S6分派和进程 | `test-readonly-dispatch-mysql.py`5项PASS；Python `test_durable_worker_process.py`真API退出、worker崩溃、第二进程恢复RUN/DAG、旧epoch409 PASS |
| S7流式隔离 | `test_conversation_stream_process.py --java-repo <repo>`：真Java JWT→独立Uvicorn→假OpenAI3项PASS；提供方已打开流后取消并观察TCP关闭 |
| S4不可变组合 | 46producer/25consumer，固定6f43ddf；export/check、Java DTO14项、临时Git故障4项PASS；真实缺上游exit1 |
| Runtime静态 | 本地Bailian、cutover、hardening、migration、supply-chain、observability gates PASS；Compose默认/worker/candidate overlays与Helm lint PASS |
| Quality | 两仓Code Hygiene：无blocking；Java无formatter/analyzer配置按工具限制SKIPPED，Python提供实际Ruff/mypy/format执行证据 |

Code Hygiene依赖允许项限于本计划复用的已有platform-metering/gateway-client/database-migrations、
根版本管理的spring-web，以及仅测试所需H2/Jackson时间模块；没有新运行中间件；S8为闭合既有安全审计仅更新三个已存在依赖，详见下述记录。

本地原始日志：`/tmp/final-clean-{java,python}.log`、`/tmp/final-{flowable-it,redis-budget,worker-process,stream-process}.log`；
长留原始集成日志在两仓`.git/codex-cross-runtime-reliability-baseline/`，不提交凭据和本地日志。
未执行真实模型、镜像生产发布、目标集群部署、benchmark模型执行或sandbox-smoke。
远程CI与本地PASS分开记录，见DELIVERY_REPORT。

## S8 CI基线阻塞修复

安全审计34条→0：仅cryptography49→50.0.2、PyJWT2.13→2.15.1、urllib3 2.7→2.8.0，
其Python最低版本均低于项目3.12，许可证保持Apache/BSD或MIT；签名/验签与干净全量518项重新通过。
官方修复依据：[cryptography公告](https://github.com/pyca/cryptography/security/advisories/GHSA-g6cj-pr64-35w5)、
[PyJWT changelog](https://pyjwt.readthedocs.io/en/stable/changelog.html)、[urllib3 release](https://github.com/urllib3/urllib3/releases/tag/2.8.0)。
IAM SDK SNAPSHOT尚未发布公共Maven：CI从公开auth-platform 929e9ca固定Git树构建SDK/protocol，
不读取dirty供应方文件；源码测试与knowledge consumer兼容验证通过，不修改第三仓。
原始日志final-auth-sdk-source/final-native-auth-sdk-compat/final-dependency-audit。

原Python工作树还包含用户未提交的新增非规范JWT重放测试，预期变体能先被decode；
更新PyJWT会在decode即拒绝，原工作树524pass/1fail。保留该用户测试，不以它替代本任务干净回归证据，
也未为兼容旧预期放宽验证；所有初始dirty贡献重建校验无偏差。

远程扫描另外发现Python基础镜像libpcre2-8-0的3条HIGH，runtime构建仅升级该已存在系统包，
并校验至少10.42-1+deb12u1；无ignore列表或降低severity。
[Debian修复版本](https://security-tracker.debian.org/tracker/CVE-2026-86145)。
Java runner补装已有静态门禁所需ripgrep；CI供应方源码放.ci-sources二级目录，
避免其Dockerfile污染本仓18镜像清单。

远程Java cutover在1ac14a0 Validate Compose失败, 与本地PASS分开; 行号诊断53ee6f5定位Linux管道SIGPIPE, deba7bc改为完整消费输入;
复现实验早退141→完整消费0, clean cutover PASS, 远程重跑36980014838 SUCCESS, 包含全reactor/Compose/安全配置/Helm。
JavaSBOM固定提交扫描85条HIGH/CRITICAL, 31依赖坐标; 全reactor通过不代表安全门禁通过。
Python34df909远程quality SUCCESS, 包含修复后的镜像扫描。详见DELIVERY_REPORT。
