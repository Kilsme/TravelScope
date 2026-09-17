# LLM Gateway 实现计划（FR-S09）

## 一、关键调研结论（决定方案形态）

1. **装饰器可行且是框架认可的模式**：`Model` 接口仅 2 个抽象方法（`stream`/`getModelName`）+ 3 个 default 方法；框架自己的 `fallbackModel` 实现就是 `implements Model` 的包装器（`ReActAgent$2`）。**必须**：`implements Model`（勿继承 ChatModelBase——其 `stream` 是 `public final`）；全部 5 个方法委托；熔断/超时用 `Flux.defer`/`transformDeferred` 包在流上（冷流订阅时才生效）。
2. **错误面**：DashScope 401（错 Key）与 invalid model 都抛 `DashScopeHttpException`（RuntimeException，nested 类）——**不实现 ModelHttpException，框架原生重试不认它**，这是自建 Resilience4j 层的正当性。
3. **fallback 不能改 GenerateOptions.modelName**（DashScope 忽略它）——必须持有两个模型实例（qwen-plus + qwen-turbo）在装饰器内切换。框架原生 `fallbackModel` 只处理「首个信号是错误」，中途失败不切——我们的装饰器用 `onErrorResume` 做更强的中途 fallback（丢弃已发块重流）。
4. **模型收口点**：master/planner/modelResolver/4 个声明式子 Agent 全部收敛到 `dashscopeChatModel` Bean 一个实例——**包一个 Bean 全链路生效**。L2/L3 意图分类（turbo/plus）也经这两个 Bean。
5. **单用户=全局**：项目无认证，所有请求共享 guest userId——「单用户 Semaphore(2)」实际就是全站并发上限 2；检测标准 1 用 3 个并发 HTTP 请求即可验证。
6. resilience4j 不在 Boot 3.3.5 BOM 中——需显式版本（2.2.0）。
7. 前端 `case 'error'` 直接渲染文案——降级文案零前端改动。

## 二、架构设计

```
请求 → ChatService.streamChat
         │ ChatService 层准入（对话粒度）
         ├─ 全局 Semaphore(50) + 单用户 Semaphore(2)：tryAcquire(等待=配置超时)，超时/被拒 → SSE error 降级文案
         ├─ 滑动窗口 QPS（全局，Resilience4j RateLimiter，每秒 N 个对话准入）
         ├─ 快慢泳道（对话粒度）：TOOL_CALL/RAG/CHAT → 快（模型单次调用超时 5s）；PLANNING → 慢（60s）
         │   （泳道超时参数通过 RuntimeContext ctx 键传递给模型装饰器——ctx 在 modelResolver 处不可见，
         │    故泳道超时实现在「模型装饰器按模型实例」：主链 plus 30s/规划走同一 Bean——简化为：
         │    对话粒度超时在 ChatService 层用 Mono/Flux.timeout 实现，快 5s 慢 60s，不侵入模型层）
         ▼
       Agent 全链路 → modelResolver → LlmGatewayModel（装饰器，模型调用粒度）
         ├─ Resilience4j CircuitBreaker：30s 滑动窗口失败率>50% → OPEN 10s → HALF_OPEN 探测
         ├─ 单次模型调用超时 30s（Flux.timeout，检测标准 3 用 15s 可配）
         └─ 失败 fallback：log.warn("fallback → qwen-turbo") → 切 turbo 实例重流（onErrorResume，含中途失败）
SSE error 兜底文案（ChatService.handleError / runAgent.onErrorResume 双路）
```

**职责切分**：对话粒度（Semaphore/QPS/泳道超时）在 **ChatService 层**（新 `LlmGateway` 组件，纯 Java+R4j，不依赖 AgentScope）；模型调用粒度（熔断/单次超时/fallback）在 **`LlmGatewayModel implements Model` 装饰器**（AgentConfig 包装 Bean）。两层解耦，各自可测。

## 三、改动清单

### 新建 4 个主代码文件

