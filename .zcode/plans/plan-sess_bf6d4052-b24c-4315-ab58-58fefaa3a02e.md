# poi-research 双路检索 + TaskResultCache 实现计划（FR-S06/S11 + FR-S14）

## 一、调研结论（决定实现形态）

1. **pgvector 语义路 100% 用 AgentScope 原生组件**：`DashScopeTextEmbedding`（rag-simple jar，Mono<double[]> embed）+ `PgVectorStore.builder()`（jdbcUrl/tableName/dimensions/COSINE，kNN search）+ `SimpleKnowledge.retrieve(query, RetrieveConfig)`——schema.sql 的 document_chunks 表结构已兼容。
2. **ES BM25 路必须自写**：原生 `ElasticsearchStore.search` 只做 kNN（字节码证据：仅 KnnSearch 调用，content 字段 index=false）；用 pom 已有的 `elasticsearch-java 9.0.2`（Rest5Client）自写 BM25 match 查询。
3. **RRF 融合必须自写**：全库无融合 API；公式 `score = Σ 1/(rrfK + rank)`，RagConfig 已有 fusion/rrf-k/vector-weight/fulltext-weight 配置。
4. **RagService 是零侵入替换点**（接口 isAvailable/retrieve 已就绪）；ES 配置段/index-mapping.json（ik_max_word/ik_smart + dense_vector 1024）已备但 IK 插件未装、索引未建、知识库空。
5. **用户决策**：灌内置杭州攻略种子数据（检测标准 1/2 真实可验）；装 IK 插件（按原设计）。

## 二、架构

```
poi-research 收到任务（目的地+天数+偏好）
  ├─ 第 0 步：调 search_pois_with_rag 工具（新）——双路检索入口
  │    RagServiceImpl.dualRetrieve(query, topK)
  │      ├─ 语义路：SimpleKnowledge.retrieve（DashScope embedding → pgvector kNN）→ List<Doc>（ranked）
  │      ├─ 关键词路：EsBm25Client.search（自写，ik 分词 BM25 match）→ List<Hit>（ranked）
  │      └─ RrfFusion.fuse（自写）：按 chunkId 去重，score=Σ w_i/(rrfK+rank_i)，输出融合 top-K
  │         → 返回带来源标记（RAG / ES / RAG+ES）的片段列表
  ├─ 高德补全：searchPois / searchNearbyAttractions 拿坐标/开放时间/评分（知识库片段缺的硬字段）
  ├─ 多轮筛选：召回不足 → 换关键词重查（提示词驱动 + 日志留痕）
  └─ 产出 poi_shortlist.md（POI 数量与天数挂钩：2 天 6~8 个）

planning-agent / 各子 Agent 产出文件时
  └─ TaskResultCache.register(userId, sessionId, taskType, content)   [POI/路线/酒店 30min，天气 10min]
  └─ 二次规划同需求 → cache_hit=poi_shortlist → poi-research 不重跑，直接复用缓存内容写文件
```

## 三、改动清单

### 新建 7 个主代码文件

1. **`service/RagServiceImpl.java`**（替换 Stub）：组合 `SimpleKnowledge`（pgvector 路）+ `EsBm25Client`（BM25 路）+ `RrfFusion`；`isAvailable()` = 两路至少一路客户端就绪（构造失败/不可达时降级为单路或不可用，照 Redis 降级风格首次 warn）；`retrieve(question, topK)` 走双路 RRF（RagConfig.mode 支持 dual/vector/fulltext）；同时提供 `dualRetrieveWithSource(query, topK)` 返回 `List<RetrievedFragment{content, source(RAG/ES/BOTH), score}>`（工具层用）。构建：`@Service`，构造注入 AppProperties + DataSource（jdbcUrl 从 spring.datasource 拼）。
2. **`service/EsBm25Client.java`**：elasticsearch-java 9.0.2 Rest5Client；`search(String query, int topK)` → BM25 match（content 字段，ik_smart search_analyzer）+ `ensureIndex()`（幂等建索引，读 resources/elasticsearch/index-mapping.json）；连接失败静默降级（isAvailable=false 时不参与双路）。
3. **`service/RrfFusion.java`**：纯静态工具——`fuse(List<Ranked>{id,content,score,source}...)` 按 rrfK 融合去重，权重 vector-weight/fulltext-weight 可选应用；带单测。
4. **`agent/tools/PoiRagTools.java`**（新 @Tool 集，注册进共享 Toolkit）：
   - `search_pois_with_rag(query, topK, ctx)`：调 RagServiceImpl.dualRetrieveWithSource，返回「片段 + 来源标记」文本（供 poi-research 第一轮召回与换词重查）
   - `get_cached_task_result(taskType, sessionId, ctx)` / `register_task_result(taskType, content, sessionId, ctx)`：TaskResultCache 的工具面（planner/poi/route 调用；产出文件后 register，回炉/二次规划前 get）
