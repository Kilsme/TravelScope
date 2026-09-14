# TravelScope v3 架构骨架重构计划（6 Agent 编排，保留工具代码）

## 一、目标与边界

按需求文档 v3 搭建主/子 Agent 骨架：**Agent 常量类（名称 + maxIters + 系统提示词）+ AgentConfig 装配 + RequirementTools/需求状态机骨架 + ReviewerRetryMiddleware 空壳**。业务逻辑（反问话术调优、5 维评分解析、路线优化算法、回炉拦截、Redis 持久化）一律 TODO，本轮只保证结构与提示词完整、可编译。

**保留不动**：WeatherTool / HotelTool / AttractionTool / TransportTool / TaskTools 五个工具类、TaskRegistry、MCP 注册逻辑（12306/variflight）、5 个 SKILL.md、PlanningGateMiddleware、IntentClassifier、ChatService/ChatController/SSE、前端、pom。

## 二、最终架构（6 Agent，已经反编译 agentscope-harness 2.0.3 核验可行）

```
travel-master（HarnessAgent，maxIters=15，现有 Bean 改造）
│  中间件：IntentRouterMiddleware → PlanningGateMiddleware(-1000) → ReviewerRetryMiddleware(-900，空壳透传)
│  共享 Toolkit：现有 5 工具类 + TaskTools + RequirementTools(新) + MCP
│
├─ subagent: intake-agent        声明式叶子，maxIters=6，tools 白名单=[get_missing_fields, update_requirement_state]
└─ subagentFactory: planning-agent  工厂手工构建（非叶子），maxIters=12
      ├─ subagent: poi-research     声明式，maxIters=6，skills=[attraction-search]
      ├─ subagent: route-optimizer  声明式，maxIters=6
      └─ subagent: reviewer-agent   声明式，maxIters=6
```

关键依据（字节码核验结论）：
- SubagentDeclaration 声明的子 Agent 被框架强制 `asLeafSubagent()`（无 agent_spawn），故 Planner 走公开 API `subagentFactory(name, description, Function<String,Agent>)` 手工构建——工厂返回的 Agent 零包装、非叶子，build 时自带 SubagentsMiddleware，能 spawn 自己声明的 3 个子 Agent；master(0)→planner(1)→poi(2) 深度 ≤ MAX_SPAWN_DEPTH=3，合法。
- 工厂每次调用 build 一个新 Planner（与现有 IntentClassifier 每次新建 ReActAgent 同款模式）；`HarnessAgent.build()` 对 toolkit 做 copy（浅拷贝共享工具实例），travelToolkit Bean 不被污染，MCP npx 进程不会重复拉起。
- 子 Agent ctx 经 `RuntimeContext.builder(parentCtx)` 拷贝 stringAttributes，master 注入的 `travelscope.collab.dir` 等键对 Planner 可见。

## 三、文件清单

### 新建 8 个

| 文件 | 内容 |
|---|---|
| `agent/IntakeAgent.java` | 常量类（对齐 TravelMasterAgent 现有格式）：AGENT_NAME="intake-agent"、MAX_ITERS=6（FR-S02）、SYS_PROMPT 骨架：调 get_missing_fields 判缺项 → 缺则反问（能选择题不文本、有默认值自动填、听不懂换问法，≤3 轮）→ 用户回答理解后 update_requirement_state 写回 → 收齐写 intake_done.md 并返回"信息已收齐"；3 轮仍缺标注「待确认」放行（DEGRADED） |
| `agent/PoiResearchAgent.java` | AGENT_NAME="poi-research"、MAX_ITERS=6（FR-S06）、SYS_PROMPT 骨架：目的地+天数+偏好 → 检索景点（MVP 用 searchAttractions/searchNearbyAttractions；RAG+ES 双路检索 TODO 待 FR-S11）→ 多轮筛选（召回不足换关键词重查）→ 产出 poi_shortlist.md（坐标/开放时间/建议时长/推荐理由） |
| `agent/RouteOptimizerAgent.java` | AGENT_NAME="route-optimizer"、MAX_ITERS=6（FR-S07）、SYS_PROMPT 骨架：读 poi_shortlist → 两两调 getDrivingRoute/getTransitRoute → 就近聚类分日、开放时间不冲突、单日通勤≤90min → 发现不顺调整再算（迭代）→ 产出 route_plan.md |
| `agent/ReviewerAgent.java` | AGENT_NAME="reviewer-agent"、MAX_ITERS=6（FR-S08，文档未给具体值取对称值）、SYS_PROMPT 骨架：5 维评分（完备性/可行性/时间冲突/费用预算/POI 合理性，各 20 分）→ 可调工具核验事实（重查距离/天气）→ 总分≥80 且无单维<12 写 review_passed.md，否则写 review_report.md（每维得分+扣分项+改进建议） |
| `agent/ReviewerRetryMiddleware.java` | implements MiddlewareBase 空壳：onActing 等全部透传 + `// TODO: 解析 review_report.md 评分，不通过时拦截"返回用户"并替换为"继续委派 Planner"（≤2 次）`；order()=-900（PlanningGate(-1000) 内层、SubagentsMiddleware(1) 外层） |
| `agent/tools/RequirementTools.java` | @Tool 骨架（格式对齐 TaskTools，userId 经 RuntimeContext 注入）：`get_missing_fields(sessionId, ctx)` 委托 store 返回缺失必填字段；`update_requirement_state(sessionId, fieldsJson, ctx)` fastjson2 解析后写回 store。必填=destination/days/startDate/fromCity，选填=budget/preference/people/special |
| `dto/TripRequirementState.java` | 状态机数据类（文档 3.3）：8 字段 + status 枚举 COLLECTING/DONE/DEGRADED + 反问轮次计数 + missingFields() 计算（纯字段判空，非业务逻辑） |
| `service/TripRequirementStore.java` | 内存版骨架：ConcurrentHashMap 按 `userId:sessionId` 双键隔离（对齐 TaskRegistry 模式）；`// TODO: 迁移 Redis hash trip:req:{userId}:{sessionId}（FR-S02）` |

