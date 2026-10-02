package com.travelscope.agent;

import com.travelscope.common.TracingHelper;
import com.travelscope.config.AppProperties;
import com.travelscope.config.AppProperties.IntentCascadeConfig;
import com.travelscope.config.AppProperties.L1Rule;
import com.travelscope.dto.IntentResult;
import com.travelscope.service.IntentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 意图三层级联路由器（FR-S01 核心：L0 → L1 → L2 → L3，上层命中即短路）
 * <pre>
 * L0 会话延续：短追问（长度 ≤ 上限 且 含代词/语气词）+ Redis 有 TTL 内该会话最近意图 → 沿用，0 计算
 * L1 规则表：保守正则集（打招呼/命令前缀/极固定句式），首个命中即返回
 * L2 轻量语义：qwen-turbo 单标签分类 + Redis 文本缓存（SHA-256 键）
 * L3 LLM 兜底：现有 IntentClassifier 原样包装（方法引用适配，零改动）
 * </pre>
 * <p>
 * 每层命中即短路返回并写回 L0 最近意图（TTL 刷新）；L1 刻意不收编规划类自然语言
 * （如「帮我规划杭州三日游」），交由 L2/L3 语义判定。Redis 不可用时缓存操作静默
 * 失败（见 RedisIntentCache），级联自动退化为 L1 → L2 → L3。
 * </p>
 * <p>
 * 埋点日志：每次分类输出 {@code cascade_hit=L0|L1|L2|L2_CACHE|L3 latency={x}ms intent={...}}，
 * 全层未命中输出 {@code cascade_miss}——供 FR-S12 的级联命中率统计与延迟观测。
 * </p>
 */
