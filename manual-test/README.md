# 手动测试场景

这是一套独立、路径不含中文的小型测试数据。感知根目录为 `sense-root/`，待整理目录为 `sense-root/Inbox/`。项目根目录的 `application.properties.example` 已指向这里；克隆项目后，先复制为 `application.properties`。要测试真实 AI，再填入自己的第三方 `ai.api-key`。

已有分类目录：`Pictures/`、`Documents/`、`Work/`、`Archives/`。`Inbox/` 中故意放错了四个文件：

- `trip-photo.png`：图片放在收件箱。
- `meeting-notes.md`：项目笔记放在收件箱。
- `Temp/invoice.pdf`：发票放在临时目录。
- `Unsorted/project-backup.zip`：备份放在未分类目录。

`Inbox/.hidden-test-file` 用于验证隐藏文件被跳过。macOS 若生成 `.DS_Store`，同样会被跳过；这类系统文件不会提交到 Git。

在 IntelliJ IDEA 中，以 `com.example.tidying.FileTidyingAssistant` 为主类创建 Application 配置，工作目录设为 `$PROJECT_DIR$`，使用 JDK 17 或更新版本。运行后先检查目录树和整理计划，确认后输入 `APPLY`。在这套初始结构中，发票、备份、照片由本地规则处理；笔记进入 AI 元数据分类，模型可要求一次内容补充，也可建议新建合理目录。检查终端的分流、请求及用量统计；实际请求次数随模型结果变化。撤销后再次运行通常会复用有效建议；要重新向模型询问，可给启动参数加 `--refresh`。

整理后，从项目根目录执行以下命令即可撤销，不会再请求 AI：

```bash
java -jar target/bei-file-tidying-0.3.0.jar --undo-last
```

撤销会把文件放回原位，恢复本次删除的空目录，并清理本次新建且已空的目录。也可在程序提示时输入 `UNDO`，但命令行参数可以跳过 AI 分析等待。
