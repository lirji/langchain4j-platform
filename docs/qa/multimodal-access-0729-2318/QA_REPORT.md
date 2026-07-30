# 多模态访问连通性 QA 报告

## 结论

**视觉、语音、图片入库与跨模态检索均已修复并通过真实调用。**

原始问题包含两类根因：

- 视觉：LiteLLM 没有收到百炼凭据。
- Voice/图片向量：Java 服务使用的是 OpenAI/CLIP 兼容协议，不能只换成百炼模型名。

现已修复凭据加载，并为 Qwen3 ASR/TTS、Qwen3-VL embedding 增加原生 DashScope adapter。

## 环境

- 前端：`http://127.0.0.1:8093`
- edge：`http://127.0.0.1:18080`
- vision：`http://127.0.0.1:18090`
- voice：`http://127.0.0.1:8091`
- knowledge：`http://127.0.0.1:8084`
- Chrome：`alice / acme / Bearer`

## 用例结果

| ID | 结果 | 实际结果 |
| --- | --- | --- |
| MM-01 | 通过 | Chrome 深链加载，登录身份和上传控件可见 |
| MM-02 | 通过 | 相关服务健康检查正常 |
| MM-03 | 通过 | `/vision/caption` API 与 Chrome 均为 200 |
| MM-04 | 通过 | `/chat/vision` 经鉴权网关返回 200 |
| MM-05 | 通过 | 119084 字节 WAV → `你好，请介绍一下退款政策。` |
| MM-06 | 通过 | 200；回复 38 字符；返回 387480 字节 `audio/wav` |
| MM-07 | 通过 | 图片入库返回 id |
| MM-08 | 通过 | 返回 1 条，命中同一 id，top score 0.8029070677594805 |
| MM-09 | 通过 | 三项 Voice 和图片检索“就绪”，图片入库“需授权” |
| MM-10 | 通过 | 前端 Fetch 与 nginx 均 no-store；容器升级后 Chrome 立即显示新状态 |
| MM-11 | 通过 | 现有 `openai` provider 分支保留 |
| MM-12 | 通过 | 仅验证 key `present`，未输出值；前端无凭据 |

## 自动化结果

- `knowledge-service`：239 tests，0 failures/errors，3 skipped。
- `voice-service`：17 tests，0 failures/errors。
- 前端聚焦/全量：30/553 tests 全通过，类型检查与生产构建通过。
- 受影响 Maven 模块及依赖打包通过。

## 缺陷状态

### MM-BUG-01：视觉 provider 缺少凭据

- 严重度：P1
- 状态：已修复
- 修复：跨 shell loader、启动前凭据/模型预检、LiteLLM 重建。

### MM-BUG-02：百炼语音协议不兼容 OpenAI `/audio/*`

- 严重度：P1
- 状态：已修复
- 修复：新增 `BailianSpeechService`，使用原生多模态生成端点。

### MM-BUG-03：百炼图片 embedding 不兼容 CLIP `/embeddings`

- 严重度：P1
- 状态：已修复
- 修复：新增 `BailianMultimodalEmbeddingModel`，文本/图片统一走
  `qwen3-vl-embedding`，使用独立 collection。

### MM-BUG-04：浏览器缓存旧能力目录

- 严重度：P2
- 状态：已修复
- 修复：Fetch `cache=no-store` + nginx `Cache-Control: no-store` + 回归测试。

## 备注

Chrome 追加文件上传时浏览器连接中断；在此之前同一音频/图片已通过鉴权 edge 完成真实
200 回归，Chrome 页面和能力状态也已刷新验证，因此该中断不判为产品缺陷。
