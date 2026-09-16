# 修复记录：主 Agent 委派时序与子 Agent 工作区隔离（2026-09-11）

> 本文记录「规划任务委派后子 Agent 读不到 task_backlog.md」问题的根因分析与修复明细，
> 所有改动均已实测验证。后续的代码层强化（任务容器 + 委派门禁）见 `architecture.md` 第 6 节。

## 1. 现象

规划对话（如「帮我规划成都两日游，预算3000元」）中，planning-agent 启动后立即报错并脱离任务流程：

```
14:14:28  [planning-agent] read_file("task_backlog.md") → result_len=41（内容为"文件不存在"）
14:14:30  [planning-agent] list_files → 当前目录下没有 task_backlog.md
14:14:32  [planning-agent] "根据系统指令，我需要先创建任务清单" ← 开始自行编造任务，脱离主 Agent 的拆分
14:14:34  [travel-master]  "已成功委派 planning-agent（task_id: …），当前状态为 running"
```

主 Agent 明明在委派后写了清单（15:33:42「task_backlog.md 已精准更新」），子 Agent 却从未读到。

## 2. 根因（两层叠加）

### 根因 1：委派时序无强制约束 + agent_spawn 是异步的

- 旧提示词（TravelMasterAgent 第三节）只有一句「将任务清单写入 task_backlog.md，委派 planning-agent 执行」，
  写清单与委派合并在一个步骤里，模型（qwen-plus）实际执行时**先调了 agent_spawn**（它把委派当成了主动作）
- harness 的 `agent_spawn` 是**异步**的：调用立即返回 task_id 与「状态 running」，子代理随即启动；
  主 Agent 之后才慢悠悠用 write_file 补写清单——但子代理早已读取失败并开始即兴规划
- 子代理侧也没有 fail-fast 协议：读不到清单时选择「自己创建任务清单」，而不是立即上报缺失

### 根因 2：子代理工作区被隔离（真正的硬伤）

即使时序正确，子代理也读不到文件。实测磁盘布局揭示了原因：

```
主 Agent write_file("task_backlog.md") 落盘在：
    .agentscope/workspace/2/task_backlog.md          ← workspace 根 + 用户目录

子代理（默认 WorkspaceMode.ISOLATED）的文件根是：
    .agentscope/workspace/agents/planning-agent/     ← 独立隔离目录！
它读 task_backlog.md 实际解析到：
    .agentscope/workspace/agents/planning-agent/workspace/2/task_backlog.md   ← 不存在
```

`SubagentDeclaration` 默认 `WorkspaceMode.ISOLATED`——子代理被隔离到 `agents/{name}/` 子目录，
与主 Agent 的文件互不可见。日志中子代理说「项目根目录中也没有找到」正是两套路径根不一致的表现。

## 3. 修复内容（3 个文件，前后对照）

### 3.1 AgentConfig.java —— 子代理共享工作区（核心修复）

```java
// ===== 修复前 =====
SubagentDeclaration planningSubAgent = SubagentDeclaration.builder()
        .name(ItineraryAgent.AGENT_NAME)
        .description("规划 Agent（行程规划师）…")
        .inlineAgentsBody(ItineraryAgent.SYS_PROMPT)
        .model(appProperties.getDashscope().getModel())
        .maxIters(ItineraryAgent.MAX_ITERS)
        .skills(List.of("weather-query", "hotel-search", "attraction-search",
                        "train-ticket-query", "flight-ticket-query"))
        .build();                                        // ← workspaceMode 缺省 = ISOLATED

// ===== 修复后 =====
SubagentDeclaration planningSubAgent = SubagentDeclaration.builder()
        .name(ItineraryAgent.AGENT_NAME)
        .description("规划 Agent（行程规划师）…")
        .inlineAgentsBody(ItineraryAgent.SYS_PROMPT)
        .model(appProperties.getDashscope().getModel())
        .maxIters(ItineraryAgent.MAX_ITERS)
        .workspaceMode(WorkspaceMode.SHARED)             // ← 与主 Agent 共享工作区
        .build();
```

> API 依据：`io.agentscope.harness.agent.subagent.WorkspaceMode` 枚举仅有 ISOLATED / SHARED 两个值，
> Builder 提供 `.workspaceMode(...)` 方法（javap 验证）。SHARED 后子代理的文件根与主 Agent 一致，
> `read_file("task_backlog.md")` 解析到同一用户目录。

