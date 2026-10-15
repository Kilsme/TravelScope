package com.travelscope.common.trace;

import com.travelscope.common.TracingHelper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TraceNodeAspect 单测（2026-10-14 P2-J）。
 * 直构切面 + mock Tracer/Span + 假 JoinPoint（复用 ReviewerRetryMiddlewareTest 的
 * 「mock Tracer 直证 span 内容」范式），覆盖：同步成功/异常、Flux 成功/错误/取消、
 * TURN 会话认父、SpEL 属性失败单条跳过、record 属性访问、NOOP 降级。
 * 样例方法签名里的参数名经 -parameters 编译参数保留，供 DefaultParameterNameDiscoverer 兜底解析。
 */
class TraceNodeAspectTest {

    /** 测试用 record（验证 record 风格零参方法属性访问，对应 RagServiceImpl.GatedResult） */
    record GatedRecord(int dropped, boolean gateEmpty) {
    }

    /** 测试用 POJO（验证 public 字段属性访问，对应 IntentResult） */
    static class PublicFieldResult {
        public String intent;

        PublicFieldResult(String intent) {
            this.intent = intent;
        }
    }

    /** 被注解的样例服务（方法即注解载体，注解实例经反射取出传给切面） */
    static class SampleService {

        @TraceNode(name = "sample-node", attrs = {
                "query=#query",
                "size=#result.size()",
                "latencyMs=#latencyMs",
                "broken=#noSuchVar"          // 故意非法：验证单条失败跳过、其他照常
        })
        public List<String> syncMethod(String query) {
            return List.of("a", "b");
        }

        @TraceNode(name = "record-node", attrs = {
                "dropped=#result.dropped",
                "gate=#result.gateEmpty"
        })
        public GatedRecord recordMethod() {
            return new GatedRecord(3, true);
        }

        @TraceNode(name = "pojo-node", attrs = {"intent=#result.intent"})
        public PublicFieldResult pojoMethod() {
            return new PublicFieldResult("PLANNING");
        }

        @TraceNode(name = "sync-fail")
        public String syncFail() {
            throw new IllegalStateException("boom");
        }

        @TraceNode(name = "flux-node")
        public Flux<String> fluxMethod() {
            return Flux.just("x", "y");
        }

        @TraceNode(name = "flux-fail")
        public Flux<String> fluxFail() {
            return Flux.error(new IllegalStateException("boom"));
        }

        @TraceNode(name = "flux-cancel")
        public Flux<String> fluxCancel() {
            return Flux.never();
        }

        @TraceNode(name = "turn-node", parent = TraceNode.Parent.TURN,
                userId = "#userId", sessionId = "#sessionId")
        public String turnMethod(String userId, String sessionId) {
            return "ok";
        }
    }

    // ==================== 基建 ====================

