# RAG 运营页面视觉改造

## 范围与结果

用户要求重做知识运营闭环和检索质量评测两块页面。复用现有工作台组件和主题 token；没有新增依赖、接口、业务数据或全局样式。CSS 以 `.rag-governance` 限定作用域。

反馈表单与管理入口在桌面分栏，窄屏堆叠。评测入口提供三步流程说明；编辑表单分组并使用样本卡片；运行结果使用六项指标卡和可横向滚动表格。按钮主次、空态、错误/成功/等待提示跟随项目主题。业务脚本仅增加展示组件与 CSS 导入。

## 验证

| 检查 | 结果 |
| --- | --- |
| `npm --prefix capability-showcase-frontend test -- src/modules/rag` | PASS：5 文件、27 项 |
| `npm --prefix capability-showcase-frontend run type-check` | PASS |
| 全量前端回归 `npm test -- --run` | PASS：72 文件、580 项 |
| 前端 Docker 构建（含 Vite 生产构建） | PASS |
| `git diff --check` | PASS |
| Chrome 桌面实际页面、打开评测集编辑、取消并放弃未保存编辑 | PASS |
| Chrome Responsive 390×844 反馈字段、管理入口、评测操作及流程排列 | PASS |

测试覆盖既有权限、租户切换、版本冲突、发布和评测交互。UI 检查未提交业务数据；浏览器检查后关闭设备模拟与 DevTools。截图保留在本机 `deploy/.dev-infra-state/rag-design-release/evidence/`，未提交含浏览器个人界面的图片。未人工遍历全部发布/运行状态，相关行为由聚焦测试覆盖；深色主题复用变量，未做人工截图验收。

## 交付状态

任务分支 `fix/rag-operations-design`，基线 `aa03cf4`。源码提交 `4362e16f1c5efde401a9ac8b734ef903ada674a6` 已正常快进合入并推送 `origin/main`。

本地运行镜像 `langchain4j-platform-capability-showcase-frontend:4362e16`，镜像 ID `sha256:3a3abdc3e42f2e5e7fadc07dca262e84be5ddb9d3439caf1363d1b79ca5d56c0`，OCI revision 与源码提交一致。容器重新创建成功，`http://localhost:8093/m/rag` 返回 200；Chrome 刷新后确认新界面。固定镜像包装配置已同步，重启不会覆盖为旧版。

远程前端 CI：[38513364785](https://github.com/lirji/langchain4j-platform/actions/runs/38513364785)，SUCCESS（全量测试、类型检查、生产构建均成功）。通用 Java 供应链运行 38513364771 仍在执行，不作为本次 UI 行为验证证据，也不声明全仓发布门禁通过。

未新增 worktree；任务改动均已纳入提交。旧工作树未删除；`node_modules/`、`dist/` 可重建但保留供本地开发，`deploy/.dev-infra-state/` 含凭据、固定镜像配置及验证证据，需保留。

仅更新本地前端容器，后端服务和数据库不变。原前端镜像 `cfa1d5b` 保留可回退；本任务不声明生产发布就绪。既有工作树、忽略凭据及运行证据保留，不清理用户内容。
