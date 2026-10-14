# M1-PERSIST Progress

- 更新日期：2026-10-14。
- 目标：用户长期画像内存存储改为 MySQL 持久化。
- 实施：DONE；存储治理和知识运营不在本切片。
- 功能提交：d8ce9d0；原交付记录：6d201d2。
- Git 交付：已通过 344245f 合入并推送远程 main。
- 用户获知原供应链扫描失败后明确要求本次提交、推送 main 并切回 main；按本次源码合并授权执行，未修改扫描配置或忽略规则。
- 集成验证：1441 tests / 0 failures / 0 errors / 17 skipped；五项部署配置门禁通过。
- 既有真实 MySQL 验证、接口/隔离测试详见 TEST_RESULT.md；功能实现未发生变化。
- 历史远程兼容性 CI 38491384085 SUCCESS；供应链 CI 38491391893 的 SBOM 扫描失败结论保持不变，不宣称最新 main CI 全绿。
- 当前统一交付记录：../pending-reliability-delivery/DELIVERY_RESULT.md。
- 原目录既有整改已按本次授权分组提交，不再属于未提交用户内容。
- 持久化任务工作树仍保留已合并分支及测试日志，未清理；MySQL 本轮未启动，Python 仓未修改。
- 后续：关注 main CI；依赖债务和完整治理另行处理。本轮不部署生产。