    /** 假 JoinPoint：proceed 直接反射调目标方法（模拟经代理调用的真实语义；目标异常解包上抛） */
    private static ProceedingJoinPoint pjp(Object target, Method method, Object... args) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(method);
        // 刻意返回 null：走 DefaultParameterNameDiscoverer 兜底路径（与生产无 ajc 元数据时一致）
        when(sig.getParameterNames()).thenReturn(null);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenAnswer(inv -> {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getTargetException();
            }
        });
        return pjp;
    }

    private static Tracer mockTracer() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.tag(anyString(), anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(tracer.withSpan(any(Span.class))).thenReturn(mock(Tracer.SpanInScope.class));
        return tracer;
    }

    private static Span currentSpan(Tracer tracer) {
        return tracer.nextSpan();
    }

    private static TraceNode ann(Method method) {
        return method.getAnnotation(TraceNode.class);
    }

    // ==================== 同步路径 ====================

    @Test
    @DisplayName("同步成功：span 命名 + 参数/#result/#latencyMs 属性 + 非法条目跳过 + end")
    void syncSuccessTagsAndEnds() throws Throwable {
        Tracer tracer = mockTracer();
        TraceNodeAspect aspect = new TraceNodeAspect(new TracingHelper(tracer));
        SampleService target = new SampleService();
        Method m = SampleService.class.getMethod("syncMethod", String.class);

        Object out = aspect.around(pjp(target, m, "杭州三日"), ann(m));

        assertEquals(List.of("a", "b"), out);
        Span span = currentSpan(tracer);
        verify(span).name("sample-node");
        verify(span).tag("query", "杭州三日");
        verify(span).tag("size", "2");
        verify(span).tag(eq("latencyMs"), matches("\\d+"));
        verify(span, never()).tag(eq("broken"), anyString());   // 单条求值失败静默跳过
        verify(span).start();
        verify(span).end();
    }

    @Test
    @DisplayName("同步异常：span.error 记录 + 异常原样上抛 + end 收尾")
    void syncFailureRecordsErrorAndRethrows() throws Throwable {
        Tracer tracer = mockTracer();
        TraceNodeAspect aspect = new TraceNodeAspect(new TracingHelper(tracer));
        SampleService target = new SampleService();
        Method m = SampleService.class.getMethod("syncFail");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.around(pjp(target, m), ann(m)));

        assertEquals("boom", ex.getMessage());
        Span span = currentSpan(tracer);
        verify(span).name("sync-fail");
        verify(span).error(any(IllegalStateException.class));
        verify(span).end();
    }

    @Test
    @DisplayName("record 属性访问：#result.dropped / #result.gateEmpty 命中零参方法")
    void recordAccessorWorks() throws Throwable {
        Tracer tracer = mockTracer();
        TraceNodeAspect aspect = new TraceNodeAspect(new TracingHelper(tracer));
        SampleService target = new SampleService();
        Method m = SampleService.class.getMethod("recordMethod");

        aspect.around(pjp(target, m), ann(m));

        Span span = currentSpan(tracer);
        verify(span).tag("dropped", "3");
        verify(span).tag("gate", "true");
        verify(span).end();
    }

    @Test
    @DisplayName("public 字段访问：#result.intent 命中 POJO 字段（IntentResult 形态）")
    void publicFieldAccessorWorks() throws Throwable {
        Tracer tracer = mockTracer();
        TraceNodeAspect aspect = new TraceNodeAspect(new TracingHelper(tracer));
        SampleService target = new SampleService();
        Method m = SampleService.class.getMethod("pojoMethod");

        aspect.around(pjp(target, m), ann(m));

        Span span = currentSpan(tracer);
        verify(span).tag("intent", "PLANNING");
        verify(span).end();
    }

    // ==================== 响应式路径 ====================

    @Test
    @DisplayName("Flux 成功：订阅前不开 span（装配期零开销），订阅完成后 end")
    void fluxSuccessEndsAfterSubscribe() throws Throwable {
        Tracer tracer = mockTracer();
        TraceNodeAspect aspect = new TraceNodeAspect(new TracingHelper(tracer));
        SampleService target = new SampleService();
        Method m = SampleService.class.getMethod("fluxMethod");

        Object out = aspect.around(pjp(target, m), ann(m));
        verify(tracer, never()).nextSpan();   // 装配期不建 span

        List<Object> collected = new CopyOnWriteArrayList<>();
        ((Flux<?>) out).subscribe(collected::add);
        assertEquals(List.of("x", "y"), collected);
        Span span = currentSpan(tracer);
        verify(span).name("flux-node");
        verify(span).end();
    }

    @Test
    @DisplayName("Flux 错误：span.error 记录 + end（错误不吞）")
    void fluxErrorRecordsAndEnds() throws Throwable {
        Tracer tracer = mockTracer();
        TraceNodeAspect aspect = new TraceNodeAspect(new TracingHelper(tracer));
        SampleService target = new SampleService();
        Method m = SampleService.class.getMethod("fluxFail");

        AtomicReference<Throwable> err = new AtomicReference<>();
        ((Flux<?>) aspect.around(pjp(target, m), ann(m))).subscribe(x -> { }, err::set);

        assertEquals("boom", err.get().getMessage());
        Span span = currentSpan(tracer);
        verify(span).error(any(IllegalStateException.class));
        verify(span).end();
    }

    @Test
    @DisplayName("Flux 取消：cancelled 标记 + end（三态收尾齐备）")
    void fluxCancelEndsWithTag() throws Throwable {
        Tracer tracer = mockTracer();
        TraceNodeAspect aspect = new TraceNodeAspect(new TracingHelper(tracer));
        SampleService target = new SampleService();
        Method m = SampleService.class.getMethod("fluxCancel");

        Disposable d = ((Flux<?>) aspect.around(pjp(target, m), ann(m))).subscribe();
        d.dispose();

        Span span = currentSpan(tracer);
        verify(span).tag("cancelled", "true");
        verify(span).end();
    }

    // ==================== TURN 会话认父 ====================

    @Test
    @DisplayName("TURN 模式：经 TracingHelper 会话表认父（withSpan(parent) 创建子 span）")
    void turnModeParentsUnderChatTurn() throws Throwable {
        Tracer tracer = mock(Tracer.class);
        Span parentSpan = mock(Span.class);
        Span childSpan = mock(Span.class);
        when(parentSpan.name(anyString())).thenReturn(parentSpan);
        when(childSpan.name(anyString())).thenReturn(childSpan);
        when(parentSpan.start()).thenReturn(parentSpan);
        when(childSpan.start()).thenReturn(childSpan);
        when(childSpan.tag(anyString(), anyString())).thenReturn(childSpan);
        when(tracer.nextSpan()).thenReturn(parentSpan, childSpan);
        when(tracer.withSpan(parentSpan)).thenReturn(mock(Tracer.SpanInScope.class));

        TracingHelper helper = new TracingHelper(tracer);
        TraceNodeAspect aspect = new TraceNodeAspect(helper);
        SampleService target = new SampleService();
        Method m = SampleService.class.getMethod("turnMethod", String.class, String.class);

        // 模拟 ChatService 每轮登记 chat-turn（子 span 的认父依据）
        helper.beginTurn("u1", "s1", helper.startSpan("chat-turn", null));

        Object out = aspect.around(pjp(target, m, "u1", "s1"), ann(m));

        assertEquals("ok", out);
        verify(tracer).withSpan(parentSpan);     // 显式认父发生
        verify(childSpan).name("turn-node");
        verify(childSpan).end();
    }

    // ==================== 降级 ====================

    @Test
    @DisplayName("Tracer 缺失（NOOP）：业务照常返回，切面零副作用")
    void noopTracerPassesThrough() throws Throwable {
        TraceNodeAspect aspect = new TraceNodeAspect(TracingHelper.NOOP);
        SampleService target = new SampleService();
        Method m = SampleService.class.getMethod("syncMethod", String.class);

        Object out = aspect.around(pjp(target, m, "test"), ann(m));

        assertEquals(List.of("a", "b"), out);
    }
}
