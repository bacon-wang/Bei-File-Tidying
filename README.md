# Bei File Tidying

Bei File Tidying 感知一个根目录的文件夹结构，把目标文件夹中的文件按 AI 建议批量整理到位。

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
tidy.delete-empty-directories=true
```

目录树默认只输出目录，隐藏目录会被过滤；批量请求会返回每个文件的独立建议。

程序会扫描目标文件夹及其子文件夹中的文件。AI 会优先复用合理的既有文件夹，也可以建议在感知根目录或目标文件夹内部新建分类文件夹；计划会标记新建项，只有输入 `APPLY` 后才创建并移动。

每批默认处理 10 个文件，最多同时处理 2 个批次。批次响应缺少文件或格式错误时，会自动退回逐文件请求。整理后只清理本次移动导致变空的目标子目录，目标目录本身不会删除。

成功整理会在 `sense.root/.bei-file-tidying/history/last-operation.json` 保存最近一次操作。也可以直接使用 `--undo-last` 撤销，不需要再次请求 AI：

```bash
java -jar target/bei-file-tidying-0.2.0.jar --undo-last
```

撤销会恢复本次删除的空目录，并清理本次新建且撤销后为空的目标目录。原位置已有文件、目标文件丢失或已修改时会保留当前文件并报告冲突。

`application.properties`、IDEA 配置、构建产物和整理历史不会提交到 Git。可重复的测试数据和说明位于 [manual-test](manual-test/README.md)。
