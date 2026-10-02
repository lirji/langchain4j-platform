# S4：不可变跨运行时契约组合

LOCAL_PASS。Producer固定`6f43ddf83ffd47558a056d45a363ea911218846f`，46文件manifest
`sha256:c6cf1ebcac313afdd21b7d6dab93ba284b768d6b1a52ef73abeb691eefa57410`。
Consumer实际使用25项，vendor manifest同时固定完整producer revision/manifest摘要和消费文件摘要。
预算、回执、只读领取协议来自Python typed models并被HTTP客户端实际使用；Java对应DTO正反向校验。

`deploy/sync-agent-contracts.sh`默认verify指定Git blob。源不存在、指定提交不存在、manifest缺文件、
摘要不符、vendor漂移、文件清单不符都返回非零，删除了缺上游skip-success路径。
上游工作树或HEAD变更不影响当前固定组合；更新需显式`--write --revision <40hex>`，
所有上游blob验证后才写副本，不会将未提交schema冒充该版本。
Producer CI重新生成校验并输出确定性ZIP/source.json；本地ZIP摘要
`62a55850df987a668170fabb39cc7fe1c11bf63dca121e236f943481b8a55165`。

Consumer CI检出公开仓lirji/platform-agentscope的固定提交，运行locked producer export校验和完整native reactor。
不依赖latest、兄弟仓是否碰巧存在、浮动artifact名或跨私有仓新凭据。
本地隔离临时Git库4项故障测试通过；真实指定提交verify PASS，实际缺源返回1；Java协议14项通过，
Python契约/HTTP组合42项通过。无CI Docker/付费模型新调用。

组合更新/回滚以旧或新固定producer提交+consumer提交成对验证，不自动升级现网协议。
固定源提交须先正常发布producer main，再发布consumer；本轮发布/远程CI结果见DELIVERY_REPORT。