### 3.2 TravelMasterAgent.java —— 规划流程改为强制顺序步骤

```java
// ===== 修复前（第三节，时序无约束）=====
3. 将任务清单写入 task_backlog.md，委派 planning-agent 执行

// ===== 修复后 =====
3. 先写清单：用 write_file 把任务清单写入 task_backlog.md，必须等到该工具返回成功才算完成
4. 后委派：仅在 task_backlog.md 写入成功之后，才能调用 agent_spawn 委派 planning-agent；
   任务说明中注明「任务清单已写入 task_backlog.md，请先用 read_file 读取后执行」。
   若子 Agent 反馈找不到任务清单：先确认文件已写入成功，再重新委派，不要让它空跑
```

同时在 IMPORTANT 硬约束中追加：

```
- 委派规划 Agent 之前，必须已用 write_file 成功写入 task_backlog.md：
  子 Agent 被启动后会立即读取该文件，文件缺失会导致它错误地自行规划
```

### 3.3 ItineraryAgent.java —— 子代理 fail-fast 协议

```java
// ===== 修复前 =====
1. task_backlog.md —— 主 Agent 写入的任务清单
   - 读取它，按优先级（P0 → P1 → P2）逐条执行

// ===== 修复后 =====
1. task_backlog.md —— 主 Agent 写入的任务清单
   - 接到任务后的第一步：用 read_file 读取它（相对路径 task_backlog.md），按优先级逐条执行
   - 若文件不存在或没有实质内容：立即结束，把「任务清单缺失，请主 Agent 先用 write_file 写入
     task_backlog.md 后重新委派」作为执行结果返回；严禁自行创建任务清单、凭空规划或改用其他文件
```

## 4. 验证证据

修复编译重启后，用新规划请求（西安三日游）实测：

1. **工具事件顺序**（SSE tool 事件）——「先写后委派」生效：

```
write_file → write_file:SUCCESS → read_file → read_file:SUCCESS → … → agent_spawn → agent_spawn:SUCCESS
```

2. **子代理读取结果对比**：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| read_file result_len | 41（not-found 消息） | **769（真实清单内容）** |
| 子代理行为 | 自行编造任务清单开始瞎跑 | 「已成功读取 task_backlog.md，确认任务清单存在且结构完整」 |
| 后续执行 | 偏离主 Agent 拆分 | 按清单逐项执行（T2 酒店 10 家、T3 兵马俑等景点查询全部成功） |

3. **fail-fast 也验证通过**：工作区 SHARED 生效前的一次对照实验中，子代理读不到文件时
   立即返回「任务清单缺失，请主 Agent 先写入后重新委派」并结束，没有即兴规划。

## 5. 遗留与后续强化

本次修复靠提示词约束顺序，仍存在模型不自觉的窗口。已在 2026-09-12 的后续改造中闭环
（详见 `architecture.md` 第 6 节）：

- 任务清单必须经 `create_task_backlog` 工具登记进任务容器（内存队列 + 隔离 MD 文件），
  禁止用 write_file 代替
- `PlanningGateMiddleware` 在 `onActing` 阶段拦截 `agent_spawn`：容器中无清单时直接把委派调用
  替换为纠正提示工具，委派在代码层无法绕过
- 协作文件迁移到用户+会话双级隔离路径 `{workspace}/{userId}/tasks/{sessionId}/`
- 主 Agent 可用 `get_task_progress` 查询未完成任务数；子 Agent 用 `update_task_status` 逐项回报

## 6. 环境注意事项

- 本机 `mvn spring-boot:run` 存在类路径问题（agentscope 类加载失败），用
  `java -cp "target/classes;依赖" com.travelscope.TravelScopeApplication` 启动正常；IDEA 内启动不受影响
- 打包为 Spring Boot fat jar 启动时，harness 的文件写入不落盘（classpath 启动正常），部署时需留意

## 7. v3 架构重构对本文机制的演进（2026-09-14 追记）

> 2026-09-14 按需求文档 v3 完成主/子 Agent 骨架重构（6 Agent 编排，工具代码原样保留）。
> 本节说明本文记录的两项机制在 v3 下的延续与变更，历史记录（第 1~6 节）保持原样。
> 现行架构详见 `architecture.md` 第 7 节。

