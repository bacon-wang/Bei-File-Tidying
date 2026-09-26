# 测试文档

更新：2026-09-26｜实现基线：0.2.0（`f7d6f4e`）

## 1. 执行方式

在项目根目录，使用 JDK 17 或更新版本：

```bash
./mvnw --batch-mode clean verify
```

Windows 使用 `mvnw.cmd`。IDEA 中可在 Maven 面板运行 `verify`，或运行 `src/test/java` 下的测试类。自动化测试使用临时目录和本地模拟 HTTP 服务，不需要 API Key；报告位于 `target/surefire-reports/`。

## 2. 自动化覆盖

现存 Surefire 报告：**5 项通过，0 失败，0 错误**。本次文档整理核对了报告和测试代码，未重新运行程序或真实 AI。

| 用例 | 已验证内容 | 对应需求 |
| --- | --- | --- |
| `treeDepthAndCompletedEventFallbackWork` | 目录树深度、隐藏目录过滤、仅含 completed 事件的响应提取 | F02、F09 |
| `localFallbackAndNewFolderPlanWork` | 无密钥分类、递归扫描、简单路径穿越拒绝、新目录计划与移动 | F02、F03、F06、F09 |
| `aiSuccessAndUnauthorizedResponsesAreHandled` | 模拟 SSE 成功响应、HTTP 401 转为失败计划 | F03、F09 |
| `undoRemovesNewlyCreatedDestinationDirectories` | 创建嵌套目的地，撤销后恢复文件并清理新目录 | F08 |
| `smallMessyFolderIsPlannedAndTidied` | 4 文件仅发 1 次模拟请求、分类移动、隐藏文件保留、空目录删除及撤销 | F03、F04、F07、F08 |

源码：[FileTidyingAssistantTest](../src/test/java/com/example/tidying/FileTidyingAssistantTest.java)、[TidyingScenarioTest](../src/test/java/com/example/tidying/TidyingScenarioTest.java)。这些测试验证程序行为，不证明第三方服务的可用性、分类质量或性能。

## 3. 真实 AI 手动验收

使用项目内的 [manual-test](../manual-test/README.md)，根目录为 `manual-test/sense-root`，目标为其 `Inbox`。已有本地配置时无需覆盖；首次使用可复制示例配置并填写对应平台密钥。

| 文件 | 合理分类方向（允许模型给出其他合理目录） |
| --- | --- |
| `Inbox/trip-photo.png` | `Pictures` 或图片子目录 |
| `Inbox/meeting-notes.md` | `Work` 或项目资料子目录 |
| `Inbox/Temp/invoice.pdf` | `Documents` 或发票子目录 |
| `Inbox/Unsorted/project-backup.zip` | `Archives` 或备份子目录 |

验收步骤：

1. 构建后执行 `java -jar target/bei-file-tidying-0.2.0.jar`，确认目录树、4 个文件、批次进度和逐文件理由可见。
2. 首次输入其他内容取消，确认文件和目录未变；再次运行并检查建议，可使用既有目录或新建目录。
3. 输入 `APPLY`，确认文件移动到计划位置，内容不变；`Temp`、`Unsorted` 为空时删除，`Inbox` 和隐藏文件保留。
4. 执行 `java -jar target/bei-file-tidying-0.2.0.jar --undo-last`，确认 4 个文件回到原位、原目录恢复、本次新建且已空的目录被清理。

历史人工验证（2026-09-26）：第三方 FHL Responses 接口、`gpt-6-luna` / `medium`，4 文件一次真实批量请求返回 HTTP 200，完成移动、删除 2 个空目录和撤销；观察到复用及新建目录建议。一次成功请求约 20 秒，不能据此承诺固定耗时。此前也出现过 HTTP 200 空响应；现有重试与回退仍需异常测试。

## 4. 待补测试与通过标准

| 优先级 | 待补范围 | 通过标准 |
| --- | --- | --- |
| P0 | CLI 确认、取消、EOF；同名冲突及部分移动失败 | 未确认不移动，不覆盖既有文件，失败原因明确 |
| P0 | 符号链接、隐藏父目录、无效 JSON；历史损坏和撤销冲突 | 不越界，不误执行，不丢失文件，未恢复项可追踪 |
| P1 | 超时、403 / 429、空结果、重复 / 缺失源路径 | 有界重试，逐项结果可解释，无错配或重复移动 |
| P1 | 10 / 50 / 184 文件、多个并发批次 | 记录耗时、请求数、回退数及成功率，再制定性能目标 |

GitHub Actions 在推送 `main` 或提交 PR 时使用 JDK 17 执行相同的 `clean verify`；真实 AI 验证单独进行，不进入常规 CI。