1. **`service/LlmGateway.java`**（对话准入层）：`tryAcquire(userId)` 返回 `AcquireResult{allowed, reason}`——全局 Semaphore(50) `tryAcquire(2s)` → 失败返回「当前排队人数较多，请稍后再试」；单用户 Semaphore(2) `tryAcquire(1s)` → 失败返回「您的请求处理中，请等上一条完成后再发」；全局 RateLimiter（滑动窗口，默认 10 QPS）→ 失败返回「请求过于频繁，请稍后再试」；`release(userId)` 在对话结束（done/error 回调）释放。埋点日志 `gateway_admit/reject reason=... latency=xms`。
2. **`agent/LlmGatewayModel.java`**（模型装饰器）：`implements Model`，5 方法全委托；`stream()` = `Flus.defer` → 熔断 `transformDeferred(CircuitBreakerOperator)` → `Flux.timeout(单次30s)` → `onErrorResume(e → log.warn("fallback → qwen-turbo, reason={}") + fallbackModel.stream(...))`（fallback 再失败则原样抛出，由 ChatService SSE error 兜底）。能力方法委托 activeModel（AtomicReference，切换后反映 fallback——对齐 ReActAgent$2 模式并修其 supportsNativeStructuredOutputWithTools 缺陷）。构造参数 `(Model primary, Model fallback, CircuitBreaker cb, Duration timeout)`。
3. **`config/AppProperties.java`**：新增 `LlmGatewayConfig` 嵌套类——`enabled=true / globalConcurrency=50 / perUserConcurrency=2 / globalQps=10 / acquireTimeoutMs=2000 / fastLaneTimeout=5s / slowLaneTimeout=60s / modelTimeout=30s / cb slidingWindowSize=10 / failureRateThreshold=50 / waitDurationOpen=10s / fallbackEnabled=true`。
4. **`application.yml`**：`travelscope.llm-gateway` 段（显式写全，注释对齐 FR-S09）+ pom.xml 加 `resilience4j-reactor` + `resilience4j-circuitbreaker`（2.2.0，属性区+依赖区按现有分组风格）。

### 修改 3 个文件

5. **`AgentConfig.java`**：`dashscopeChatModel` Bean 改为返回**装饰后的 `LlmGatewayModel`**（返回类型改 `Model`，内部 build 真实 plus 实例 + 注入 turbo fallback + 从 `CircuitBreakerRegistry` 取/建熔断器；`@Primary` 保留）。下游影响：`travelMasterAgent`/`buildPlannerAgent`/`intentClassifier` 参数类型 `DashScopeChatModel` → `Model`（Builder 接受 Model）；`modelResolver(name -> model)` 同步。turbo Bean 保持裸实例（L2 轻量调用 + 作 fallback 目标，自身不套熔断——fallback 已是降级终点）。
6. **`ChatService.java`**：构造器注入 `LlmGateway`；`streamChat` 在 `flatMapMany` 前 `gateway.tryAcquire(userId)`——被拒 → `Flux.just(error 事件带降级文案)` + 不跑 Agent；放行 → 正常链 + `handleError/handleComplete` 里 `gateway.release(userId)`；**泳道超时**：按 intent 分流——非 PLANNING 用 `.timeout(fastLane=5s)` 包 `runAgent` 返回的 Flux（超时 → onErrorResume 转 SSE error「查询超时，请稍后再试」），PLANNING 用 slowLane=60s。`onErrorResume` 文案统一走新降级文案（含熔断 OPEN 时的「模型服务暂时不可用，已自动降级重试中/请稍后再试」）。
7. **`dto/ChatEvent.java`**：无需新事件类型（降级走 error 事件——检测标准明确「SSE error 兜底」）；仅在注释中补「error 含网关降级文案」。

### 测试（4 个新测试类，复刻现有 Fake/Counting 风格）

