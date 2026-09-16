
## 架构约定（2026-09-14 定）
- AgentScope 项目设计第一原则「克制」：能工具不 Agent、能代码不提示词、能确定性不调模型；新增组件前先过 Agent 资格测试（多步推理/自主选工具/失败换路 ≥2 条才配 Agent）。
- 当前生效需求文档：docs/需求文档-v3.md（v3.1，6 Agent：master/intake/planner/poi-research/route-optimizer/reviewer；intake 为混合形态——代码工具判缺项、Agent 负责问与听）；v1/v2 保留作演进对比。
- 开发计划：D:\TravelScope-开发计划.md（D 盘根目录，非项目目录），AI vibecoding 模式，P0~P9 十阶段，标准 15 工作日/压缩 2 周。
- v3.2（2026-09-16）：对照 Go 参考项目补强——FR-S08 分段审核+按段局部回炉、FR-S14 TaskResultCache、FR-S15 EvalCase；PromptIter 与三级审核前置留 P2。开发计划 v1.1：标准版 16.5 工作日。
