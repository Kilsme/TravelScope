# intake-agent 混合形态补完计划（Redis 状态 + 反问轮次 + clarify_question SSE）

## 一、现状与缺口（已核实）

| 项 | 现状 | 缺口 |
|---|---|---|
| RequirementTools（get_missing_fields / update_requirement_state） | ✅ 已有 | 缺 `ask_user`（反问统一出口） |
| TripRequirementState 实体 | ✅ 已有（8 字段 + COLLECTING/DONE/DEGRADED + missingFields 纯代码计算） | — |
| intake-agent SubagentDeclaration | ✅ 已有（tools 白名单=2 个状态机工具） | 白名单需加 ask_user |
| 反问话术提示词 | ✅ 已有（IntakeAgent.SYS_PROMPT） | 需改为经 ask_user 发问 + DEGRADED 收尾指令 |
| master 委派逻辑 | ✅ 已有（planningDirective 第 1 步 + PlanningGate 豁免，E2E 已跑通） | — |
| 状态存储 | ❌ 内存 ConcurrentHashMap | **需迁 Redis hash** `trip:req:{userId}:{sessionId}` |
| 反问轮次计数 | ❌ `incrementClarifyCycles` 无任何调用方（**3 轮 DEGRADED 放行断线**，检测标准 4 无法满足） | ask_user 调用时计数 |
| SSE clarify_question | ❌ 不存在（intake 反问混在 master 的 delta 文本里；子代理事件被 isMainAgentEvent 过滤） | 新增事件类型 + 拦截转换 |
| 前端 | ❌ 无 clarify 处理 | 类型 + 最小展示 |

**框架机制依据（反编译核验）**：子代理执行 @Tool 时，其 `ToolResultTextDeltaEvent` 会带 `parentSessionId/agentId` 格式 source 转发进 master 的 streamEvents 流（与 isMainAgentEvent 过滤注释吻合）；工具结果文本只在 `ToolResultTextDeltaEvent.getDelta()`（End 事件无内容 getter）。**拦截点定为子代理流的 ask_user 工具结果事件**——实时、语义精确，优于解析 agent_spawn 混合文本（有超时 promote 三层不确定性）。

## 二、改动清单

### 1. `service/TripRequirementStore.java` 重写为 Redis hash 存储（核心）
- 删除 @Service 注解（AgentConfig 已有 @Bean，消除双注册），构造器注入 `StringRedisTemplate`
- 键 `trip:req:{userId}:{sessionId}`，**hash 字段即状态字段**：destination/days/startDate/fromCity/budget/preference/people/special/status/clarifyCycles（fastjson2 序列化单值，逐字段 HSET；读时 HGETALL 反序列化）+ TTL 24h（常量，防会话键无限累积）
- **API 变化**：Redis 下 get() 返回的是反序列化副本，直接改字段不会持久——新增 `save(userId, sessionId, state)`；`incrementClarifyCycles` 内部 get→+1→DEGRADED 判定→save
- **降级**：Redis 异常时回退内存 ConcurrentHashMap（沿用 RedisIntentCache 的"首次 warn 后续 debug"模式；降级期间状态仅存进程内存，可接受——与意图缓存降级同语义）

### 2. `agent/tools/RequirementTools.java` 新增 ask_user（反问统一出口）
```java
@Tool(description = "向用户发出反问（intake-agent 专用唯一反问出口，不要用文本输出反问）。"
        + "每次调用自动累加反问轮次（上限 3 轮，超限后 get_missing_fields 会显示 DEGRADED）")
public String ask_user(@ToolParam(name="question") String question,
                       @ToolParam(name="sessionId") String sessionId, RuntimeContext ctx)
```
实现：`store.incrementClarifyCycles(...)` 后**返回 question 原文**——工具返回值会经 ToolResultTextDeltaEvent 流向 SSE 层，这是 clarify_question 事件的文本载体。同时 `update_requirement_state` 在字段写回后调用 `store.save(...)`（适配 Redis 副本语义）。

