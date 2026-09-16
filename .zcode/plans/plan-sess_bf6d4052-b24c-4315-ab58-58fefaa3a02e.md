# FR-S01 意图识别三层级联实现计划（修订版：L1 规则集对齐验收标准 a~e）

## 一、总体设计（不变）

```
用户消息 → ChatService.resolveIntent → IntentCascadeRouter.classify【新入口】
  L0 会话延续：短追问(≤20字符且含代词/语气词) + Redis 有 30 分钟内该会话最近意图 → 沿用，0 计算
  L1 规则表：保守正则集（yml 可配置 + 代码内置同款默认），首个命中即短路
  L2 轻量分类：qwen-turbo 单标签（LightweightIntentClassifier）+ Redis 文本缓存（SHA-256 键）
  L3 兜底：现有 IntentClassifier 以方法引用包装，零改动
每层命中即短路返回，写回 L0 最近意图（TTL 30min）；日志 cascade_hit=L{n} latency={x}ms
Redis 不可用时缓存全部静默失败（首次 warn 后续 debug），级联自动降级为 L1→L2→L3
```

## 二、L1 规则表（修订核心：按验收标准 c 收窄）

**"帮我规划杭州三日游"必须不命中 L1、落到 L2/L3 判 PLANNING**——自然语言的规划请求（含目的地/天数）交给 L2；L1 只收编「打招呼、命令前缀、极固定句式」，与需求文档 v3 §3.1 对 L1 的定位（`/天气`、`/规划` 命令、固定句式）一致：

| # | 规则（正则，按序首个命中生效） | 意图 | 对应验收 |
|---|---|---|---|
| 1 | `^(你好|您好|嗨|哈喽|hi|hello|在吗)[?？!！。~～\s]*$` | CHAT | a |
| 2 | `^/规划` | PLANNING | — |
| 3 | `^/天气` | TOOL_CALL | b（"/天气 北京"前缀命中） |
| 4 | `(查询|查一下|查查).{0,12}(火车票|高铁票|动车票|车票|机票|航班)` | TOOL_CALL | —（v3 固定句式） |
| 5 | `(今天|明天|后天|大后天|周末).{0,8}(天气|气温|温度)` | TOOL_CALL | — |

关键负向校验：「帮我规划杭州三日游」对以上 5 条全部不命中（规划类自然语言不走 L1）——作为固定回归用例。yml `travelscope.intent-cascade.l1-rules` 显式配置同款规则，未配置/为空时代码内置默认兜底（单一事实源：代码默认与 yml 保持一致）。

L0 追问判定正则（代码常量）：`那|这个|那个|它|他|她|再|还是|还有|帮我|刚才|前面|上面|继续|接着|换个|改成|另外|呢|吧|呗`（含"改成"，覆盖标准 d 的"那改成四天呢"；不含疑问词，避免误判）。

## 三、新建 4 个主代码文件

1. **`agent/IntentCascadeRouter.java`**：嵌套接口 `LlmClassifier{IntentResult classify(String,String,String)}`（L2/L3 注入，测试打桩）；构造时预编译 L1 规则（非法正则/意图 warn 跳过）；classify 流程 `enabled=false 直通 L3 → L0(长度≤上限 && FOLLOWUP 命中才查 Redis，命中刷新 TTL) → L1 → L2(先查文本缓存，miss 调 turbo，成功回写) → L3`；每层成功写回 L0；L2 返回 null 或意图非法视为 miss 落 L3；L3 原样返回（含 null，保持 ChatService 现有容错）。日志：`cascade_hit=L0|L1|L2|L2_CACHE|L3 latency={}ms intent={} ...`、全 miss 打 `cascade_miss`，内部计时 `System.nanoTime()`。
2. **`agent/LightweightIntentClassifier.java`**（L2）：对齐 IntentClassifier 结构（每次新建 ReActAgent、结构化输出、独立 sessionId 后缀 `-intent-l2`），差异：精简 SYS_PROMPT（4 类定义+少量示例+只出单标签）、超时 15s。
3. **`service/IntentCache.java`** 接口：`getRecentIntent / saveRecentIntent(ttl)`（L0）+ `getCachedL2 / saveCachedL2(ttl)`（L2）。
4. **`service/RedisIntentCache.java`**（@Service）：StringRedisTemplate；键 `travelscope:intent:l0:{userId}:{sessionId}`、`travelscope:intent:l2:{sha256(trim)}`；每个操作 try/catch 全异常静默降级（volatile 首次失败 warn）。

## 四、配置与接线（修改 3 文件，IntentClassifier 零改动）

