# Progress

目标：完成长期记忆治理和知识运营闭环，排除Java升级/债务。
当前：M1 IN_PROGRESS；M2–M5、K1–K6 TODO。
基线：ba0b8c9；分支 feat/governed-memory-knowledge-operations。
设计：docs/design/memory-knowledge-operations/。
验证：尚未执行本次新增功能验证；不复用旧通过结论。
当前无必需业务信息阻塞；默认沿用已有身份及ingest权限。

M1 存储窄验证：13/0 fail/0 error，含3项新增治理用例；H2迁移已通过，真实MySQL留待M5。M1 IMPLEMENTED；下一片 M2 IN_PROGRESS。

M2 窄验证通过：26 tests，含候选不召回、异步跨清空及对话前代次捕获。M3 IN_PROGRESS。

M3 窄验证通过：27 tests，新增HTTP身份/冲突/缺版本用例。M4 UI/API已接入，待组件与类型验证。

M4 窄验证：新增3项组件测试+既有11项对话交互通过；vue-tsc通过。视觉截图尚未验证。M5 真实MySQL8.4通过17项存储/治理测试（含并发审核只有一个成功者）、三表注释及APP DDL拒绝。正在跑记忆批次全量回归。

M5全量回归：1448/0 fail/0 error/17 skip；Code Hygiene无阻塞，formatter/static analyzer未配置。浏览器验证限制：IAB不可用，Chrome创建本地预览超时；M4视觉标记UNVERIFIED，不影响独立K1后端实施。下一步K1；整体目标未完成。
