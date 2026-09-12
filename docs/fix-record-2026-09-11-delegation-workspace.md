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
