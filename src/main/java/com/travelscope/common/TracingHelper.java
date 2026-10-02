package com.travelscope.common;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Hooks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 业务链路打点辅助（2026-10-02 多智能体链路 Trace 打点，Micrometer Tracing → OTLP → Jaeger）
 * <p>
 * 统一封装 span 样板，业务处一行调用（withSpan / recordSpan / startSpan），
 * 避免每个类注入 Tracer 手写生命周期。设计约束：
 * <ul>
 *   <li><b>打点失败绝不影响主流程</b>：所有 span 操作 try-catch 兜底，首次失败 warn、
 *       之后降为 debug（与 RedisIntentCache 的降级风格一致）</li>
 *   <li><b>未启用 tracing 时全 no-op</b>：单测直构的类（IntentCascadeRouter /
 *       LlmGatewayModel 等）经旧构造器重载注入 {@link #NOOP}，行为与打点前完全一致</li>
 *   <li><b>Reactor 自动上下文传播</b>（构造时 Hooks.enableAutomaticContextPropagation）：
 *       chat-turn 在请求线程开启后，trace 上下文随订阅流入快/慢泳道与 Agent 链，
 *       子 span（intent-cascade / model-call / task-* / review-attempt）才能挂到同一父链</li>
 * </ul>
 * span 一览（父子关系：chat-turn 为一轮对话的根）：
 * chat-turn（ChatService）→ intent-cascade（IntentCascadeRouter）/ task-backlog（TaskTools）/
 * task-cache（PoiRagTools）/ model-call（LlmGatewayModel）/ review-attempt（ReviewerRetryMiddleware）
 * </p>
 */
@Component
public class TracingHelper {

    private static final Logger log = LoggerFactory.getLogger(TracingHelper.class);

    /** 打点失败只 warn 一次的标记（后续降为 debug，避免长期故障时刷屏） */
    private static volatile boolean failureWarned = false;

    /** 未装配 tracing 时的空实现（旧构造器重载的默认注入，全部 no-op） */
    public static final TracingHelper NOOP = new TracingHelper((Tracer) null);

    private final Tracer tracer;

    // 多构造器场景（另有私有 Tracer 构造器供 NOOP 用）须显式指定注入用构造器
    @Autowired
    public TracingHelper(ObjectProvider<Tracer> tracerProvider) {
        this(tracerProvider.getIfAvailable());
        // Reactor 自动上下文传播：订阅点捕获 ThreadLocal（含 trace 上下文）并在各操作符
        // 线程恢复——没有它，泳道/Agent 链线程上的子 span 会丢失父链（各自成根）。
        // 幂等：Spring Boot 已启用时重复调用无副作用
        try {
            Hooks.enableAutomaticContextPropagation();
        } catch (Throwable t) {
            warnOnce("enableAutomaticContextPropagation", t);
        }
    }

    /** 直构构造器（测试用：注入 mock Tracer 验证 span 内容；Spring 走上面的 @Autowired 构造器） */
    public TracingHelper(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * 包装型打点：span 在 body 执行期间保持打开并作为当前 span（内部子打点挂其下）。
     * body 的业务异常记录进 span 后<b>原样上抛</b>，span 生命周期由本方法管理
     */
    public <T> T withSpan(String name, Map<String, String> attrs, Supplier<T> body) {
        SpanHandle span = startSpan(name, attrs);
        try (var ignored = span.inScope()) {
            return body.get();
        } catch (Throwable t) {
            span.error(t);
            throw t;
        } finally {
            span.end();
        }
    }

    /**
     * 事件型打点：记录一个立即结束的 span（挂在当前活跃 span 下），适合无包裹体的锚点
     * （如 intent-cascade 只在分类完成时才知道 layer/latency）
     */
    public void recordSpan(String name, Map<String, String> attrs) {
        startSpan(name, attrs).end();
    }

    /**
     * 开启长生命周期 span（chat-turn 这类跨线程/异步结束的场景）：返回句柄可继续补属性、
     * 在请求线程短暂 inScope（订阅点捕获上下文）、最终在任意线程 end
     */
    public SpanHandle startSpan(String name, Map<String, String> attrs) {
        if (tracer == null) {
            return SpanHandle.NOOP;
        }
        try {
            Span span = tracer.nextSpan().name(name);
            if (attrs != null) {
                attrs.forEach(span::tag);
            }
            span.start();
            return new SpanHandle(tracer, span, name);
        } catch (Throwable t) {
            warnOnce("startSpan " + name, t);
            return SpanHandle.NOOP;
        }
    }

    // ==================== 会话级显式父链接 ====================
    // 实证（2026-10-02 E2E）：agentscope 框架内部线程跳转不传播 trace 上下文——
    // master PRE_CALL（慢泳道，有 traceId）→ PRE_REASONING 起全部丢失（日志 [,]），
    // 工具/中间件/模型调用线程上创建的 span 会各自成根。工具与中间件都拿得到
    // userId+sessionId，经会话键显式认 chat-turn 为父，把链接回来。

    /** 会话（userId:sessionId）→ 最近一轮 chat-turn span（ChatService 每轮登记，跨轮存续） */
    private final Map<String, SpanHandle> turnSpans = new ConcurrentHashMap<>();

    /**
     * 登记本轮 chat-turn span（startSpan 后、订阅前调用；句柄无效时静默跳过）。
     * <p>登记<b>跨轮存续</b>、由下一轮 beginTurn 覆盖：master 常以异步 spawn 委派
     * planning-agent，planner→reviewer 的子链在 SSE 轮结束后仍在后台执行——晚到的
     * 子打点仍要挂到该会话最近一轮的 trace 上（父 span 已 end 不影响子 span 认父）。
     * 已知取舍：若后台链与用户下一轮并发，晚到子 span 会挂到新一轮 turn（同会话错轮），
     * 观测用途可接受。</p>
     */
    public void beginTurn(String userId, String sessionId, SpanHandle turnSpan) {
        try {
            if (tracer != null && turnSpan != null && turnSpan.span != null) {
                turnSpans.put(userId + ":" + sessionId, turnSpan);
            }
        } catch (Throwable t) {
            warnOnce("beginTurn", t);
        }
    }

    /** 显式挂到该会话 chat-turn 下的子 span；无注册 turn 时退化为自然上下文（可能成根，如实记录） */
    public SpanHandle startChildSpan(String userId, String sessionId, String name, Map<String, String> attrs) {
        if (tracer == null) {
            return SpanHandle.NOOP;
        }
        try {
            SpanHandle parent = sessionId != null ? turnSpans.get(userId + ":" + sessionId) : null;
            if (parent != null && parent.span != null) {
                // 短暂把父 span 设为当前 → nextSpan 以其为父 → 立即恢复（线程安全，scope 仅本线程）
                try (Tracer.SpanInScope ignored = tracer.withSpan(parent.span)) {
                    return startSpan(name, attrs);
                }
            }
        } catch (Throwable t) {
            warnOnce("startChildSpan " + name, t);
        }
        return startSpan(name, attrs);
    }

    /** 事件型子 span：挂在指定会话的 chat-turn 下立即结束（task-cache / review-attempt 用） */
    public void recordChildSpan(String userId, String sessionId, String name, Map<String, String> attrs) {
        startChildSpan(userId, sessionId, name, attrs).end();
    }

    /** 包装型子 span：挂在指定会话的 chat-turn 下执行 body（task-backlog 用） */
    public <T> T withChildSpan(String userId, String sessionId, String name, Map<String, String> attrs,
                               Supplier<T> body) {
        SpanHandle span = startChildSpan(userId, sessionId, name, attrs);
        try (var ignored = span.inScope()) {
            return body.get();
        } catch (Throwable t) {
            span.error(t);
            throw t;
        } finally {
            span.end();
        }
    }

    /**
     * span 句柄：所有操作 no-op 安全且绝不抛异常（打点失败不影响主流程的落点）；
     * end 幂等（complete/error/cancel 多路触发只结束一次）
     */
    public static final class SpanHandle {

        static final SpanHandle NOOP = new SpanHandle(null, null, "noop");

        private final Tracer tracer;
        private final Span span;
        private final String name;
        private final AtomicBoolean ended = new AtomicBoolean();
        /** 本地记录已打的 key（Span API 无读取口，tagIfAbsent 依赖它判断） */
        private final Map<String, String> tagged = new HashMap<>();

        private SpanHandle(Tracer tracer, Span span, String name) {
            this.tracer = tracer;
            this.span = span;
            this.name = name;
        }

        /** 补充/覆盖属性 */
        public void tag(String key, String value) {
            if (span == null) {
                return;
            }
            try {
                span.tag(key, value);
                tagged.put(key, value);
            } catch (Throwable t) {
                warnOnce("tag " + name, t);
            }
        }

        /** 该 key 尚未打过才打（如 outcome=fallback 先标，doOnComplete 的 success 不得覆盖） */
        public void tagIfAbsent(String key, String value) {
            if (span == null || tagged.containsKey(key)) {
                return;
            }
            tag(key, value);
        }

        /** 记录异常事件（不结束 span） */
        public void error(Throwable t) {
            if (span == null) {
                return;
            }
            try {
                span.error(t);
            } catch (Throwable e) {
                warnOnce("error " + name, e);
            }
        }

        /** 结束 span（幂等） */
        public void end() {
            if (span == null || !ended.compareAndSet(false, true)) {
                return;
            }
            try {
                span.end();
            } catch (Throwable t) {
                warnOnce("end " + name, t);
            }
        }

        /**
         * 在当前线程把 span 设为活跃并返回 scope（try-with-resources 关闭）——
         * chat-turn 在请求线程订阅前开启用，订阅捕获上下文后即可关闭
         */
        public SpanScope inScope() {
            if (span == null || tracer == null) {
                return () -> { };
            }
            try {
                Tracer.SpanInScope scope = tracer.withSpan(span);
                return scope::close;
            } catch (Throwable t) {
                warnOnce("inScope " + name, t);
                return () -> { };
            }
        }
    }

    /** scope 关闭句柄：窄化 AutoCloseable（SpanInScope.close() 本就无检查异常，避免 IOException 检查传染调用方） */
    public interface SpanScope extends AutoCloseable {
        @Override
        void close();
    }

    /** 打点失败日志：首次 warn（提示定位），后续 debug（避免刷屏），均不抛出 */
    private static void warnOnce(String where, Throwable t) {
        if (failureWarned) {
            log.debug("业务打点失败（已降级，不影响主流程）: {} 原因: {}", where, t.getMessage());
        } else {
            failureWarned = true;
            log.warn("业务打点首次失败，后续降级为 debug（打点永不影响主流程）: {} 原因: {}",
                    where, t.getMessage());
        }
    }
}
