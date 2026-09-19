# route-optimizer 落地计划（FR-S07）

## 一、现状与缺口（调研结论）

**已存在**：RouteOptimizerAgent 常量类（提示词骨架完整：调优目标三条/工作流程/格式/约束）、AgentConfig 声明（SHARED 工作区）、planner 提示词第 2 步（查 route 缓存→spawn→登记）、TaskWorkspaceService.FILE_ROUTE_PLAN 常量、TaskResultCache.ROUTE 类型——**但全链路从未真实跑通**（workspace 无任何 route_plan.md，上轮 E2E 只推进到 poi_shortlist）。

**七个缺口**（本轮全部修复）：
1. 提示词未提 **duration 单位是秒**（工具原样返回高德秒值，格式样例却写"15 分钟"——不换算会全错）
2. 声明缺 skills/tools 白名单（对比 poi 有 attraction-search）
3. 提示词引用 attraction-search 作工具事实源，但该技能文件不含路线工具内容
4. **register(route) 依赖 planner 自觉**——poi 同款模式已实测翻车（7.10 节缺口①：planner 让 poi 干完活自己没登记）
5. planner 分轮截断风险（7.10 节缺口②）
6. poi_shortlist 的开放时间/时长是非结构化文本（嵌在引用片段里）
7. 坐标格式 `经度,纬度` 与路线工具入参天然对齐（无需处理，记录确认）

## 二、改动清单（4 个文件，全部小改）

### 1. `agent/RouteOptimizerAgent.java` —— SYS_PROMPT 重写（核心）

- **工具格式说明**：明确 `getDrivingRoute/getTransitRoute 返回 duration 单位为秒、distance 单位为米——写进 route_plan 前换算为分钟/公里`；坐标入参格式 `经度,纬度`（与 poi_shortlist 一致直接用）
- **迭代日志锚点（检测标准 4）**：要求每次「检验→调整」在 route_plan.md 的「调优过程」小节留痕：`迭代 1：初排后检验发现 河坊街→灵隐寺→西湖 存在折返（通勤 48 分钟）→ 调整为 西湖→灵隐寺→河坊街`；同时推理文本中的排序/算距/调整过程自然留在 AgentTrace 日志
- **自登记（修缺口 4）**：产出 route_plan.md 后**自己调** `register_task_result(taskType=route, content=全文)`——route-optimizer 有该工具（共享 Toolkit），登记职责从 planner 下沉到产出者，不依赖 planner 自觉
- **开放时间提取**：从 poi_shortlist 的引用文本中提取开放时间/建议时长（非结构化→排序依据），提取不到的标「时间待确认」并按默认 09:00-17:00 处理
- **分日策略具体化**：给出可操作的聚类方法（按坐标经度/纬度粗分组或按行政区域），避免模型空谈"就近聚类"
- **修正工具事实源引用**：删掉"以 attraction-search 为准"（与路线工具无关），工具细节直接内联在提示词

### 2. `agent/ItineraryAgent.java` —— planner 第 2 步微调

route 段落改为：spawn route-optimizer（先查 route 缓存，命中复用）；**登记由 route-optimizer 自完成**（提示词已约束），planner 只需在读到 route_plan.md 后继续第 3 步——去掉 planner 侧的 register(route) 指令（避免双重登记），并补一句"若 route_plan.md 未生成且 route 缓存未命中，重新 spawn route-optimizer"（分轮截断的自愈提示）。

### 3. `config/AgentConfig.java` —— route-optimizer 声明补白名单

`.tools(List.of("getDrivingRoute", "getTransitRoute", "geocode", "read_file", "write_file", "register_task_result", "get_cached_task_result"))`——限定排线所需工具面（对齐 intake 的 tools 模式；防误调 searchPois 等越权）。

### 4. `resources/skills/` —— 新增 `route-planning/SKILL.md`

路线工具使用手册（getDrivingRoute/getTransitRoute 参数与返回格式、秒/米单位说明、两两调用策略）——route-optimizer 声明挂 `.skills(List.of("route-planning"))`，作为工具细节的事实源（填补缺口 3，与 poi 的 attraction-search 对称）。

## 三、测试（无新单测——逻辑全在提示词与声明层）

- 现有 104 测试全量回归（确认提示词改动/白名单无编译与行为破坏）
- E2E 为主要验证（检测标准 1~4）

## 四、E2E 检测标准 1~4 执行方案

环境：PG → Docker Redis+ES → 应用（seed 已在库）→ curl。

| # | 操作 | 验证 |
|---|---|---|
| 1 | 复用 conv-44（poi_shortlist 已在）或新会话完整规划 | `route_plan.md` 生成：读文件断言分日结构 + 表格含通勤方式/时长列 |
| 2 | 读 route_plan.md 中各 POI 坐标（对照 poi_shortlist） | 人工/脚本验证同日 POI 坐标聚簇（无城东→城西→城东折返） |
| 3 | route_plan.md 每日总通勤 + 日志 duration 换算 | 各日 Σ通勤 ≤ 90 分钟 |
| 4 | 日志 grep route-optimizer 的推理轨迹 + route_plan.md 的调优过程小节 | 可见「排序→算距→发现不顺→调整」至少一次迭代 |

E2E 推进策略（吸取上轮教训）：分轮截断时用同会话「继续」指令推进 planner；若 planner 仍不 spawn route，用明确指令「委派 route-optimizer 排线」直达。

## 五、明确不做

- Reviewer 回炉联动（route 缓存的局部回炉消费路径，待 ReviewerRetryMiddleware 落地）
- planner 分轮截断的代码层兜底（7.10 节缺口②的系统修复——影响面是全流程非 route 单点，单独需求处理）
- 通勤计算的代码层强校验（如 middleware 拦截 write_file 校验 ≤90min——过度工程，提示词+检测先行）

## 六、文档与收尾

- architecture.md：第 7 节 poi/route 提示词描述同步（工具白名单、自登记模式）；第 12 节 TaskResultCache 描述补 route 自登记差异
- fix-record：7.11 节「route-optimizer 落地」——记录自登记 vs planner 登记的决策（poi 翻车教训的直接应用）、单位换算缺口、E2E 检测结果
- 检测完成后关闭全部后台程序（应用/Docker Redis+ES/PG 服务）