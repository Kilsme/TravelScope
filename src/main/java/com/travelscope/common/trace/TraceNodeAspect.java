package com.travelscope.common.trace;

import com.travelscope.common.TracingHelper;
import com.travelscope.common.TracingHelper.SpanHandle;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * {@link TraceNode} 注解切面（2026-10-14 P2-J 埋点注解化）。
 * <p>
 * 双路径：同步方法 = 调用前开 span / 返回（或异常）时打属性并收尾，语义等价
 * {@code TracingHelper.withSpan}；Flux/Mono 返回 = span 延迟到订阅时开启、
 * {@code doOnError/doFinally} 收尾（对齐 {@code LlmGatewayModel} model-call 的手工语义）。
 * </p>
 * <p>
 * <b>观测绝不反噬可用性</b>：切面自身的任何失败（签名解析、上下文构建、SpEL 求值）
 * 全部 try-catch 兜底——属性条目逐个求值、单条失败仅跳过该条；span 开关失败仅产生
 * NOOP 句柄；{@code pjp.proceed()} 的异常一律原样上抛且只调用一次。
 * 本类与 TracingHelper 的 warnOnce 降级风格一致（首次 warn、后续 debug）。
 * </p>
 */
@Aspect
@Component
public class TraceNodeAspect {

    private static final Logger log = LoggerFactory.getLogger(TraceNodeAspect.class);

    /** 切面失败只 warn 一次的标记（与 TracingHelper.warnOnce 同款语义） */
    private static volatile boolean failureWarned = false;

    private static final SpelExpressionParser SPEL = new SpelExpressionParser();

    /** #result 的占位哨兵：异常路径没有结果可绑，用哨兵区分「null 结果」与「无结果」 */
    private static final Object NO_RESULT = new Object[0];

    private static final ParameterNameDiscoverer PARAM_NAMES = new DefaultParameterNameDiscoverer();

    private final TracingHelper tracing;

    public TraceNodeAspect(TracingHelper tracing) {
        this.tracing = tracing;
    }

    @Around("@annotation(traceNode)")
    public Object around(ProceedingJoinPoint pjp, TraceNode traceNode) throws Throwable {
        Method method = resolveMethodQuietly(pjp);
        if (method != null && isReactiveReturn(method.getReturnType())) {
            return aroundReactive(pjp, traceNode, method);
        }
        return aroundSync(pjp, traceNode, method);
    }

    // ==================== 同步路径 ====================

