# 多模态访问连通性 QA 计划

## 范围与授权

- 目标：验证本地 `http://127.0.0.1:8093` 的视觉、语音与图片跨模态检索。
- 环境：localhost Compose；Chrome 现有 Casdoor 会话 `alice / acme`。
- 执行授权：用户要求测试并修复多模态问题，随后批准新增百炼原生 adapter。
- 安全：凭据只从本地 CSV/环境加载，不打印、不提交；真实调用控制在最小样本。

## 用例

| ID | 用例 | 预期 |
| --- | --- | --- |
| MM-01 | 多模态控制台与登录态 | 页面加载，身份为 acme |
| MM-02 | edge/vision/voice/knowledge 探活 | 均可达 |
| MM-03 | `/vision/caption` | 200 且有 caption/model |
| MM-04 | `/chat/vision` | 200 且有图片理解回复 |
| MM-05 | `/voice/transcribe` | 200 且有非空转写 |
| MM-06 | `/voice/chat` | 200 且有回复与非空音频 |
| MM-07 | `/rag/image` | 200 且返回图片 id |
| MM-08 | `/rag/image-search` | 200 且命中 MM-07 id |
| MM-09 | Chrome 能力状态 | Voice/搜索就绪，图片入库需授权 |
| MM-10 | 前端目录升级 | `catalog.json` 不缓存旧状态 |
| MM-11 | 回滚兼容 | OpenAI speech/CLIP provider 仍可选 |
| MM-12 | 凭据安全 | 日志、diff、前端无 key |
