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
 * L2 轻量意图分类器（FR-S01 三层级联的 L2 层）
 * <p>
 * 与 L3 的 {@link IntentClassifier} 结构完全一致（每次分类新建 ReActAgent、
 * 结构化输出、独立 sessionId 不污染主会话状态），差异只在：
 * <ul>
 *   <li>模型：qwen-turbo（单标签分类任务，低成本低延迟）</li>
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
            你是旅游助手的意图分类器。根据用户消息，判定唯一一个意图类别。

            类别：
            - CHAT：问候、闲聊、能力咨询
            - TOOL_CALL：单点实时查询（天气/酒店/景点/交通/火车票/机票），一次工具调用即可回答
            - PLANNING：需要整合多天多要素（交通+住宿+景点+预算）的完整行程方案
            - RAG：旅游攻略、目的地介绍、文化风俗等知识型问题

            规则：只看当前这条消息；拿不准时优先判 TOOL_CALL。

            示例：
            「你好」 → CHAT
            「北京今天天气怎么样？」 → TOOL_CALL
            「查一下明天北京到上海的高铁票」 → TOOL_CALL
            「帮我规划成都五日游，预算 6000」 → PLANNING
            「成都必吃美食有哪些？」 → RAG
            """;

    private final Model model;

    public LightweightIntentClassifier(Model model) {
        this.model = model;
    }

    /**
     * 单标签分类（结构与 IntentClassifier.classify 一致）
     * <p>
     * qwen-turbo 对结构化输出（合成工具路径）的遵循度不稳定：实测常把意图标签
     * （如 "CHAT"）直接当纯文本输出（metadata 无 _structured_output 键）。因此本类
     * 在结构化解析之外增加<b>纯文本标签解析兜底</b>：textContent.trim() 去掉引号/
     * 句读后能归一化为合法 IntentType 即视为成功——这正是「单标签分类」的最自然形态。
     * 两条路都失败才返回 null（级联层负责降到 L3）。
     * </p>
     *
     * @return 分类结果；失败返回 null（级联层负责降级到 L3）
     */
    public IntentResult classify(String userMessage, String userId, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId + "-intent-l2")
                .build();
        try {
            ReActAgent classifier = ReActAgent.builder()
                    .name("intent-classifier-l2")
                    .sysPrompt(SYS_PROMPT)
                    .model(model)
                    .maxIters(2)
                    .build();

            Msg reply = classifier.call(userMessage, IntentResult.class, ctx)
                    .block(CLASSIFY_TIMEOUT);
            if (reply == null) {
                log.warn("L2 轻量分类返回为空: userId={}, sessionId={}", userId, sessionId);
                return null;
            }
            // 路径一：框架结构化输出（qwen-turbo 对该路径遵循度不稳定，
            // metadata 缺 _structured_output 键时框架会抛异常——捕获后走路径二）
            try {
                IntentResult result = reply.getStructuredData(IntentResult.class);
                if (result != null && result.toIntentType() != null) {
                    log.info("L2 轻量分类结果(结构化): intent={} (userId={}, sessionId={})",
                            result.intent, userId, sessionId);
                    return result;
                }
            } catch (Exception ignored) {
                // metadata 无结构化键（qwen-turbo 常见），落纯文本解析
            }
            // 路径二：纯文本标签解析（qwen-turbo 常把标签直接当文本输出，如 textContent="CHAT"）
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
            log.warn("L2 轻量分类失败（将降级到 L3 兜底）: userId={}, sessionId={}, 原因: {}",
                    userId, sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 纯文本标签解析：去除引号/句读/空白后按 IntentType 归一化；
     * 多词或非标签文本（如完整句子）返回 null
     */
    private static IntentType parseTextLabel(String text) {
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
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
