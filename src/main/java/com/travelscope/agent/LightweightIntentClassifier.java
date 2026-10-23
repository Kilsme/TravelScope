package com.travelscope.agent;

import com.travelscope.common.TurnCancellation;

import com.travelscope.dto.IntentResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * L2 轻量意图分类器（FR-S01 三层级联的 L2 层）
 * <p>
 * 与 L3 的 {@link IntentClassifier} 结构完全一致（每次分类新建 ReActAgent、
 * 结构化输出、独立 sessionId 不污染主会话状态），差异只在：
 * <ul>
 *   <li>模型：轻量档（deepseek-v4-flash-0731 @ 模型网关；单标签分类任务，低成本低延迟）</li>
 *   <li>超时：15 秒（比 L3 的 60 秒短——L2 的定位是快速筛选，
 *       超时/失败即视为 miss 落到 L3 兜底，不在这里恋战）</li>
 *   <li>提示词：精简版（同类定义 + 少量示例），适配轻量模型</li>
 * </ul>
 * 失败返回 null，由 IntentCascadeRouter 决定降级路径。
 * </p>
 */
public class LightweightIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LightweightIntentClassifier.class);

    /** L2 定位是快速筛选：超时即 miss，落到 L3 兜底 */
    private static final Duration CLASSIFY_TIMEOUT = Duration.ofSeconds(15);

    private static final String SYS_PROMPT = """
            你是旅游助手的意图分类器。根据用户消息（可能附带对话上下文），判定唯一一个意图类别。

            类别：
            - CHAT：问候、闲聊、能力咨询
            - TOOL_CALL：单点实时查询（天气/酒店/景点/交通/火车票/机票），一次工具调用即可回答
            - PLANNING：需要整合多天多要素（交通+住宿+景点+预算）的完整行程方案
            - RAG：旅游攻略、目的地介绍、文化风俗等知识型问题

            规则：
            - intent 为唯一类别标签；reason 补一句简短判定依据（不超过 40 字：指代指什么/结合什么状态）
            - 优先按【当前用户消息】本身判定；消息自身意图明确时不被上下文带偏
            - 消息含指示代词或省略主语（那个/这样/继续/可以吗）时，先从【对话上下文】解析
              指代对象与所处流程，再判定类别
            - 消息本身不明确时（如只答地名/天数/日期/预算/人数的短回答），结合【对话上下文】
              理解——对助手上一轮反问的回答属于正在进行的流程，通常判 PLANNING
            - 上下文含「需求收集中」：用户的确认、选择、短回答是对反问的回答 → PLANNING
            - 上下文含「行程已生成」：对行程的追问、调整 → PLANNING；与行程无关的新查询
              （天气/车票等）按消息本身判定
            - 无上下文时只看当前这条消息；拿不准时优先判 TOOL_CALL

            示例：
            「你好」 → CHAT
            「北京今天天气怎么样？」 → TOOL_CALL
            「查一下明天北京到上海的高铁票」 → TOOL_CALL
            「帮我规划成都五日游，预算 6000」 → PLANNING
            「成都必吃美食有哪些？」 → RAG
            「那边下周天气怎么样」 → TOOL_CALL（含「那」但自身意图明确：天气查询）
            （状态：需求收集中，助手反问出发日期）「这样可以吗」 → PLANNING（对反问的确认）
            （状态：行程已生成）「第二天能加点博物馆吗」 → PLANNING（行程调整）
            （状态：行程已生成）「那边天气如何」 → TOOL_CALL（与行程无关的新查询）
            （上下文：助手反问「您想去哪里玩？」）「长春」 → PLANNING
            （上下文：助手反问「您想去哪里玩？」）「长春今天天气怎么样？」 → TOOL_CALL
            """;

    private final Model model;
    private com.travelscope.assessment.ObservationMiddleware observation;
    public void setObservation(com.travelscope.assessment.ObservationMiddleware value) { observation=value; }

    public LightweightIntentClassifier(Model model) {
        this.model = model;
    }

    /**
     * 单标签分类（结构与 IntentClassifier.classify 一致）
     * <p>
     * 轻量模型对结构化输出（合成工具路径）的遵循度不稳定（qwen-turbo 时期实测，
     * 轻量档模型普遍存在）：常把意图标签（如 "CHAT"）直接当纯文本输出（metadata 无
     * _structured_output 键）。因此本类
     * 在结构化解析之外增加<b>纯文本标签解析兜底</b>：textContent.trim() 去掉引号/
     * 句读后能归一化为合法 IntentType 即视为成功——这正是「单标签分类」的最自然形态。
     * 两条路都失败才返回 null（级联层负责降到 L3）。
     * </p>
     *
     * @param userMessage  用户消息原文
     * @param contextBlock 对话上下文块（messages + 记忆摘要构建；null/空白 = 无上下文，
     *                     行为与旧版一致——只看当前这条消息）
     * @return 分类结果；失败返回 null（级联层负责降级到 L3）
     */
    public IntentResult classify(String userMessage, String contextBlock, String userId, String sessionId) {
        return classify(userMessage,contextBlock,userId,sessionId,null);
    }
    public IntentResult classify(String userMessage,String contextBlock,String userId,String sessionId,com.travelscope.assessment.ObservationContext observationContext) {
        TurnCancellation.check(observationContext);
        IntentResult result = classifyOnce(userMessage, contextBlock, userId, sessionId, observationContext);
        TurnCancellation.check(observationContext);
        if (result != null) {
            return result;
        }
        // 重试 1 次（2026-10-05 网关迁移实测：deepseek-v4-flash-0731 约 20% 概率输出
        // 乱格式文本（如 "[[12]]"、双括号 JSON）导致双路解析皆空——与 RubricScorer 的
        // 重试 1 次同款兜底，单次成功率 ~80% → 重试后 ~96%；级联场景本层空值本就有
        // L3 兜底，重试进一步降低打到 L3（更贵）的概率
        result = classifyOnce(userMessage, contextBlock, userId, sessionId, observationContext);
        TurnCancellation.check(observationContext);
        if (result != null) {
            log.info("L2 轻量分类重试成功: userId={}, sessionId={}", userId, sessionId);
            return result;
        }
        return null;
    }

    /** 单次分类调用（双路解析：框架结构化 → 纯文本标签），失败返回 null */
    private IntentResult classifyOnce(String userMessage, String contextBlock, String userId, String sessionId, com.travelscope.assessment.ObservationContext observationContext) {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId + "-intent-l2")
                .build();
        var cancellation = TurnCancellation.from(null);
        if (cancellation != null) ctx.put(TurnCancellation.KEY, cancellation);
        if(observationContext!=null)ctx.put(com.travelscope.assessment.ObservationContext.KEY,observationContext);
        try {
            ReActAgent classifier = ReActAgent.builder()
                    .middlewares(observation==null?java.util.List.of():java.util.List.of(observation))
                    .name("intent-classifier-l2")
                    .sysPrompt(SYS_PROMPT)
                    .model(model)
                    .maxIters(2)
                    .build();

            Msg reply = TurnCancellation.await(classifier.call(buildInput(userMessage, contextBlock), IntentResult.class, ctx), CLASSIFY_TIMEOUT, observationContext);
            if (reply == null) {
                log.warn("L2 轻量分类返回为空: userId={}, sessionId={}", userId, sessionId);
                return null;
            }
            // 路径一：框架结构化输出（轻量模型对该路径遵循度不稳定，
            // metadata 缺 _structured_output 键时框架会抛异常——捕获后走路径二）
            try {
                IntentResult result = reply.getStructuredData(IntentResult.class);
                if (result != null && result.toIntentType() != null) {
                    log.info("L2 轻量分类结果(结构化): intent={} (userId={}, sessionId={})",
                            result.intent, userId, sessionId);
                    return result;
                }
            } catch (Exception ignored) {
                // metadata 无结构化键（轻量模型常见），落纯文本解析
            }
            // 路径二：纯文本标签解析（轻量模型常把标签直接当文本输出，如 textContent="CHAT"）
            IntentType fromText = parseTextLabel(reply.getTextContent());
            if (fromText != null) {
                log.info("L2 轻量分类结果(纯文本标签): intent={} (userId={}, sessionId={})",
                        fromText.name(), userId, sessionId);
                return new IntentResult(fromText.name(), "L2 单标签输出");
            }
            log.warn("L2 轻量分类输出无法解析: textContent={}, userId={}, sessionId={}",
                    reply.getTextContent(), userId, sessionId);
            return null;
        } catch (Exception e) {
            TurnCancellation.rethrow(e, observationContext);
            log.warn("L2 轻量分类失败（将降级到 L3 兜底）: userId={}, sessionId={}, 原因: {}",
                    userId, sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 分类输入：有上下文时按「上下文块 + 当前消息」包装，否则原样（保持旧行为）
     */
    static String buildInput(String userMessage, String contextBlock) {
        if (contextBlock == null || contextBlock.isBlank()) {
            return userMessage;
        }
        return "【对话上下文（截至上一轮）】\n" + contextBlock.trim()
                + "\n\n【当前用户消息】\n" + userMessage;
    }

    /** 兜底一：带前缀形态（kimi-k3 实测输出「**意图类别：PLANNING**」——markdown 加粗+中文前缀） */
    private static final java.util.regex.Pattern LABEL_WITH_PREFIX = java.util.regex.Pattern.compile(
            "意图[类别分类]*\\s*[:：]\\s*[\\*`\"'\\s]*(CHAT|TOOL_CALL|PLANNING|RAG)");
    /**
     * 兜底二：intent 字段形态——2026-10-22 v7 reason 先行后新增：模型（glm）常把
     * {"reason": 长中文, "intent": "PLANNING"} 整体当纯文本输出，或 reason 内引号把
     * JSON 弄畸形后 intent 落在长文本里。显式字段赋值（"intent": X / intent: X）
     * 是模型明确的结论，不受长度限制约束。
     */
    private static final java.util.regex.Pattern INTENT_FIELD = java.util.regex.Pattern.compile(
            "[\"'`]?intent[\"'`]?\\s*[:：]\\s*[\\*`\"'\\s]*(CHAT|TOOL_CALL|PLANNING|RAG)");
    /** 兜底三：短输出内词边界提取（限 ≤50 字符——长解释可能提到多个标签词，误判不如判空降 L3） */
    private static final java.util.regex.Pattern LABEL_ANYWHERE = java.util.regex.Pattern.compile(
            "\\b(CHAT|TOOL_CALL|PLANNING|RAG)\\b");
    private static final int LABEL_ANYWHERE_MAX_LEN = 50;

    /**
     * 纯文本标签解析（四层）：整串归一化 → 「意图X：LABEL」前缀形态 → intent 字段形态
     * （JSON 整体当文本输出/行内 intent: X）→ 短输出（≤50 字符）词边界提取。
     * 全部不中返回 null（级联降 L3）。包可见：{@link IntentClassifier}（L3）复用同款兜底。
     */
    static IntentType parseTextLabel(String text) {
        if (text == null) {
            return null;
        }
        String label = text.trim()
                .replace("\"", "")
                .replace("'", "")
                .replace("`", "")
                .replace(".", "")
                .replace("。", "")
                .replace("!", "")
                .replace("！", "");
        if (label.isEmpty()) {
            return null;
        }
        try {
            return IntentType.valueOf(label.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            // 非纯标签形态，落正则兜底
        }
        java.util.regex.Matcher m = LABEL_WITH_PREFIX.matcher(text);
        if (m.find()) {
            return IntentType.valueOf(m.group(1));
        }
        m = INTENT_FIELD.matcher(text);
        if (m.find()) {
            return IntentType.valueOf(m.group(1));
        }
        if (text.length() <= LABEL_ANYWHERE_MAX_LEN) {
            m = LABEL_ANYWHERE.matcher(text.toUpperCase());
            if (m.find()) {
                return IntentType.valueOf(m.group(1));
            }
        }
        return null;
    }
}