### 7.1 WorkspaceMode.SHARED 的延续

本文的核心修复（子代理共享工作区）在 v3 全面继承：全部 5 个子 Agent 声明
（intake-agent / poi-research / route-optimizer / reviewer-agent，以及工厂内构建的
planning-agent）均显式 `workspaceMode(WorkspaceMode.SHARED)`，协作文件收敛在
`{workspace}/{userId}/tasks/{sessionId}/`（v3 新增 intake_done.md / poi_shortlist.md /
route_plan.md / review_report.md / review_passed.md 5 个文件）。

### 7.2 planning-agent 注册方式的变更（声明式 → 工厂）

v3 要求 Planner 作为二级编排者 spawn poi-research / route-optimizer / reviewer-agent，
但框架核验发现（agentscope-harness 2.0.3 反编译）：`SubagentDeclaration` 声明的子 Agent
被强制 `asLeafSubagent()`——toolkit 无 `agent_spawn`，不能再委派。因此 planning-agent
从本文 3.1 节的声明式注册改为 `subagentFactory(name, description, factory)` 公开 API：
工厂内用 `HarnessAgent.builder()` 手工构建（非叶子），能 spawn 自己声明的 3 个子 Agent，
spawn 深度 2 ≤ 框架上限 3。

注意：本文 3.1 节的「修复后」代码片段是 **2026-09-11 当时的历史状态**（声明式 + SHARED），
v3 起该写法已不适用于 planning-agent，但 intake-agent 等叶子子 Agent 仍是同一声明式写法。

### 7.3 PlanningGateMiddleware 新增 intake-agent 豁免

v3 流程中需求收集发生在任务登记**之前**（需求文档 4.2 步骤 [5]/[6]：master 先委派
intake-agent 反问收口，收齐后才 create_task_backlog）——若门禁不加区分，intake 的
spawn 必然被误杀（此时清单必然未登记）。因此门禁逻辑更新为：读取 `agent_spawn` 调用参数
中的 `agent_id`，仅对 `intake-agent` 之外的目标执行「未登记即拦截」；intake 的 spawn
即使与受门禁的 spawn 同轮出现也原样放行。

### 7.4 后续迭代（未落地，见需求文档 v3 §9.2）

- ReviewerRetryMiddleware 目前为空壳透传（order=-900 占位），回炉拦截逻辑待实现
- TripRequirementStore 为内存版，待迁移 Redis hash `trip:req:{userId}:{sessionId}`
- 意图三层级联已于 2026-09-16 落地（见 7.5 节）；SSE 新事件（clarify_question /
  agent_status / review_score）、RAG 双路检索仍为后续迭代项

### 7.5 意图三层级联落地（2026-09-16 追记，FR-S01）

> 意图识别从「每条消息必调 qwen-plus 单层分类」升级为三层级联，与本文主题
> （委派时序/工作区）同属应用层编排机制，追记于此。完整设计见 `architecture.md` 第 5 节。

**结构**：`IntentCascadeRouter`（新入口，ChatService.resolveIntent 改接）——
L0 会话延续（短追问 + Redis 30min 最近意图，0 计算）→ L1 保守正则规则表
（yml `travelscope.intent-cascade.l1-rules` 可配置，未配置用代码内置默认；首个命中即返回）→
L2 qwen-turbo 单标签 + Redis 文本缓存（sha256 键，60min）→ L3 现有 IntentClassifier
方法引用包装（零改动兜底）。每层命中即短路、写回 L0（TTL 滚动）、打
`cascade_hit=L{n} latency={x}ms` 埋点日志。

**L1 刻意保守**（验收标准驱动）：规划类自然语言（「帮我规划杭州三日游」）不进 L1，
由 L2 语义判定——L1 只收编打招呼、`/规划`、`/天气` 命令前缀与极固定句式，
并有负向回归用例固定守护。

**实测验收**：「你好」/「/天气 北京」L1 命中 0ms 零模型调用；
「帮我规划杭州三日游」真实链路 L2 命中 270ms 判 PLANNING；
同会话追问「那改成四天呢」L0 命中 0ms。全量 49 测试 0 失败。