    private Object aroundSync(ProceedingJoinPoint pjp, TraceNode traceNode, Method method) throws Throwable {
        SpanHandle span = openQuietly(pjp, traceNode, method);
        long startNanos = System.nanoTime();
        try {
            Object result = pjp.proceed();
            tagQuietly(pjp, traceNode, span, result, startNanos);
            return result;
        } catch (Throwable t) {
            // 业务异常：记录进 span 后原样上抛（TracingHelper.withSpan 同款语义）
            try {
                span.error(t);
            } catch (Throwable ignored) {
            }
            tagQuietly(pjp, traceNode, span, NO_RESULT, startNanos);
            throw t;
        } finally {
            try {
                span.end();
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 响应式路径 ====================

    private boolean isReactiveReturn(Class<?> returnType) {
        return Flux.class.isAssignableFrom(returnType) || Mono.class.isAssignableFrom(returnType);
    }

    private Object aroundReactive(ProceedingJoinPoint pjp, TraceNode traceNode, Method method) throws Throwable {
        // 装配期：此时不开 span（span 属于订阅生命周期）；装配即抛的异常没有 span，如实上抛
        Object result = pjp.proceed();
        if (result instanceof Flux<?> flux) {
            return Flux.defer(() -> instrumentFlux(flux, pjp, traceNode, method));
        }
        if (result instanceof Mono<?> mono) {
            return instrumentMono(mono, pjp, traceNode, method);
        }
        // 声明为 Flux/Mono 但实现返回了 null 或其他：如实透传（不建 span）
        return result;
    }

    private <T> Flux<T> instrumentFlux(Flux<T> flux, ProceedingJoinPoint pjp, TraceNode traceNode, Method method) {
        SpanHandle span = openQuietly(pjp, traceNode, method);
        long startNanos = System.nanoTime();
        return flux
                .doOnError(t -> {
                    try {
                        span.error(t);
                    } catch (Throwable ignored) {
                    }
                })
                .doFinally(signal -> finishReactive(pjp, traceNode, span, signal, startNanos));
    }

    private <T> Mono<T> instrumentMono(Mono<T> mono, ProceedingJoinPoint pjp, TraceNode traceNode, Method method) {
        return Mono.defer(() -> {
            SpanHandle span = openQuietly(pjp, traceNode, method);
            long startNanos = System.nanoTime();
            return mono
                    .doOnError(t -> {
                        try {
                            span.error(t);
                        } catch (Throwable ignored) {
                        }
                    })
                    .doFinally(signal -> finishReactive(pjp, traceNode, span, signal, startNanos));
        });
    }

    private void finishReactive(ProceedingJoinPoint pjp, TraceNode traceNode, SpanHandle span,
                                SignalType signal, long startNanos) {
        try {
            if (signal == SignalType.CANCEL) {
                try {
                    span.tag("cancelled", "true");
                } catch (Throwable ignored) {
                }
            }
            tagQuietly(pjp, traceNode, span, NO_RESULT, startNanos);
        } finally {
            try {
                span.end();
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== span 开关与属性求值（全部静默降级） ====================

    private SpanHandle openQuietly(ProceedingJoinPoint pjp, TraceNode traceNode, Method method) {
        try {
            String name = traceNode.name().isBlank()
                    ? (method != null ? method.getName() : "trace-node")
                    : traceNode.name();
            if (traceNode.parent() == TraceNode.Parent.TURN) {
                StandardEvaluationContext ctx = buildContext(pjp);
                String userId = evalStringQuietly(traceNode.userId(), ctx);
                String sessionId = evalStringQuietly(traceNode.sessionId(), ctx);
                return tracing.startChildSpan(userId, sessionId, name, null);
            }
            return tracing.startSpan(name, null);
        } catch (Throwable t) {
            warnOnce("openSpan", t);
            return tracing.startSpan("__trace_node_fallback_noop", null);
        }
    }

    /** 收尾打属性：绑定 #result（成功同步路径）与 #latencyMs 后逐项求值；单条失败仅跳过 */
    private void tagQuietly(ProceedingJoinPoint pjp, TraceNode traceNode, SpanHandle span,
                            Object result, long startNanos) {
        try {
            StandardEvaluationContext ctx = buildContext(pjp);
            if (result != NO_RESULT) {
                ctx.setVariable("result", result);
            }
            ctx.setVariable("latencyMs", (System.nanoTime() - startNanos) / 1_000_000);
            for (String attr : traceNode.attrs()) {
                try {
                    int eq = attr.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = attr.substring(0, eq).trim();
                    String expr = attr.substring(eq + 1).trim();
                    if (key.isEmpty() || expr.isEmpty()) {
                        continue;
                    }
                    String value;
                    if (expr.startsWith("#")) {
                        Object v = SPEL.parseExpression(expr).getValue(ctx);
                        value = v == null ? null : String.valueOf(v);
                    } else {
                        value = expr;
                    }
                    if (value != null) {
                        span.tag(key, value);
                    }
                } catch (Throwable perAttr) {
                    log.debug("TraceNode 属性求值跳过（不打断埋点）: {} 原因: {}",
                            attr, perAttr.getMessage());
                }
            }
        } catch (Throwable t) {
            warnOnce("tagAttrs", t);
        }
    }

    // ==================== SpEL 上下文 ====================

    private StandardEvaluationContext buildContext(ProceedingJoinPoint pjp) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        ctx.addPropertyAccessor(BeanOrRecordPropertyAccessor.INSTANCE);
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            Method method = sig.getMethod();
            Object[] args = pjp.getArgs();
            String[] names = resolveParamNames(method, sig);
            for (int i = 0; i < args.length; i++) {
                if (names != null && i < names.length && names[i] != null) {
                    ctx.setVariable(names[i], args[i]);
                }
                ctx.setVariable("p" + i, args[i]);
                ctx.setVariable("a" + i, args[i]);
            }
        } catch (Throwable t) {
            warnOnce("buildContext", t);
        }
        return ctx;
    }

    /** 参数名解析：@AspectJ 元数据优先（无 -parameters 时为 argN），兜底标准发现器（ASM 读调试符号） */
    private String[] resolveParamNames(Method method, MethodSignature sig) {
        try {
            String[] fromSig = sig.getParameterNames();
            if (fromSig != null && fromSig.length == method.getParameterCount()
                    && java.util.Arrays.stream(fromSig).noneMatch(n -> n == null || n.startsWith("arg"))) {
                return fromSig;
            }
        } catch (Throwable ignored) {
        }
        try {
            return PARAM_NAMES.getParameterNames(method);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String evalStringQuietly(String expr, StandardEvaluationContext ctx) {
        try {
            if (expr == null || expr.isBlank()) {
                return null;
            }
            Object v = expr.startsWith("#")
                    ? SPEL.parseExpression(expr).getValue(ctx)
                    : expr;
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            log.debug("TraceNode userId/sessionId 求值跳过: {} 原因: {}", expr, t.getMessage());
            return null;
        }
    }

    private static Method resolveMethodQuietly(ProceedingJoinPoint pjp) {
        try {
            return ((MethodSignature) pjp.getSignature()).getMethod();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 切面失败日志：首次 warn（提示定位），后续 debug（避免刷屏），均不抛出 */
    private static void warnOnce(String where, Throwable t) {
        if (failureWarned) {
            log.debug("TraceNode 切面失败（已降级，不影响主流程）: {} 原因: {}", where, t.getMessage());
        } else {
            failureWarned = true;
            log.warn("TraceNode 切面首次失败，后续降级为 debug（观测绝不反噬可用性）: {} 原因: {}",
                    where, t.getMessage());
        }
    }

    /**
     * 同时兼容三种属性形态的 SpEL PropertyAccessor：
     * JavaBean getter（标准，委托 {@code ReflectivePropertyAccessor}）、record 风格零参方法
     * （{@code #result.dropped} → {@code dropped()}）、public 字段（{@code #result.intent}）。
     * 只为 {@link TraceNode} 的求值服务，只读。
     */
    static final class BeanOrRecordPropertyAccessor implements PropertyAccessor {

        static final BeanOrRecordPropertyAccessor INSTANCE = new BeanOrRecordPropertyAccessor();

        private final PropertyAccessor standard = new org.springframework.expression.spel.support.ReflectivePropertyAccessor();

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return null;
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) throws AccessException {
            if (target == null) {
                return false;
            }
            if (standard.canRead(context, target, name)) {
                return true;
            }
            return readByName(target, name) != NoSuchValue.INSTANCE;
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
            Object value = readByName(target, name);
            if (value == NoSuchValue.INSTANCE) {
                throw new AccessException("无可读属性 '" + name + "'（试过 getter/record 方法/public 字段）");
            }
            return new TypedValue(value);
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
            throw new AccessException("TraceNode 属性只读");
        }

        /** 依次尝试 record 风格零参方法 → getter/is 约定 → public 字段；全部未命中返回哨兵 */
        private static Object readByName(Object target, String name) {
            Class<?> type = target.getClass();
            try {
                Method m = type.getMethod(name);
                if (m.getParameterCount() == 0) {
                    return m.invoke(target);
                }
            } catch (ReflectiveOperationException ignored) {
            }
            String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (String prefix : new String[]{"get", "is"}) {
                try {
                    Method m = type.getMethod(prefix + suffix);
                    if (m.getParameterCount() == 0) {
                        return m.invoke(target);
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            try {
                Field f = type.getField(name);
                return f.get(target);
            } catch (ReflectiveOperationException ignored) {
            }
            return NoSuchValue.INSTANCE;
        }

        /** 未命中哨兵（TypedValue 可以包装 null，不能用 null 当未命中标志） */
        private enum NoSuchValue {
            INSTANCE
        }
    }
}
