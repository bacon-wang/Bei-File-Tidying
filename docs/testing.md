# 测试文档

更新：2026-09-26｜实现基线：0.2.0（分层分类迭代）

## 1. 执行方式

在项目根目录，使用 JDK 17 或更新版本：

```bash
./mvnw --batch-mode clean verify
```

Windows 使用 `mvnw.cmd`。IDEA 中可在 Maven 面板运行 `verify`，或运行 `src/test/java` 下的测试类。自动化测试使用临时目录和本地模拟 HTTP 服务，不需要 API Key；报告位于 `target/surefire-reports/`。

## 2. 自动化覆盖

本轮自动化结果：**33 项通过，0 失败，0 错误**；真实 AI 整理和撤销验证见下文。

| 用例 | 已验证内容 | 对应需求 |
| --- | --- | --- |
| `treeDepthAndCompletedEventFallbackWork` | 目录树深度、隐藏目录过滤、仅含 completed 事件的响应提取 | F02、F09 |
| `localFallbackAndNewFolderPlanWork` | 无密钥分类、递归扫描、简单路径穿越拒绝、新目录计划与移动 | F02、F03、F06、F09 |
| `aiSuccessAndUnauthorizedResponsesAreHandled` | 模拟 SSE 成功响应、HTTP 401 转为失败计划 | F03、F09 |
| `undoRemovesNewlyCreatedDestinationDirectories` | 创建嵌套目的地，撤销后恢复文件并清理新目录 | F08 |
| `smallMessyFolderIsPlannedAndTidied` | 4 文件仅发 1 次模拟请求、分类移动、隐藏文件保留、空目录删除及撤销 | F03、F04、F07、F08 |
| `EfficientPlanningTest`（9 项） | 本地分流、元数据不含正文、按需摘要、无效响应有限重试、缺项定向补请求、429 停止、并发预算、字符拆批、深层目录、用量及 184 文件场景 | F04、F10、F11 |
| `CacheSafetyTest`（1 项） | 缓存目录符号链接不读写根目录外文件 | F06、F13 |
| `HistorySafetyTest`（4 项） | 历史目录与源目录被符号链接替换时，整理和撤销不读写根目录外文件 | F06、F08 |
| `GroupCacheTest`（14 项） | 24 文件组与异类回退、预算、缓存失效、冲突、无效响应不缓存、同大小文本改写、组响应歧义、组回退缓存、分析期间文件变化、符号链接及执行前复核 | F06、F12、F13 |

源码：[FileTidyingAssistantTest](../src/test/java/com/example/tidying/FileTidyingAssistantTest.java)、[TidyingScenarioTest](../src/test/java/com/example/tidying/TidyingScenarioTest.java)、[EfficientPlanningTest](../src/test/java/com/example/tidying/EfficientPlanningTest.java)、[GroupCacheTest](../src/test/java/com/example/tidying/GroupCacheTest.java)、[CacheSafetyTest](../src/test/java/com/example/tidying/CacheSafetyTest.java)、[HistorySafetyTest](../src/test/java/com/example/tidying/HistorySafetyTest.java)。这些测试验证程序行为，不证明第三方服务的可用性、分类质量或性能。

针对性验证：旧实现的 10 文件无效响应场景产生 11 次请求，新实现限制为 2 次。184 文件合成场景中，180 个明确命名的照片由规则处理，4 个未知文本发 1 次模拟请求，全部生成计划；本地一次观测约 0.35 秒（含稳定性等待）。旧批量算法在同规模下正常需 19 次请求，这是按批量大小推算，未做真实 AI 对比测速。

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

上轮人工验证（2026-09-26 11:05）：第三方 FHL Responses 接口、`gpt-6-luna` / `medium`。3 个文件命中本地规则；笔记先发元数据，模型要求后补充摘要，共 2 次 HTTP 200，输入提示词合计 1,863 字符，分析耗时 17.8 秒。笔记建议新建 `Work/Java Project`。确认后移动 4 文件、清理 2 个空目录；撤销后全部路径、目录树及文件 SHA-256 与测试前一致。

本轮复测（2026-09-26 11:49）：`--refresh` 后 4 文件生成计划，2 次 HTTP 200，分析耗时 41.2 秒；执行和撤销后 4 个文件的 SHA-256 与原目录一致。再次分析命中 1 条 AI 缓存，0 次请求，耗时 0.3 秒。服务响应时间有波动，缓存收益仅对应重复分析。

路径加固复测（2026-09-26 12:24）：`--refresh` 后 2 次 HTTP 200，分析耗时 38.8 秒；4 个文件整理、清理 2 个空目录后执行 `--undo-last`，文件 SHA-256 和目录清单均与运行前一致。

平台本次回报的 token 数均为 0，按未知用量处理，无法量化费用节省。小样本的两阶段判断可能增加请求次数；184 文件合成结果不能外推为真实混合资料的分类准确率或耗时保证。

## 4. 待补测试与通过标准

| 优先级 | 待补范围 | 通过标准 |
| --- | --- | --- |
| P0 | CLI 确认、取消、EOF；同名冲突及部分移动失败 | 未确认不移动，不覆盖既有文件，失败原因明确 |
| P0 | 并发替换路径、隐藏父目录、复杂无效 JSON；历史损坏和撤销冲突 | 不越界，不误执行，不丢失文件，未恢复项可追踪 |
| P1 | 超时、403、持续 5xx、重复源路径及复杂畸形响应 | 补齐现有重试、停止条件和解析的异常覆盖 |
| P1 | 10 / 50 / 184 文件的真实混合资料对比 | 记录分类质量、耗时、请求数及有效 token 用量，再制定性能目标 |

GitHub Actions 在推送 `main` 或提交 PR 时使用 JDK 17 执行相同的 `clean verify`；真实 AI 验证单独进行，不进入常规 CI。