8. **`LlmGatewayTest`**（纯单测）：FakeCountingModel（implements Model 计数+可控失败）验证——单用户第 3 个并发被拒且有文案；全局 50 上限；QPS 限流；release 后可再进；acquire 超时。
9. **`LlmGatewayModelTest`**（纯单测）：失败 → fallback 调用一次且日志「fallback → qwen-turbo」；中途失败也切；fallback 也失败 → 异常上抛；熔断器：连续失败达阈值 → OPEN（后续调用 CallNotPermittedException 快速失败）→ 等待 waitDuration → HALF_OPEN 放行探测（用 R4j 真实 CircuitBreaker，参数调小加速）。
10. **`ChatServiceGatewayIntegrationTest`**：不起 Spring——手写最小桩（FakeAgent implements Agent? 太重）→ 改为**直接测 LlmGateway 的 SSE 语义**：AcquireResult 拒绝原因 → 降级文案映射断言（文案常量类）。
11. **`LlmGatewayRealApiTest`**（API_KEY 门控）：真实 plus+turbo 经装饰器调一条消息（验证正常路径零回归）；错误 Key 实例（sk-invalid）验证 401 → fallback 到 turbo（正确 Key）成功返回（标准 3 变体）。
12. 全量 `mvn test` 回归（现有 71+ 测试零破坏）。

## 四、E2E 检测标准实测方案

| # | 操作 | 预期 |
|---|---|---|
| 1 | 同会话并发 curl 3 条消息（& 后台并发） | 第 3 条收到 SSE error「您的请求处理中…」明确提示，不白屏（前 2 条正常） |
| 2 | 启动时 `API_KEY=sk-invalid` 覆盖（新进程 + test yml 属性）发消息 | 401 连续失败 → 30s 窗口内日志出现 CircuitBreaker OPEN（`gateway` 日志含 state transition）；OPEN 期间请求快速失败（无 30s 等待）；等 10s 后 HALF_OPEN 探测日志 |
| 3 | 配置慢模型超时模拟（把 model-timeout 调到 2s + 用 qwen-plus 长回复消息触发超时）或错 Key 变体 | 日志出现 `fallback → qwen-turbo`；用户仍收到回复（fallback 成功时正常 delta/done） |
| 4 | 规划慢泳道进行中（60s 档）同时发「/天气 北京」快泳道（5s 档） | 天气响应在数秒内返回（独立链路 + 快泳道 5s 超时保护），不被规划拖到 60s |

（E2E 需启动 PG+Redis+应用；标准 2/3 通过临时属性/环境变量注入，不改动真实 .env）

## 五、文档更新（检测后）

- **architecture.md**：第 1 节图加 LlmGateway 组件；第 4 节补准入/泳道/超时流程；第 7 节补模型 Bean 装饰器说明（含「DashScopeHttpException 不触发框架重试」的核验结论）；第 9 节 error 事件补降级文案说明；新增第 13 节「LLM Gateway」或在第 7 节后插入专节（两层职责、参数表、降级矩阵）
- **fix-record**：新增 7.9 节「LLM Gateway 落地」——记录框架核验发现（原生 fallbackModel 的首信号局限、DashScopeHttpException 不实现 ModelHttpException 的重试盲区、GenerateOptions.modelName 被忽略）、两层职责的设计理由、E2E 检测结果

## 六、明确不做

- SSE 新事件类型（降级走 error，前端零改动）
- 按用户 QPS（单用户已有 Semaphore(2)，QPS 只做全局）
- 线程池隔离（快慢泳道用超时+独立链路实现；真线程池隔离收益低复杂度高，P2）
- AgentScope `fallbackModel`/`maxRetries` 原生 API（装饰器已覆盖且更强；避免双层重试叠加）
- 修改 .env / 真实 API Key（标准 2/3 用进程级临时属性）

## 七、执行顺序

1. pom + AppProperties + yml → 2. LlmGateway + LlmGatewayModel → 3. AgentConfig/ChatService 接线 → 4. 编译 + 4 个测试类 + 全量回归 → 5. E2E 检测标准 1~4（启动依赖链）→ 6. 文档更新 → 7. 关闭全部后台程序（应用/Redis/PG/Docker Desktop 提示）