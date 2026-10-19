# 自有旧版 Word 夹具

`enterprise-reference.txt` 是本项目编写的无敏感信息中英测试文本。`enterprise-reference.doc` 是其真实 Word 97 OLE 格式，不是改扩展名。作者/编辑者/公司固定为测试值；无宏或嵌入对象。Linux CI 直接读该固定文件，不依赖 Office/macOS。

在 macOS 重新生成：

```bash
textutil -convert doc -inputencoding UTF-8 -strip -author 'Synthetic fixture' -editor 'Synthetic fixture' -company Test -output enterprise-reference.doc enterprise-reference.txt
```

生成物可能含容器时间戳，因此不要求不同操作系统重新生成逐字节相同；正文、格式签名与解析结果由测试验证。SHA-256：`b6ddef2f90cbaa02431001a56216ede48e039d98ba61eeee3de39747e519501e`。
