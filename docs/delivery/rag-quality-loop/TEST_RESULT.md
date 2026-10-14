# 验证记录

## S1 发布复查版本

- 先加入缺失/旧版本/新版本/空版本反例，原实现失败；修复后全部通过。
- `mvn -o -B -pl knowledge-service -am test` PASS。版本字段通过controller映射验证；原有发布前后registry检查保留。
- 兼容：KnowledgeHit.version为加法可空字段，旧Java九参数构造器保留；新复查面对旧query无version时拒绝通过。升级顺序query→ingest-api；普通查询无行为变化。
- 未运行真实模型或部署。
