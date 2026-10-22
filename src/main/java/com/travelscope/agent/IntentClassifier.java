package com.travelscope.agent;

import com.travelscope.dto.IntentResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 意图分类器（应用层路由的第一步）
 * <p>
 * 使用一个轻量 ReActAgent（无工具、无子代理）对用户消息做意图分类，
 * 通过结构化输出（{@code call(msg, IntentResult.class)}）拿到稳定的分类结果，
 * ChatService 据此决定：直接回答 / 调工具 / 委派规划 Agent / RAG 检索，
 * 从而避免闲聊和单点查询触发规划子 Agent。
 * </p>
 * <p>
 * 线程模型：ReActAgent 单实例不接受并发调用，官方 Web 推荐模式为「每次请求新建实例，
 * Model 与 StateStore 可共享」——本类按此模式每次分类新建 Agent，开销可控（单轮短调用）。
 * </p>
 * <p>
 * 本类不使用 @Component，由 AgentConfig 以 Bean 方式注册（构造需要模型名配置）。
 * </p>
 */
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final Duration CLASSIFY_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 分类提示词：类别定义 + 判定规则 + few-shot 示例（参考 LangGraph supervisor 路由模式）。
     * 2026-10-22 v7 意图改造：指代解析规则 + 会话状态规则（需求收集中/行程已生成）+
     * 边缘案例示例（含「那」的新查询/确认式短回答）+ reason 先行（先推理后结论）。
     */
    private static final String SYS_PROMPT = """
            你是 TravelScope 旅游助手的意图分类器。根据用户消息（可能附带对话上下文），判定唯一一个意图类别。

            类别定义：
            - CHAT：问候、闲聊、能力咨询、对助手本身的追问（不需要工具，也不需要规划）
            - TOOL_CALL：单点实时信息查询，一次工具调用即可回答（天气/酒店/景点/餐厅/市内交通/火车票/机票/机场等）
            - PLANNING：需要整合多天、多要素（交通+住宿+景点+预算）的完整行程方案，或对既有行程方案的整体修改
            - RAG：知识型问题（旅游攻略、目的地介绍、签证政策、当地文化风俗等，适合从知识库检索回答）

            判定规则：
            - intent 为唯一类别标签；reason 补一句简短判定依据（不超过 40 字：指代指什么/结合什么状态）
            - 优先按【当前用户消息】本身判定；消息自身意图明确时不被上下文带偏；
              「拿不准」时优先判 TOOL_CALL（单点查询远比规划常见）
            - 消息含指示代词或省略主语（那个/这样/继续/可以吗）时，先从【对话上下文】解析
              指代对象与所处流程，再判定类别
            - 消息本身不明确时（如只答地名/天数/日期/预算/人数的短回答），结合【对话上下文】
              理解——对助手上一轮反问的回答属于正在进行的流程，通常判 PLANNING
            - 上下文含「需求收集中」：用户的确认、选择、短回答是对反问的回答 → PLANNING
            - 上下文含「行程已生成」：对行程的追问、调整 → PLANNING；与行程无关的新查询
              （天气/车票等）按消息本身判定
            - PLANNING 的标准是「需要多要素整合的完整方案」，不是提到旅行就算
            - 用户在追问细节（如「那第二天呢」「换个酒店呢」）且上下文是行程规划时，判 PLANNING
            - 无上下文时只看当前这条消息

            示例：
            「你好，你能做什么？」 → CHAT
            「北京今天天气怎么样？」 → TOOL_CALL
            「查一下明天北京到上海的高铁票」 → TOOL_CALL
            「上海有哪些经济型酒店？」 → TOOL_CALL
            「从杭州到乌镇怎么坐车？」 → TOOL_CALL
            「帮我规划成都五日游，预算 6000」 → PLANNING
            「去西安玩三天，帮我安排一下行程」 → PLANNING
            「第一次去日本旅游有什么注意事项？」 → RAG
            「成都必吃美食有哪些？」 → RAG
            「那边下周天气怎么样」 → TOOL_CALL（含「那」但自身意图明确：天气查询）
            （状态：需求收集中，助手反问出发日期）「这样可以吗」 → PLANNING（对反问的确认）
            （状态：行程已生成）「第二天能加点博物馆吗」 → PLANNING（行程调整）
            （状态：行程已生成）「那边天气如何」 → TOOL_CALL（与行程无关的新查询）
            （上下文：助手反问「您想去哪里玩？」）「长春」 → PLANNING
            （上下文：助手反问「您想去哪里玩？」）「长春今天天气怎么样？」 → TOOL_CALL
            """;

    /** 模型实例（与主 Agent 共用同一配置） */
    private final Model model;
    private com.travelscope.assessment.ObservationMiddleware observation;
    public void setObservation(com.travelscope.assessment.ObservationMiddleware value) { observation=value; }

    public IntentClassifier(Model model) {
        this.model = model;
    }

    /**
     * 对用户消息进行意图分类
     *
     * @param userMessage  用户消息原文
     * @param contextBlock 对话上下文块（messages + 记忆摘要构建；null/空白 = 无上下文，
     *                     行为与旧版一致——只看当前这条消息）
     * @param userId       用户 ID（分类器使用独立的 sessionId，不污染主会话状态）
     * @param sessionId    会话 ID
     * @return 分类结果；分类失败时返回 null（调用方回退为主 Agent 依据自身提示词自主路由）
     */
    public IntentResult classify(String userMessage, String contextBlock, String userId, String sessionId) {
        return classify(userMessage,contextBlock,userId,sessionId,null);
    }
    public IntentResult classify(String userMessage,String contextBlock,String userId,String sessionId,com.travelscope.assessment.ObservationContext observationContext) {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId + "-intent")
                .build();
        if(observationContext!=null)ctx.put(com.travelscope.assessment.ObservationContext.KEY,observationContext);
        try {
            ReActAgent classifier = ReActAgent.builder()
                    .middlewares(observation==null?java.util.List.of():java.util.List.of(observation))
                    .name("intent-classifier")
                    .sysPrompt(SYS_PROMPT)
                    .model(model)
                    .maxIters(2)
                    .build();

            Msg reply = classifier.call(buildInput(userMessage, contextBlock), IntentResult.class, ctx)
                    .block(CLASSIFY_TIMEOUT);
            if (reply == null) {
                log.warn("意图分类返回为空: userId={}, sessionId={}", userId, sessionId);
                return null;
            }
            // 路径一：框架结构化输出（2026-10-22 v7 reason 先行后，glm 会把 JSON 整体当
            // 纯文本输出或 reason 内引号弄畸形 JSON → intent=null 的半解析对象，需兜底）
            IntentResult result = null;
            try {
                result = reply.getStructuredData(IntentResult.class);
            } catch (Exception ignored) {
                // metadata 无结构化键，落纯文本解析（与 L2 同款四层解析）
            }
            if (result != null && result.toIntentType() != null) {
                log.info("意图分类结果: intent={}, reason={} (userId={}, sessionId={})",
                        result.intent, result.reason, userId, sessionId);
                return result;
            }
            // 路径二：纯文本标签解析（intent 字段形态/中文前缀/裸标签——模型把
            // {"reason":…, "intent": "PLANNING"} 当文本输出时由这里接住）
            IntentType fromText = LightweightIntentClassifier.parseTextLabel(reply.getTextContent());
            if (fromText != null) {
                log.info("意图分类结果(纯文本标签): intent={} (userId={}, sessionId={})",
                        fromText.name(), userId, sessionId);
                return new IntentResult(fromText.name(), "L3 单标签输出");
            }
            log.warn("意图分类输出无法解析: textContent={}, userId={}, sessionId={}",
                    reply.getTextContent(), userId, sessionId);
            return result;
        } catch (Exception e) {
            log.warn("意图分类失败，回退为主 Agent 自主路由: userId={}, sessionId={}, 原因: {}",
                    userId, sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 分类输入：有上下文时按「上下文块 + 当前消息」包装，否则原样（保持旧行为）；
     * 与 LightweightIntentClassifier.buildInput 同款（两类结构互为克隆）
     */
    static String buildInput(String userMessage, String contextBlock) {
        if (contextBlock == null || contextBlock.isBlank()) {
            return userMessage;
        }
        return "【对话上下文（截至上一轮）】\n" + contextBlock.trim()
                + "\n\n【当前用户消息】\n" + userMessage;
    }
}