public class IntentCascadeRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentCascadeRouter.class);

    /**
     * LLM 分类器的统一抽象（L2 轻量分类与 L3 兜底都以它注入，测试可打桩计数）
     */
    @FunctionalInterface
    public interface LlmClassifier {
        IntentResult classify(String userMessage, String contextBlock, String userId, String sessionId);
    }

    /**
     * L0 短追问判定：消息含代词/语气词/延续词才视为「对上一轮的追问」。
     * 刻意不含疑问词（怎么/什么/吗），避免「北京天气怎么样」被误判为追问。
     */
    private static final Pattern FOLLOWUP_HINT = Pattern.compile(
            "那|这个|那个|它|他|她|再|还是|还有|帮我|刚才|前面|上面|继续|接着|换个|改成|另外|呢|吧|呗");

    /** 代码内置默认 L1 规则（yml 未配置/为空时兜底；与 application.yml 保持一致） */
    private static final List<L1Rule> DEFAULT_L1_RULES = List.of(
            rule("^(你好|您好|嗨|哈喽|hi|hello|在吗)[?？!！。~～\\s]*$", "CHAT"),
            rule("^/规划", "PLANNING"),
            rule("^/天气", "TOOL_CALL"),
            rule("(查询|查一下|查查).{0,12}(火车票|高铁票|动车票|车票|机票|航班)", "TOOL_CALL"),
            rule("(今天|明天|后天|大后天|周末).{0,8}(天气|气温|温度)", "TOOL_CALL"));

    /** 预编译的 L1 规则（构造时一次编译，非法正则/意图 warn 后跳过） */
    private record CompiledRule(Pattern pattern, IntentType intent) {
    }

    private final IntentCascadeConfig config;
    private final IntentCache cache;
    private final LlmClassifier l2;
    private final LlmClassifier l3;
    private final List<CompiledRule> compiledRules;
    /** 业务链路打点（intent-cascade span，2026-10-02） */
    private final TracingHelper tracing;

    public IntentCascadeRouter(AppProperties appProperties, IntentCache cache,
                               LlmClassifier l2, LlmClassifier l3) {
        this(appProperties, cache, l2, l3, TracingHelper.NOOP);
    }

    public IntentCascadeRouter(AppProperties appProperties, IntentCache cache,
                               LlmClassifier l2, LlmClassifier l3, TracingHelper tracing) {
        this.config = appProperties.getIntentCascade();
        this.cache = cache;
        this.l2 = l2;
        this.l3 = l3;
        this.tracing = tracing;
        this.compiledRules = compileRules(config.getL1Rules());
        log.info("意图级联初始化: enabled={}, L1 规则 {} 条（{}）, L0={}, L2={} (model={}), L2 缓存={}",
                config.isEnabled(), compiledRules.size(),
                config.getL1Rules().isEmpty() ? "内置默认" : "yml 配置",
                config.isL0Enabled(), config.isL2Enabled(), config.getL2Model(),
                config.isL2CacheEnabled());
    }

    /**
     * 级联分类入口（上层命中即短路返回）
     * <p>
     * 2026-09-29 失忆修复 Fix 4：contextBlock（messages + 记忆摘要构建的对话上下文）
     * 仅送 L2/L3 语义层——L0/L1 是确定性快速层不消费上下文；有上下文时旁路 L2 文本
     * 缓存（同文本在不同会话语境下正确意图可能不同，且上下文逐轮变化键必 miss）。
     * </p>
     *
     * @param contextBlock 对话上下文块（ChatService 从 messages 表 + 记忆摘要构建；null/空白 = 无上下文）
     * @return 分类结果；全层失败返回 null（ChatService 保持既有容错：主 Agent 自主路由）
     */
    public IntentResult classify(String userMessage, String contextBlock, String userId, String sessionId) {
        if (!config.isEnabled()) {
            return viaL3(userMessage, contextBlock, userId, sessionId, System.nanoTime());
        }
        long start = System.nanoTime();

        // ---- L0 会话延续：短追问 + Redis 有最近意图 → 沿用，0 计算 ----
        if (config.isL0Enabled() && isLikelyFollowup(userMessage)) {
            String recent = safeGetRecent(userId, sessionId);
            if (recent != null) {
                IntentType type = parseIntent(recent);
                if (type != null) {
                    IntentResult result = new IntentResult(type.name(), "L0 会话延续");
                    // 刷新 TTL：延续窗口随交互滚动
                    safeSaveRecent(userId, sessionId, type.name());
                    logHit("L0", start, userId, sessionId, result);
                    return result;
                }
            }
        }

        // ---- L1 规则表：首个命中即返回（0 模型调用）----
        for (CompiledRule rule : compiledRules) {
            if (rule.pattern().matcher(userMessage).find()) {
                IntentResult result = new IntentResult(rule.intent().name(),
                        "L1 规则命中: " + rule.pattern().pattern());
                saveRecent(userId, sessionId, rule.intent());
                logHit("L1", start, userId, sessionId, result);
                return result;
            }
        }

        // ---- L2 轻量语义：文本缓存（仅无上下文）→ qwen-turbo 单标签 ----
        if (config.isL2Enabled()) {
            String normalized = userMessage == null ? "" : userMessage.trim();
            // 带上下文时旁路文本缓存：同文本在不同会话的正确意图可能不同
            // （「长春」在规划反问语境是 PLANNING、全新会话是 CHAT），上下文逐轮变化
            // 键也必 miss；旁路同时根治一次误判被全局缓存 60 分钟的投毒问题
            boolean useCache = config.isL2CacheEnabled() && !normalized.isEmpty()
                    && (contextBlock == null || contextBlock.isBlank());
            if (useCache) {
                String cached = safeGetL2(normalized);
                IntentType cachedType = cached != null ? parseIntent(cached) : null;
                if (cachedType != null) {
                    IntentResult result = new IntentResult(cachedType.name(), "L2 文本缓存命中");
                    saveRecent(userId, sessionId, cachedType);
                    logHit("L2_CACHE", start, userId, sessionId, result);
                    return result;
                }
            }
            IntentResult l2Result = l2.classify(userMessage, contextBlock, userId, sessionId);
            IntentType l2Type = l2Result != null ? l2Result.toIntentType() : null;
            if (l2Type != null) {
                if (useCache) {
                    safeSaveL2(normalized, l2Type.name());
                }
                saveRecent(userId, sessionId, l2Type);
                logHit("L2", start, userId, sessionId, l2Result);
                return l2Result;
            }
            // L2 miss（null / 非法标签 / 超时）→ 落到 L3
        }

        // ---- L3 兜底：现有 IntentClassifier 原样包装 ----
        return viaL3(userMessage, contextBlock, userId, sessionId, start);
    }

    /**
     * L3 兜底并补埋点（enabled=false 直通时也走这里，保持旧链路）
     */
    private IntentResult viaL3(String userMessage, String contextBlock, String userId, String sessionId, long start) {
        IntentResult result = l3.classify(userMessage, contextBlock, userId, sessionId);
        if (result != null && result.toIntentType() != null) {
            saveRecent(userId, sessionId, result.toIntentType());
            logHit("L3", start, userId, sessionId, result);
        } else {
            long ms = elapsedMs(start);
            log.info("cascade_miss latency={}ms userId={} sessionId={}（全层未命中，主 Agent 自主路由）",
                    ms, userId, sessionId);
            // 业务打点（intent-cascade）：与 cascade_miss 埋点同锚点
            tracing.recordSpan("intent-cascade", Map.of("layer", "MISS", "latencyMs", String.valueOf(ms)));
        }
        return result;
    }

    /**
     * L0 触发条件：长度 ≤ 上限 且 含代词/语气词（避免长句和无指向消息白查 Redis）
     */
    private boolean isLikelyFollowup(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String trimmed = userMessage.trim();
        return trimmed.length() <= config.getL0MaxMessageLength()
                && FOLLOWUP_HINT.matcher(trimmed).find();
    }

    private void saveRecent(String userId, String sessionId, IntentType type) {
        if (config.isL0Enabled()) {
            safeSaveRecent(userId, sessionId, type.name());
        }
    }

    // ==================== 缓存防御性包装（接口实现抛异常也不阻断级联） ====================

    private String safeGetRecent(String userId, String sessionId) {
        try {
            return cache.getRecentIntent(userId, sessionId);
        } catch (Exception e) {
            log.warn("L0 缓存读取失败（跳过该层）: {}", e.getMessage());
            return null;
        }
    }

    private void safeSaveRecent(String userId, String sessionId, String intent) {
        try {
            cache.saveRecentIntent(userId, sessionId, intent,
                    Duration.ofMinutes(config.getL0TtlMinutes()));
        } catch (Exception e) {
            log.warn("L0 缓存写回失败（忽略）: {}", e.getMessage());
        }
    }

    private String safeGetL2(String normalizedMessage) {
        try {
            return cache.getCachedL2(normalizedMessage);
        } catch (Exception e) {
            log.warn("L2 文本缓存读取失败（跳过缓存）: {}", e.getMessage());
            return null;
        }
    }

    private void safeSaveL2(String normalizedMessage, String intent) {
        try {
            cache.saveCachedL2(normalizedMessage, intent,
                    Duration.ofMinutes(config.getL2CacheTtlMinutes()));
        } catch (Exception e) {
            log.warn("L2 文本缓存写回失败（忽略）: {}", e.getMessage());
        }
    }

    private static IntentType parseIntent(String label) {
        if (label == null) {
            return null;
        }
        try {
            return IntentType.valueOf(label.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 埋点日志（FR-S12 基础）：层命中 + 端到端延迟 + 意图
     */
    private void logHit(String layer, long start, String userId, String sessionId, IntentResult result) {
        long latencyMs = elapsedMs(start);
        log.info("cascade_hit={} latency={}ms intent={} userId={} sessionId={}",
                layer, latencyMs, result.intent, userId, sessionId);
        // 业务打点（intent-cascade，2026-10-02）：与 cascade_hit 埋点同锚点同取值（layer/latencyMs）
        tracing.recordSpan("intent-cascade", Map.of("layer", layer, "latencyMs", String.valueOf(latencyMs)));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 编译 L1 规则：yml 配置非空用 yml，否则内置默认；非法条目 warn 后跳过不阻断
     */
    private static List<CompiledRule> compileRules(List<L1Rule> rules) {
        List<L1Rule> source = (rules == null || rules.isEmpty()) ? DEFAULT_L1_RULES : rules;
        List<CompiledRule> compiled = new ArrayList<>();
        for (L1Rule r : source) {
            if (r == null || r.getPattern() == null || r.getIntent() == null) {
                log.warn("L1 规则字段缺失，跳过: {}", r);
                continue;
            }
            IntentType intent = parseIntent(r.getIntent());
            if (intent == null) {
                log.warn("L1 规则意图标签非法（允许 CHAT/TOOL_CALL/PLANNING/RAG），跳过: {}", r.getIntent());
                continue;
            }
            try {
                compiled.add(new CompiledRule(Pattern.compile(r.getPattern()), intent));
            } catch (Exception e) {
                log.warn("L1 规则正则非法，跳过: pattern={}, 原因: {}", r.getPattern(), e.getMessage());
            }
        }
        return List.copyOf(compiled);
    }

    private static L1Rule rule(String pattern, String intent) {
        L1Rule r = new L1Rule();
        r.setPattern(pattern);
        r.setIntent(intent);
        return r;
    }
}
