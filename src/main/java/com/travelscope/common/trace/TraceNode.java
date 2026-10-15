package com.travelscope.common.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 span 埋点（2026-10-14 P2-J 埋点注解化，对照 ragent {@code @RagTraceNode} 的轻量版）。
 * <p>
 * 标注在 Spring Bean 的方法上，由 {@link TraceNodeAspect} 自动开启/收尾 span，
 * 替代手写 {@code tracing.withSpan/recordSpan} 样板。适用边界（与手工埋点的分工见
 * {@code TracingHelper} 类头「埋点方式选择」一节）：
 * <ul>
 *   <li><b>仅对 Spring 代理可达的方法生效</b>：Bean 间调用（经注入的代理引用）才能被拦截——
 *       {@code AgentConfig} 里 {@code new} 出来交给 AgentScope 框架反射回调的
 *       工具/中间件不是 Bean，注解对其无效（ragent 文档自曝的 self-call / 非代理调用坑，
 *       本项目的对应物），那些锚点继续用 TracingHelper 手工埋点</li>
 *   <li><b>同步方法</b>：span 在方法调用前开启、返回/异常时收尾，属性在返回后统一求值
 *       （因此 SpEL 可以引用 {@code #result}）</li>
 *   <li><b>Flux/Mono 返回</b>：span 延迟到订阅时开启（装配期不建 span），
 *       {@code doOnError/doFinally} 收尾；响应式模式下 {@code #result} 未绑定，
 *       引用它的属性条目会被静默跳过（属性求值失败永不抛出）</li>
 * </ul>
 * SpEL 上下文变量：方法参数（按参数名、{@code #p0}/{@code #a0} 下标）、
 * {@code #result}（同步成功返回后）、{@code #latencyMs}（切面内置，毫秒耗时）。
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TraceNode {

    /** span 名；留空取方法名 */
    String name() default "";

    /**
     * 属性条目，形如 {@code "key=value"}；value 以 {@code #} 开头按 SpEL 求值，
     * 否则按字面量。求值失败的条目单独跳过，不影响其他条目与主流程。
     */
    String[] attrs() default {};

    /**
     * 父 span 模式：{@link Parent#CONTEXT} 自然上下文（依赖 Reactor 自动传播，
     * 打点所在线程有 trace 上下文则挂上，否则如实成根）；
     * {@link Parent#TURN} 经 {@code TracingHelper} 会话表显式认父
     * （框架线程上丢上下文的锚点用，需配合 {@link #userId()}/{@link #sessionId()}）
     */
    Parent parent() default Parent.CONTEXT;

    /** parent=TURN 时解析 userId 的 SpEL（如 {@code "#userId"}，对应方法参数名） */
    String userId() default "";

    /** parent=TURN 时解析 sessionId 的 SpEL */
    String sessionId() default "";

    /** 父 span 归属模式 */
    enum Parent {
        /** 自然上下文（打点线程当前活跃 span 为父；无则成根，如实记录） */
        CONTEXT,
        /** 会话级显式认父：挂到该 userId:sessionId 最近一轮 chat-turn 下 */
        TURN
    }
}
