# Bei File Tidying

Bei File Tidying 感知一个根目录的文件夹结构，把目标文件夹中的文件按 AI 建议批量整理到位。

## 项目文档

- [需求文档](docs/requirements.md)：目标、范围与验收标准。
- [架构文档](docs/architecture.md)：实际结构、数据流与实现边界。
- [测试文档](docs/testing.md)：已验证内容、复现步骤与待补测试。
- [整体项目计划](docs/project-plan.md)：阶段成果、优先级与交付门槛。

## 快速开始

需要 JDK 17 或更新版本。项目自带 Maven Wrapper，无需单独安装 Maven。在项目根目录执行：

```bash
cp application.properties.example application.properties
./mvnw clean test
./mvnw package
java -jar target/bei-file-tidying-0.2.0.jar
```

Windows 下用 `mvnw.cmd` 代替 `./mvnw`。配置文件必须位于运行时的工作目录；上面的命令应从项目根目录运行。

示例配置指向 `manual-test/sense-root`，可以先用这套隔离数据查看计划。要调用真实 AI，在本地 `application.properties` 填写第三方平台的 `ai.api-key`。密钥留空时，程序使用扩展名规则。随后可修改 `sense.root` 和 `target.dir` 指向自己的目录；目标目录必须位于感知根目录内。

## 在 IntelliJ IDEA 中运行

1. 用 IntelliJ IDEA 打开项目目录或 `pom.xml`，选择 JDK 17 或更新版本。
2. 按上面的步骤创建并填写本地 `application.properties`。
3. 创建 Application 运行配置，主类为 `com.example.tidying.FileTidyingAssistant`，工作目录为项目根目录。
4. 查看整理计划，仅在确认后输入完整的 `APPLY`；其他输入会取消。

可在 `application.properties` 调整目录树深度、批次大小、并发批次数和空目录清理：

```properties
sense.tree-depth=3
ai.batch-size=10
ai.batch-concurrency=2
ai.max-requests=20
ai.max-prompt-chars=12000
tidy.delete-empty-directories=true
```

目录树默认只输出目录，隐藏目录会被过滤；普通批次返回逐文件建议，明确的编号系列可返回整组建议。

程序会扫描目标文件夹及其子文件夹中的文件。AI 会优先复用合理的既有文件夹，也可以建议在感知根目录或目标文件夹内部新建分类文件夹；计划会标记新建项，只有输入 `APPLY` 后才创建并移动。

### 减少 AI 请求与内容传输

程序先用保守规则识别名称和类型都明确的发票、备份压缩包、旅行照片，匹配唯一的已有根级分类目录。主题子目录、项目目录、有多个候选目录或需要细分的情况交给 AI。规则只生成建议，执行仍需 `APPLY`。

其余文件首先只发送相对路径、类型、大小和最多 2,000 字符的相关目录索引。只有模型明确要求补充内容时，才把支持的文本文件前 1,200 字符发送给 AI；仍无法确定的文件标记 `REVIEW`，保留原位。PDF、Office、图片当前不提取正文或图像内容。

默认最多 10 文件 / 批、2 批并发，并按输入长度拆批。一次运行最多 20 次请求，单次提示词最多 12,000 字符；预算包含内容补充与重试。每个阶段只对未解决项补请求一次，401 / 403 / 429 等客户端错误停止后续请求，连续 3 次无有效结果也停止；已发出的并发请求仍可能完成。终端显示本地规则数、AI 队列数、摘要读取数、请求数、输入字符数、耗时和平台报告的 token 用量；用量缺失或全为 0 时显示费用未知。

同目录、同格式、共同主题前缀加编号且至少 8 个文件的系列会尝试一次组分类。模型看到全部成员名称，只有明确确认同一目的地时才逐文件生成计划；否则按现有逐文件批次处理。名称相似但主题或格式不同的文件不会加入该组。组请求与回退都受原有预算约束。

有效的 AI 建议缓存在 `sense.root/.bei-file-tidying/classifications.properties`，取消执行后再次分析也可复用。缓存按文件大小、修改时间、文件标识、文本前段摘要、完整目录结构、目标目录、模型与密钥摘要校验；命中后仍重新检查目的地安全与同名冲突。缓存仅保存分类建议及截短的理由，不保存 API Key 原文或文件正文；文件内容前段会在本地读取以校验缓存。使用 `--refresh` 可跳过并替换当前缓存：

```bash
java -jar target/bei-file-tidying-0.2.0.jar --refresh
```

已有配置无需修改，新参数缺省即生效。`ai.max-requests=0` 可禁止本次 AI 请求，未被本地规则或缓存处理的文件会留待确认。字符预算不等于 token 或费用限额。

整理后只清理本次移动导致变空的目标子目录，目标目录本身不会删除。

成功整理会在 `sense.root/.bei-file-tidying/history/last-operation.json` 保存最近一次操作。也可以直接使用 `--undo-last` 撤销，不需要再次请求 AI：

```bash
java -jar target/bei-file-tidying-0.2.0.jar --undo-last
```

撤销会恢复本次删除的空目录，并清理本次新建且撤销后为空的目标目录。原位置已有文件、目标文件丢失或已修改时会保留当前文件并报告冲突。

`application.properties`、IDEA 配置、构建产物、整理历史和分类缓存不会提交到 Git。可重复的测试数据和说明位于 [manual-test](manual-test/README.md)。