**两个根因排查记录**（对后续开发有复用价值）：

1. **qwen-turbo 不走结构化输出**：框架 `call(msg, Class)` 的结构化输出依赖
   metadata `_structured_output` 键，qwen-plus 能走通合成工具路径，但 qwen-turbo
   实测常把标签（如 `CHAT`）直接当纯文本输出，框架抛
   `No structured output in message metadata` 异常导致 L2 全部 miss。
   修复：LightweightIntentClassifier 双路径解析——结构化解析异常/为空后，
   对 textContent 做纯文本标签归一化（去引号句读 → IntentType.valueOf），
   双路皆失败才返回 null 落 L3。教训：**轻量模型接入 AgentScope 结构化输出
   前必须实测验证遵循度，纯文本标签兜底是低成本保险**。
2. **Router 层缓存防御缺失**：初期 Router 假设 IntentCache 实现永不抛异常
   （静默降级职责全押在 RedisIntentCache 实现里），ThrowingCache 单测暴露
   异常直接穿透级联链路。修复：Router 侧加 safe* 防御包装，与实现层降级
   形成双保险。教训：**接口实现方的容错约定不能替代调用方防御**。

**环境备注**：Redis 回环测试（RedisIntentCacheTest）按 6379 端口门控——本机
Redis 未运行时自动跳过，`docker-compose up -d redis` 后生效；Redis 不可用期间
级联自动降级为 L1→L2→L3（无缓存模式），不阻断对话。

### 7.6 PlanningGateMiddleware 豁免逻辑上线后的 ClassCastException（2026-09-16 追记）

> 7.3 节的 intake-agent 豁免上线首日，真实规划请求触发
> `String cannot be cast to [C` 导致 Agent 执行异常——根因是 AgentScope API
> 的一个泛型签名陷阱，记录于此防止复发。

**现象**：前端规划对话报「Agent 执行异常: class java.lang.String cannot be
cast to class [C」；堆栈顶在 `PlanningGateMiddleware.onActing`（豁免逻辑读
意图的行）。

**根因**：`RuntimeContext.get(String)` 的签名是 `<T> T get(String)`（泛型返回值，
无目标类型时 javac 需自行推断）。豁免代码写的：

```java
String intent = ctx.get(CTX_INTENT_KEY) != null
        ? String.valueOf(ctx.get(CTX_INTENT_KEY)) : null;   // ← 出事行
```

javac 的**目标类型推断**把 `<T>` 推断为 `char[]`，使 `String.valueOf(...)`
绑定了 `String.valueOf(char[])` 重载（而非预期的 `valueOf(Object)`）。反编译
字节码可见 `checkcast [C` + `String.valueOf([C)` 铁证；运行时存的是 String，
强转 char[] 即抛 ClassCastException。

**为什么骨架测试没拦住**：单测桩直接构造 IntentCache/LlmClassifier，未走
真实 `RuntimeContext.put/get` 链路；首次真实 PLANNING 请求进中间件链才触发。
教训：**读上下文的代码路径要有一条真实链路集成测试覆盖**。

**修复**：直接以 `String` 接收（泛型推断为 String，零强转）：

```java
String intent = ctx.get(IntentRouterMiddleware.CTX_INTENT_KEY);   // 直接接收
```

同步排查全仓，IntentRouterMiddleware.planningDirective 里 collabDir 的同款
三元写法一并修复（该处历史代码因推断路径不同暂未爆雷，但写法相同，属隐患），
两处均留注释说明陷阱。

**通用教训（对使用 AgentScope 泛型 API 的所有代码有效）**：
`<T> T` 返回值的方法（`ctx.get(key)` 等）**不要套 `String.valueOf(...)`**
——目标类型推断会把它绑定到 `valueOf(char[])` 重载；直接用具体类型变量接收。
验证手段：`javap -c` 检查产物无 `checkcast [C`。

**修复后验证**：级联相关 25 测试全过；重启应用真实请求复现——
`cascade_hit=L2 intent=PLANNING` → 门禁豁免放行 intake-agent →
intake-agent 调 get_missing_fields 反问出发日期，v3 规划链路（含 6.2 节任务
容器、7.3 节豁免、7.5 节级联）首次端到端跑通，无异常。