- `AppProperties` 新增嵌套 `IntentCascadeConfig`（含 `L1Rule{pattern,intent}`，仓库首个 List 配置）：`enabled=true / l0Enabled=true / l0TtlMinutes=30 / l0MaxMessageLength=20 / l2Enabled=true / l2Model="qwen-turbo" / l2CacheEnabled=true / l2CacheTtlMinutes=60 / l1Rules`
- `application.yml` 新增 `travelscope.intent-cascade` 段（上述 5 条规则显式写出）
- `AgentConfig`：`dashscopeChatModel` 加 `@Primary`；新增 `dashscopeTurboModel` Bean（同 apiKey + l2Model）；新增 `intentCascadeRouter` Bean（L2=`new LightweightIntentClassifier(turbo)`，L3=`intentClassifier::classify`）
- `ChatService`：构造器与字段 `IntentClassifier` → `IntentCascadeRouter`，`resolveIntent` 改调 router.classify；其余不动

## 五、测试计划（逐条映射验收标准 a~e）

### 1. `IntentCascadeRouterTest`（纯 JUnit5 单测，无门控必跑；手写 FakeIntentCache/ThrowingIntentCache + lambda 计数桩）

| 验收 | 测试用例 | 断言 |
|---|---|---|
| a | `testCriterionA_greetingHitsL1ZeroModelCalls`："你好" | cascade_hit=L1、intent=CHAT、**L2/L3 桩调用次数=0**（等价于 DashScope 调用数不变） |
| b | `testCriterionB_weatherCommandHitsL1`："/天气 北京" | L1 命中 TOOL_CALL，L2/L3 零调用 |
| c | `testCriterionC_planningFallsThroughToL2`："帮我规划杭州三日游" | **L1 不命中**（负向回归）→ L2 桩返回 PLANNING → cascade_hit=L2 |
| c' | 同消息 L2 桩返回 null | 落 L3，cascade_hit=L3 |
| d | `testCriterionD_followupHitsL0`：同会话先发"帮我规划杭州三日游"（L2 桩）再发"那改成四天呢" | 第二次 cascade_hit=L0、intent=PLANNING、L2/L3 零调用 |
| e | `testCriterionE_l1LatencyUnder10ms`：循环 100 次发"你好" | 平均耗时 < 10ms（纳秒计时） |
| 补 | L2 文本缓存命中（同消息第二次 L2 桩零调用→cascade_hit=L2_CACHE）；长消息/无代词不触发 L0；ThrowingCache 全异常时 L1/L2/L3 照常；enabled=false 直通 L3；yml 缺省时内置默认规则生效 | 各 ~1 用例 |

### 2. `LightweightIntentClassifierTest`（`@EnabledIfEnvironmentVariable(named="API_KEY", matches="sk-.+")`，本机已配置会实跑）
真实 qwen-turbo 调用 3 条消息（天气/规划/闲聊），断言非空且 `toIntentType()` 合法。

### 3. `IntentCascadeRealApiTest`（同上门控）：真实 turbo L2 + 真实 qwen-plus L3 + Fake 缓存的端到端级联——发"帮我规划杭州三日游"断言最终 PLANNING 且日志可见 cascade_hit=L2 或 L3；顺带记录 L2 真实延迟（观测 200ms 目标，不作硬断言）。

### 4. `RedisIntentCacheTest`（自定义 `@EnabledIf`：6379 端口可达才跑）：手工构造 Lettuce 连接（localhost:6379/root123456）测 L0/L2 读写回环与 TTL；Redis 未运行自动跳过。

## 六、验证步骤

1. `mvn compile`（JAVA_HOME=ms-21.0.7 + wrapper 路径）
2. 定向跑 4 个新测试类 → 全量 `mvn test`（既有测试均环境门控不受影响）
3. **尽力 E2E**（对应"日志 cascade_hit"字面验证）：探测 5432/6379 端口，若 PostgreSQL 可用则启动应用，依次 curl 发送 a~d 四条消息（同会话发 c→d），grep 应用日志确认 cascade_hit=L1/L1/L2(或 L3)/L0；DB 不可用则跳过，验收由单测+真实 API 测试覆盖（a 的"无模型调用"由桩零调用证明）
4. 尽力启动 Redis（docker-compose up -d redis；daemon 未运行则跳过，降级路径已由 ThrowingCache 用例验证）

## 七、文档更新

`docs/architecture.md`：第 1 节图 `IntentClassifier` 节点 → `IntentCascadeRouter(L0~L3)`；第 5 节改写为三层级联描述（层定义/短路语义/日志格式/配置键/Redis 降级行为/验收标准对照）。