### 修改 4 个

1. **`TravelMasterAgent.java`** — SYS_PROMPT 更新为 v3 编排：PLANNING 流程改为「委派 intake-agent 收口需求 → create_task_backlog → 委派 planning-agent（其内部闭环 poi/route/reviewer）→ 收最终方案整合输出」；删除"关键信息不足先自己追问"的旧规则（职责移交 intake-agent）；硬约束、输出风格保留。
2. **`ItineraryAgent.java`** — SYS_PROMPT 重写为 v3 Planner：直调工具（天气与天数对齐 / 酒店+预算过滤 / 车票 MCP / POI 初查）→ 同回合并行 spawn poi-research + route-optimizer → 收齐组装 itinerary_draft.md + POI 坐标核验（geocode，偏差>50m 重查）→ spawn reviewer-agent 质检，不通过按 review_report.md 改进重提（≤2 次）；MAX_ITERS 8→12。
3. **`AgentConfig.java`** — 装配改造（核心）：
   - 新增 `TripRequirementStore` Bean；travelToolkit 注册 `new RequirementTools(store)`；
   - master builder：`.subagent(intake声明)` + `.subagentFactory(ItineraryAgent.AGENT_NAME, "v3 规划 Agent：直调工具+并行子 Agent+质检闭环", name -> buildPlannerAgent(toolkit, dashscopeChatModel))` + middlewares 追加 ReviewerRetryMiddleware；
   - 新私有方法 `buildPlannerAgent(toolkit, model)`：手工构建非叶子 Planner（HarnessAgent.builder：name/sysPrompt/model+modelResolver/toolkit/maxIters=12/BYPASS/workspace/skillRepository/InMemoryStateStore/.subagents(List.of(poi, route, reviewer))，三个声明均 SHARED 工作区，poi 带 skills=[attraction-search]）；
   - TaskWorkspaceService 内部类：追加 5 个文件常量 `intake_done.md / poi_shortlist.md / route_plan.md / review_report.md / review_passed.md`，session_meta 参与者列表更新为 6 Agent；
   - 类头 javadoc 架构注释更新为 6 Agent 树。
4. **`IntentRouterMiddleware.java`** — 仅改 `planningDirective` 注入文本：PLANNING 指令流程前插「第一步委派 intake-agent 收口需求，收到"信息已收齐"后再登记任务清单」；拦截/注入逻辑代码不动。

## 四、验证

1. `mvn compile`（Git Bash 下于 D:\TravelScope 执行）编译通过；
2. 结构自检：确认 master 挂 intake 声明 + planning 工厂、Planner 挂 3 声明、RequirementTools 注册进 travelToolkit；
3. 可选（环境变量 API_KEY 就绪时）：启动应用看 6 个 Agent 构建日志，不跑端到端对话。

## 五、明确不做（后续迭代，对应文档 9.2 顺序）

- 意图三层级联 L0~L2（FR-S01，改造 IntentRouterMiddleware 规则表 + IntentClassifier 退化兜底）
- SSE 新事件 clarify_question / agent_status / review_score 与前端适配
- ReviewerRetryMiddleware 拦截逻辑、评分解析、回炉计数
- Redis 状态持久化（AgentStateStore 与 TripRequirementStore）、LLM Gateway、RAG 双路检索