5. **`service/TaskResultCache.java`**：StringRedisTemplate，key=`taskresult:{userId}:{sessionId}:{taskType}`（v3 文档规范），value=产出内容+头部 task_id/时间戳元信息；TTL 按类型（POI 30min/路线 30min/酒店 30min/天气 10min）；命中打 `cache_hit={taskType}` 日志（检测标准 5 锚点）；照 TripRequirementStore 降级风格（首次 warn 后续 debug + 内存 fallback）；失效 API `invalidate(userId, sessionId, taskType|ALL)` 预留给 P7 按影响面失效。
6. **`config/KnowledgeIngestRunner.java`**：`ApplicationRunner` + `@Profile("seed")` 或配置开关 `travelscope.rag.seed-enabled`（默认 false，检测时开）——内置 ~25 条杭州/热门城市攻略片段（每条含 POI 名称/坐标/开放时间/建议时长/推荐理由的文本），启动时切块→DashScope embedding→双写 document_chunks（经 SimpleKnowledge.addDocuments）+ ES（ensureIndex + bulk index，content 可检索）→ 幂等（存在 chunk 跳过）。
7. **`dto/RetrievedFragment.java`**：content/source/score/chunkId record。

### 修改 4 个文件

8. **`agent/PoiResearchAgent.java` SYS_PROMPT**：检索策略改为「第 1 步先调 search_pois_with_rag 双路召回（知识库游记攻略，带 RAG/ES 来源）→ 评估召回 → 不足换关键词重查该工具 → 高德 searchPois 补坐标/开放时间硬字段 → 筛选（来源标记必填：RAG 命中 or ES 命中 or 高德）」；产出格式加「来源: RAG(chunk=xx) / ES(keyword=xx) / searchPois」；开始前调 `get_cached_task_result(poi)` 命中则直接复用并标注缓存来源。
9. **`agent/ItineraryAgent.java`**（planner 提示词）：spawn poi-research 前先调 `get_cached_task_result(poi)`——命中跳过 spawn（复用缓存内容写 poi_shortlist.md）；各子 Agent 完成后由 planner 统一 register（poi/route/weather/hotel 四类）。
10. **`config/AgentConfig.java`**：travelToolkit 注册 `new PoiRagTools(ragService, taskResultCache, …)`；poi-research 声明 skills 加无（attraction-search 已有），无需 tools 白名单（默认继承全部）；新增 TaskResultCache Bean。
11. **`service/RagServiceStub.java`**：保留类但去掉 @Service（Impl 接管；Stub 留作参考/测试）——或直接删除，采用**删除**（git 有历史）。ChatService L175 的 topK 硬编码顺手改 RagConfig.topK。

### 测试（4 个新测试类，复刻现有风格）

12. **`RrfFusionTest`**（纯单测）：双路去重融合/权重/rrfK 参数/单路退化/空输入。
13. **`TaskResultCacheTest`**（ThrowingRedisTemplate 降级单测 + 6379 门控回环）：register/get 回环、TTL 差异（天气 10min vs POI 30min）、cache_hit 日志语义、invalidate、降级不阻断。
14. **`PoiRagToolsTest`**：Fake RagService（可控返回）验证工具返回格式（来源标记）与 get/register 工具链。
15. **`RagServiceImplTest`**（PG+ES 门控，检测环境跑）：双路真实检索 + RRF 融合 + 种子数据召回「杭州 西湖」类 query。

## 四、环境与检测执行序

1. 启动 PG → psql 跑 schema.sql（幂等，确保 documents/document_chunks/HNSW 就绪）
2. 启动 ES 容器 → 装 IK 插件（elasticsearch-plugin install + restart，按 README）→ init-index.sh 建索引
3. 启动 Redis → 应用（seed-enabled=true）→ Runner 自动灌种子数据（embedding 真实调 DashScope，~25 条）
4. **检测标准 1~5**：
   - 1. 规划「杭州 2 日游」→ 工作区 poi_shortlist.md 含 6~8 POI（坐标/开放时间/时长/理由）
   - 2. 每个 POI 来源标记（RAG/ES/高德）
   - 3. 召回不足日志可见换词重查（构造冷门 query 观察，或检查提示词多轮行为日志）
   - 4. 2 天不给 20 个点（提示词规模约束 + 检查产出数量）
   - 5. 同会话二次规划（需求未变）→ 日志 `cache_hit=poi_shortlist`、poi-research 无 spawn 调用记录
5. 全量 mvn test 回归（85+ 测试零破坏）
6. 文档：architecture.md（6.4 后新增 RAG 专节 + 工具表加 search_pois_with_rag/get_cached_task_result + poi-research 节更新）；fix-record 7.10 节（框架核验发现：ElasticsearchStore 纯 kNN 无 BM25、RRF 自写、seed 方案）
7. 关闭全部后台程序

## 五、明确不做

- 文档上传管理（FR-A03 管理端，种子 Runner 只是数据通道）
- P7 局部回炉的 ReviewerRetry 联动（TaskResultCache 只打底：register/get/invalidate API 就绪，回炉路由待 ReviewerRetryMiddleware 实装时接）
- ES 向量路（ES 只做 BM25；向量在 pgvector——避免双写向量开销）
- memory 版 InMemoryStore（不引入测试专用 store，Fake 注入即可）
- spring-data-elasticsearch 版本混用风险处理：本次只用 elasticsearch-java 原生客户端，不触 spring-data API（保持现状不扩大）