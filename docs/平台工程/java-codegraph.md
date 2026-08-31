# Java Code Graph 使用指南

## 用途

`tools/java-codegraph/CodeGraphCli.java` 使用 JDK 21 Compiler Tree API 离线扫描 Java 源码，输出类型、方法、Spring HTTP 入口及它们之间的结构/调用关系。它不加入 Maven reactor，也不下载解析器依赖。

```bash
graph_dir="$(mktemp -d)"
java tools/java-codegraph/CodeGraphCli.java build \
  --root . --output "$graph_dir/graph.json"

java tools/java-codegraph/CodeGraphCli.java query \
  --root . --symbol OrderService

java tools/java-codegraph/CodeGraphCli.java query \
  --root . --file order-service/src/main/java/com/lrj/platform/order/OrderService.java
```

build 输出 `coding-agent-codegraph/v1`、内容 digest、扫描/解析/失败计数、nodes 和 edges。query 返回声明位置、直接关系、反向影响候选和相关测试；符号不存在退出 2，名称歧义退出 3，并要求改用限定名或文件查询。

## 证据强度

- `resolved`：Javac 在当前无完整 classpath 条件下仍能确定的声明关系。
- `syntactic`：按源码语法和名称得到的候选关系，只能用于导航和影响面提示，不能宣称为精确运行时调用链。

所有节点和边保留相对文件及行号。框架反射、Spring 动态代理、配置条件、AOP、序列化和运行时路由不会被静态图完整表达；安全/租户/事务边界仍需结合配置、测试和文本搜索复核。

## 验证与维护

```bash
bash tools/java-codegraph/test/run-tests.sh
```

fixture 覆盖 endpoint、imports、extends/implements、字段/参数依赖、方法调用、overload 唯一 ID、文件查询、重复简单名歧义和确定性 digest。2026-08-31 的最终全仓校验处理 1,044/1,044 个 Java 文件、0 个解析失败，生成 7,266 个节点和 62,481 条边；该数量会随源码变化，CI 只要求扫描成功和输出非空，不锁死计数。
