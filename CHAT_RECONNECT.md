# 对话断线恢复：部署和验收

一轮对话由 PostgreSQL 持久化任务驱动，模型执行不属于浏览器的 SSE 请求。
断开连接、切换会话和关闭页面不会停止任务；只有“停止”按钮会请求取消。
当前实现仅支持单后端实例。启动时所有遗留活动任务转为 `INTERRUPTED`，不重跑模型。

## 部署

1. 备份数据库，停止接收新对话，等待在途任务结束。
2. 在业务库执行 `src/main/resources/chat-reconnect-upgrade.sql`。脚本可以重复执行；旧消息的 `turn_id` 保持空值。新环境使用更新后的 `schema.sql`。
3. 部署后端，再部署前端。没有执行迁移时，后端启动会明确报错，不会静默回退到不可恢复的旧执行方式。
4. 反向代理对事件接口关闭响应缓冲，允许超过 15 秒的空闲时间；事件端点每 15 秒发送注释心跳，每个连接最长 120 秒，随后客户端自动接续。
5. 回滚前停止接收新任务并等在途任务结束。保留新增表、索引和消息字段，不能删除去重与恢复记录。

配置位于 `travelscope.reconnect`，默认值如下：

```yaml
travelscope:
  reconnect:
    enabled: true
    preparation-seconds: 60
    queue-seconds: 30
    execution-seconds: 900
    retention-hours: 24
    sse-timeout-seconds: 120
```

`enabled: false` 用于维护期间关闭对话受理和恢复接口，旧 `/chat/stream` 也使用同一执行机制。
保持现有网关并发、QPS、快慢泳道和收费档位。MinIO 请求具有连接、读写及整次调用时限。

## API 和恢复约定

全部接口位于 `/api`，使用 Bearer 认证。任务不存在或不属于用户时统一 404。

| 接口 | 用途 |
| --- | --- |
| `POST /chat/turns` | 使用客户端 UUID 幂等受理；执行中 202，已结束 200 |
| `GET /chat/turns/{turnId}` | 状态和完整快照，包含 `snapshotSeq` |
| `GET /chat/turns/{turnId}/events?afterSeq=N` | 补发并持续读取同一事件日志 |
| `POST /chat/turns/{turnId}/cancel` | 幂等请求停止；取消确认前仍占用会话 |
| `GET /chat/conversations/{id}/turns?activeOnly=true` | 查找活动任务；省略参数返回会话全部轮次快照 |
| `GET /auth/me` | 服务器确认的账户身份，作为浏览器缓存命名空间 |

请求包含 `turnId`、原始 `conversationId`（首次发送可以是 null）、`message`、`imageBase64` 和表单的 `sourceTurnId`。
首次发送前将请求原样写入 IndexedDB；确认丢失时复用完整原请求，不能把新返回的会话 ID 改写进去。
同 ID 内容不同返回 `409 IDEMPOTENCY_CONFLICT`；会话已有任务返回 `409 CONVERSATION_BUSY`，不自动排队；旧卡片返回 `409 FORM_STALE`。

事件序号覆盖全部正文、活动、需求卡片、质检、行程骨架与每日版本。SSE `id` 与 JSON `seq` 相同；业务类型由 SSE `event` 和 JSON `type` 表示。
每轮只有一个 `terminal` 事件，携带 `status / outcome / content / assistantMessageId / error`，包括失败、取消和重启中断。
`WAITING_INPUT` 是成功终态的结果，不占执行位置。每日行程 `version` 与传输序号独立。

客户端只在应用完整事件后推进游标；重复事件忽略，缺号重新同步。终态正文始终覆盖半截内容。
游标越过水位返回 `409 CURSOR_INVALID`；日志清理后返回 `410 REPLAY_EXPIRED`，客户端改为获取完整快照。
事件在任务结束满 24 小时后清理；去重记录、消息和快照保留。未被用户消息引用的失败图片准备对象在 24 小时后回收。
正文增量按 50 毫秒或 4 KiB 合并。日志与对应快照在同一事务更新，因此快照水位始终覆盖其实际内容。

图片只保存固定随机对象键和媒体类型；base64 不入库、不进入任务日志，不写入对话记忆。
同轮准备重试可以重新 PUT 同一对象，不会生成新的随机对象。准备超时或取消后，晚到的上传不能重新启动任务。

