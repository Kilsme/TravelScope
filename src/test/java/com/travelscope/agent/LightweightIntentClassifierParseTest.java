package com.travelscope.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * parseTextLabel 三层解析纯单测（2026-10-22，无模型调用）：纯标签 → 带前缀形态
 * （kimi-k3 实测输出「**意图类别：PLANNING**」）→ 短输出词边界提取；长解释文本
 * 判空降级 L3（防多标签词误取首个）。
 */
class LightweightIntentClassifierParseTest {

    private static IntentType parse(String text) throws Exception {
        Method m = LightweightIntentClassifier.class.getDeclaredMethod("parseTextLabel", String.class);
        m.setAccessible(true);
        return (IntentType) m.invoke(null, text);
    }

    @Test
    @DisplayName("纯标签/带句读引号：整串归一化（原有行为不回归）")
    void pureLabelAndPunctuation() throws Exception {
        assertEquals(IntentType.CHAT, parse("CHAT"));
        assertEquals(IntentType.PLANNING, parse("\"planning\"."));
        assertNull(parse("一句话说今天天气不错适合出去玩不知道怎么分类才好呀"));
    }

    @Test
    @DisplayName("带前缀形态兜底：kimi-k3 实测的 markdown 加粗 + 中文前缀输出")
    void prefixedFormRecoversKimiMarkdownOutput() throws Exception {
        // 2026-10-22 图片规划轮实测（conv-89）：L2 两次尝试均输出此形态
        assertEquals(IntentType.PLANNING, parse("**意图类别：PLANNING**"));
        assertEquals(IntentType.PLANNING, parse("**意图分类：PLANNING**"));
        assertEquals(IntentType.CHAT, parse("意图类别: `CHAT`"));
    }

    @Test
    @DisplayName("短输出词边界兜底：非前缀的脏格式仍可提取")
    void shortOutputWordBoundary() throws Exception {
        assertEquals(IntentType.RAG, parse("RAG（知识库问答）"));
        assertEquals(IntentType.TOOL_CALL, parse("标签: TOOL_CALL"));
    }

    @Test
    @DisplayName("长解释文本判空（多标签词误取首个不如降级 L3）")
    void longAmbiguousTextStillNull() throws Exception {
        assertNull(parse("这句话里既提到了 CHAT 又提到了 PLANNING 还夹杂 RAG 与 TOOL_CALL 的解释性长文本，"
                + "明显超过五十个字符的阈值，宁可判空降级到 L3 兜底也不能误取第一个标签词"));
    }
}
