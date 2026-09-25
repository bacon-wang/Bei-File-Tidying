# Bei File Tidying

Bei File Tidying 感知一个根目录的文件夹结构，把目标文件夹中的文件按 AI 建议批量整理到位。

## 在 IntelliJ IDEA 中运行

1. 用 IntelliJ IDEA 打开 `pom.xml`。
2. 复制 `application.properties.example` 为 `application.properties`。
3. 配置 `sense.root`（感知根目录）和 `target.dir`（要整理的目标文件夹）。目标文件夹必须位于感知根目录内。
4. 配置 `ai.api-key`、`ai.base-url`、`ai.model`、`ai.reasoning-effort` 和 `ai.wire-api`。当前第三方平台配置使用 `https://www.fhl.mom`、`gpt-6-luna`、`medium` 和 `responses`。
5. 可调整目录树深度、批次大小、并发批次数和空目录清理：

   ```properties
   sense.tree-depth=3
   ai.batch-size=10
   ai.batch-concurrency=2
   tidy.delete-empty-directories=true
   ```

   目录树默认只输出目录，隐藏目录会被过滤；批量请求会返回每个文件的独立建议。
6. 运行 `com.example.tidying.FileTidyingAssistant`。
7. 程序会展示整理计划，输入 `APPLY` 才会移动文件；输入 `UNDO` 撤销最近一次整理，其他输入会取消。

程序会扫描目标文件夹及其子文件夹中的文件。AI 会优先复用合理的既有文件夹，也可以建议在感知根目录或目标文件夹内部新建分类文件夹；计划会标记新建项，只有输入 `APPLY` 后才创建并移动。

每批默认处理 10 个文件，最多同时处理 2 个批次。批次响应缺少文件或格式错误时，会自动退回逐文件请求。整理后只清理本次移动导致变空的目标子目录，目标目录本身不会删除。

成功整理会在 `sense.root/.bei-file-tidying/history/last-operation.json` 保存最近一次操作。也可以直接使用 `--undo-last` 撤销，不需要再次请求 AI：

```bash
java -cp target/classes com.example.tidying.FileTidyingAssistant --undo-last
```

撤销会恢复本次删除的空目录，并清理本次新建且撤销后为空的目标目录。原位置已有文件、目标文件丢失或已修改时会保留当前文件并报告冲突。
