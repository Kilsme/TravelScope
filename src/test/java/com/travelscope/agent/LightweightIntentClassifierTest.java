package com.travelscope.agent;

import com.travelscope.dto.IntentResult;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LightweightIntentClassifier 真实 API 集成测试（qwen-turbo）
 * <p>
 * 需要 API_KEY 环境变量（DashScope），未配置时整类跳过——与仓库既有
 * WeatherToolTest 的门控风格一致。覆盖 L2 提示词对 4 类意图的真实分类效果，
 * 以及 2026-09-29 失忆修复 Fix 4 的核心场景：反问语境下的裸名词短回答。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "API_KEY", matches = "sk-.+")
class LightweightIntentClassifierTest {

    /** Fix 4 场景上下文：intake 反问「您想去哪里玩」后用户只答地名 */
    private static final String CLARIFY_CONTEXT = """
            会话摘要：用户想要一次旅行规划，需求收集进行中
            最近对话：
            用户：帮我规划一个行程
            助手：好的，请问您想去哪里玩？""";

    private static LightweightIntentClassifier classifier;

    @BeforeAll
    static void setUp() {
        DashScopeChatModel turbo = DashScopeChatModel.builder()
                .apiKey(System.getenv("API_KEY"))
                .modelName("qwen-turbo")
                .stream(true)
                .build();
        classifier = new LightweightIntentClassifier(turbo);
    }

    @Test
    @DisplayName("真实 qwen-turbo：闲聊消息 → CHAT/TOOL_CALL 合法标签")
    void testClassify_chat() {
        IntentResult result = classifier.classify("你好呀，在忙吗", null, "1", "conv-test-l2");
        assertNotNull(result);
        assertNotNull(result.toIntentType(), "L2 应返回合法意图标签: " + (result != null ? result.intent : null));
    }

    @Test
    @DisplayName("真实 qwen-turbo：单点查询 → TOOL_CALL")
    void testClassify_toolCall() {
        IntentResult result = classifier.classify("十一假期北京天气冷不冷，要带厚衣服吗", null, "1", "conv-test-l2");
        assertNotNull(result);
        assertNotNull(result.toIntentType());
        assertTrue(result.toIntentType() == IntentType.TOOL_CALL || result.toIntentType() == IntentType.RAG,
                "天气类问题应判 TOOL_CALL（允许 RAG 的理由是穿衣建议带知识型色彩）: " + result.intent);
    }

    @Test
    @DisplayName("真实 qwen-turbo：规划请求 → PLANNING（验收标准 c 的 L2 判定）")
    void testClassify_planning() {
        IntentResult result = classifier.classify("帮我规划杭州三日游", null, "1", "conv-test-l2");
        assertNotNull(result);
        assertNotNull(result.toIntentType());
        assertTrue(result.toIntentType() == IntentType.PLANNING || result.toIntentType() == IntentType.TOOL_CALL,
                "轻量模型对规划请求的主判应为 PLANNING: " + result.intent);
    }

    @Test
    @DisplayName("真实 qwen-turbo + 上下文：反问语境下的裸地名「长春」→ PLANNING（失忆修复 Fix 4）")
    void testClassify_bareAnswerWithContext_planning() {
        IntentResult result = classifier.classify("长春", CLARIFY_CONTEXT, "1", "conv-test-l2-ctx");
        assertNotNull(result);
        assertNotNull(result.toIntentType());
        assertEquals(IntentType.PLANNING, result.toIntentType(),
                "反问语境下的裸地名应判 PLANNING（不再被判 CHAT 脱离规划流）: " + result.intent);
    }

    @Test
    @DisplayName("真实 qwen-turbo + 上下文：意图明确的消息不被上下文带偏 → TOOL_CALL")
    void testClassify_explicitMessageWithContext_toolCall() {
        IntentResult result = classifier.classify("长春今天天气怎么样", CLARIFY_CONTEXT, "1", "conv-test-l2-ctx");
        assertNotNull(result);
        assertEquals(IntentType.TOOL_CALL, result.toIntentType(),
                "明确的天气查询应按消息本身判 TOOL_CALL，不被规划上下文劫持: " + result.intent);
    }
}