### 3. `agent/IntakeAgent.java` 提示词更新
- 反问策略改为：**所有反问必须经 ask_user 工具发出**（直接文本输出的反问不会实时到达用户）
- 收尾判定强化：get_missing_fields 返回 DEGRADED（已反问 3 轮仍缺）→ 不再反问，带默认值写 intake_done.md（缺项标注「待确认」）并以「信息已收齐（部分待确认）」收尾
- 不重复追问：get_missing_fields 的已收集字段列表是唯一事实源，已答字段不再问
- 选择题式反问保留并强化（缺目的地 → "国内/国外/还没定"+热门城市；缺天数 → "2 天/3 天/5 天+"；缺日期 → "本周末/下周末/具体日期"）

### 4. `config/AgentConfig.java`
- intake 声明 tools 白名单：`["get_missing_fields","update_requirement_state","ask_user"]`
- `tripRequirementStore` Bean 方法签名加 `StringRedisTemplate` 参数

### 5. `dto/ChatEvent.java` + `service/ChatService.java`（SSE 事件）
- ChatEvent 加 `TYPE_CLARIFY_QUESTION = "clarify_question"`
- runAgent 事件流：filter 条件从 `isMainAgentEvent(e)` 放宽为 `isMainAgentEvent(e) || isClarifyEvent(e)`——后者判定 `ToolResultTextDeltaEvent && "ask_user".equals(getToolCallName()) && source 含 "/"`（intake-agent 转发事件）；toChatEvent 中该事件 → `ChatEvent.of(TYPE_CLARIFY_QUESTION, e.getDelta())`。ask_user 的其他转发事件（Start/End）仍被子代理过滤器拦截，不产生噪音；master 后续整合文本照常走 delta（前端不升级也能看到反问，clarify_question 是增强信号）

### 6. 前端最小适配（React）
- `frontend/src/types.ts`：事件类型联合加 `'clarify_question'`
- `frontend/src/App.tsx`：clarify_question 渲染为「💬 需要补充信息」气泡（实现时先读这两个文件确认现有渲染模式，按现有风格最小插入；不重构）

### 7. 文档同步（architecture.md）
- 6.4 节需求状态机：补 Redis hash 存储、ask_user 工具、轮次/DEGRADED 闭环、降级行为
- 第 9 节 SSE 事件表：加 clarify_question 行

## 三、测试

1. **单测（无门控必跑）**：`RequirementToolsTest`——手写 ThrowingRedisTemplate（opsForHash 全抛）驱动 store 走内存降级，验证：update 写回+save、get_missing 摘要、ask_user 轮次 +1、**连续 3 次 ask_user 后 DEGRADED**、收齐后 DONE、非法字段拒绝
2. **Redis 集成（6379 端口门控）**：`TripRequirementStoreRedisTest`——真实 hash 读写回环、**HGETALL 断言 hash 字段结构**（检测标准 2 的 Redis 可见性）、TTL 存在、3 轮 DEGRADED、Redis 停机降级（连不上的端口）
3. **全量 mvn test 回归**（现有 58 测试零破坏）

## 四、E2E 检测（启动 PG + Redis + 应用，curl 带会话）

| # | 操作 | 预期 |
|---|---|---|
| 1 | 新会话发"帮我规划行程" | SSE 收到 clarify_question 事件（选择题式反问，缺目的地给"国内/国外/还没定"——标准 1/3） |
| 2 | 答"下周吧，两三个人，穷游" | `redis-cli HGETALL trip:req:{userId}:{sessionId}` 显示 people/budget/startDate 已写入；下轮反问不再问已答字段（标准 2） |
| 3 | 3 轮仍缺（如一直不答目的地） | 状态 DEGRADED、工作区出现 `intake_done.md`（含「待确认」标注）、master 放行进入拆分（标准 4） |
| 4 | 新会话一次说全（目的地/天数/日期/出发城市） | intake 0 反问（无 clarify_question 事件）直接 intake_done.md → 四维拆分登记（标准 5） |

## 五、明确不做

- master 直接反问路径（master 只做编排，语言能力全在 intake——维持 3.3 决策三的职责切分）
- reviewer/其余 SSE 新事件（agent_status/review_score 是后续迭代）
- 前端反问选项按钮交互（clarify_question 仅展示气泡；按钮化是前端迭代项）