旧 `POST /chat/stream` 是适配入口，终态映射为旧 `done/error`。未提供 `turnId` 的旧调用无法保证发送重试去重。
评估仍使用原隔离执行入口，不走在线受理，不写用户消息或扣积分；在线观测关联业务轮次 ID。

## 本地测试

前端：`cd frontend` 后执行 `npm test`、`npm run build` 和 `npm run lint`。
前端新增 Vitest 和 fake-indexeddb，覆盖 UTF-8 跨块、CRLF、多行数据、半帧、游标缺号、重复事件、每日版本、权威终态、冻结载荷和账户隔离。

后端单元/接口测试：

```powershell
mvn "-Dtest=ChatTurnApiTest,ChatService*Test,ChatControllerImagesTest,ItineraryProgressTest" test
```

真实 PostgreSQL 验收必须单独执行，不应只接受跳过结果。创建一个空的 `travelscope_reconnect_test` 库，提供连接：

```powershell
$env:RECONNECT_TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/travelscope_reconnect_test'
$env:RECONNECT_TEST_DATABASE_USER='测试库用户'
$env:RECONNECT_TEST_DATABASE_PASSWORD='测试库密码'
mvn "-Dtest=ChatTurnPostgresTest" test
```

集成测试拒绝其他库名，每个用例创建随机隔离 schema 并在结束后删除，只操作自己的 schema；迁移脚本会执行两遍验证可重复性。
通过可控制的假模型流覆盖并发重复受理、单次扣费、会话占用、终态竞争、失败回滚、补发连续性、表单消耗、图片准备重试、重启、总时限、两个 SSE 订阅者及越权。
Redis 不参与任务表事务和补发；这些测试没有 Redis 连接，不需要模型 API 密钥。

既有认证仍使用 Redis 的不透明 Bearer 令牌。单实例进程只缓存已经验证过的令牌指纹，Redis 短暂不可用时可继续恢复任务，每次仍从 PostgreSQL 检查账户状态；登出和禁用会撤销本地降级资格。未验证过的令牌（包括后端刚重启且 Redis 同时不可用）返回 503 和等待时间，不能绕过认证，也不会误发 401 清除浏览器登录状态。降级缓存不延长 Redis 故障期间的认证有效期。

浏览器验收使用生产前端构建与本地可控假后端，验证确认丢失、断网后完成、刷新、切换会话、多标签页和显式停止。
上线前还应在目标反向代理和 MinIO 部署环境验证真实网络中断与图片上传。
已有主线 `AssessmentReviewRegressionTest#oversizeTextIsTruncatedNotPersistedVerbatim` 慢测试单独记录，不作为本功能的修改范围。

## 运维

### 显式取消与模型连接

点击停止会请求 `/api/chat/turns/{turnId}/cancel`。每轮的 `TurnCancellation` 同时传入 Reactor、AgentScope 和同步工作流；取消会中止模型流及阻塞等待，并在分类重试、模型池切换、需求抽取、并行每日文案、规划和修订入口拒绝后续工作。同步入口的线程作用域只是兼容桥接，跨线程使用同一轮的显式上下文对象。取消轮不启动收尾摘要；已排队或执行中的本轮摘要也响应同一信号。

取消通道使用完成信号先取消上游，再报告取消异常，避免当前 Reactor 版本中伴随流直接报错却没有关闭源连接的问题。`TurnCancellationTest` 通过生产 OpenAI 客户端连接本地 SSE 模型服务器，要求服务端实际观察到连接断开，并验证不切换备用模型；其余取消测试覆盖分类、需求工作流、并行文案及摘要。运行日志记录 `turn_cancel_requested`、`model_stream_cancelled` 和最终任务状态。此验证覆盖应用到模型端点的连接；外部网关是否继续向模型供应商传播断开及供应商计费，需要网关侧证据，不能仅凭本地 `CANCELLED` 推断。

Micrometer 指标前缀 `chat.turn`：`duplicate`、`execution`、`event_delivered`、`event_replayed`、`recovery_duration`、`recovery_failure.<reason>`、`persistence_failure`、`image_prepare_failure`、`restart_interrupted`、`terminal.<status>`、`cancel_duration`。
任务日志关联 `userId / conversationId / turnId`。持久化失败时停止生成，终态事务每秒重试；不能绕过存储发送未提交内容。
备份策略应同时包含 PostgreSQL 与 MinIO。任务快照和去重记录需要随会话保留。
