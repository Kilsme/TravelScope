# 评估中心 Trace 采集

在线请求和批量测评使用同一个 OpenTelemetry SDK 采集，`AssessmentTraceProcessor` 将业务 Span 投影到现有 PostgreSQL 评估表。同一个 SDK 保留 Spring Boot 的 OTLP 导出，Jaeger 不参与统计查询。

## 数据和兼容性

- 每轮有一个业务根 Span；Agent、逻辑模型请求、实际模型尝试、工具各有自己的原生 Span。Reactor 与 AgentScope 使用不可变 `ObservationContext` 显式传播父节点。
- 只有带业务运行标识和已知节点类型的 Span 进入统计。模型池诊断、HTTP、数据库调用和后台探活不会重复累计。
- 根 Span 映射到 `assessment_runs`，节点映射到 `assessment_spans`。原 UUID、父节点 UUID、接口、字段和页面保持兼容；新增原生标识及 `collection_version`、安全属性字段。节点耗时来自 SDK 起止时间，不再采用调用方手工传入的运行耗时。
- `MODEL_CALL` 只组织请求和重试。模型用量、费用按实际 `MODEL` 尝试汇总；父 Agent 汇总不再次计入总计。模型未返回用量或未配置价格时保留未知值。
- 事后评分使用原运行的原生根节点作为父上下文，标记 `SCORING`，不增加业务运行、不改写规划耗时。历史运行缺少原生父标识时不会伪造历史标识。
- 正文、错误详情和产物仍走已有脱敏存储，不放进 Span 属性。Span 只携带关联 ID、状态、用量、价格版本等结构化元数据。

## 故障行为

SpanProcessor 回调只复制快照并加入有界队列，默认容量 2048。数据库写入和脱敏正文持久化共用 FIFO 队列，开始记录先于依赖它的正文写入。

数据库写入失败或队列溢出不打断聊天，但标记相关运行的数据不完整。统计仍显示已知数据和现有采集健康提示，不能将缺失值当成零。写入屏障结合运行的持久化完整性检查，防止规则和评分使用半份数据；评分本身的采集失败不会触发额外模型重试。数据库恢复后新运行可继续采集，历史不完整运行不会自动变完整。

采集业务 Span 始终完整采样，诊断 Span 继续遵循 `management.tracing.sampling.probability`。Jaeger 导出失败不影响本地投影。重启恢复逻辑将遗留运行和节点标记中断，不重新调用模型。指标保留 180 天，正文和产物保留 30 天。

## 升级与回滚

1. 备份数据库和当前后端包，停止新任务进入并等待在线任务、测评批次及评分收尾。
2. 已安装评估表的环境执行 `src/main/resources/assessment-trace-upgrade.sql`；尚无评估表时执行完整 `assessment-upgrade.sql`。两个脚本均可重复执行，新环境 `schema.sql` 已包含升级。
3. 部署后端，核对采集健康、历史统计及调用链。前端无需修改或发布。
4. 新运行的采集版本为 `OTEL_V1`，历史记录保留 `LEGACY`，不做历史 Trace 回填。每次运行只由一种采集器写指标。

回滚时同样先等待在途任务完成，再换回旧后端；保留新增字段及新记录。旧后端写入继续使用 `LEGACY` 默认值，禁止删除恢复和 Trace 数据。

## 验证

新增 `AssessmentTraceTest`、`AssessmentTracePostgresTest`，覆盖共享 SDK 导出、非采样父链路、故障隔离、实际 provider 切换、节点重放、乱序终态、异步父子关系、手动评分和重启。

PostgreSQL 测试需要 `ASSESSMENT_TEST_DATABASE_URL/USER/PASSWORD`，仅允许显式测试库，并在其中创建、清理自有随机 schema。断线重连回归使用原有 `RECONNECT_TEST_DATABASE_*` 配置。使用假模型，不需真实 API 凭证。
