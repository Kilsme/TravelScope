package com.travelscope.service;

import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TurnUsageTracker 单元测试（P1-K 真实 token 采集的累加/降级语义）
 */
class TurnUsageTrackerTest {

    private static ChatUsage usage(int in, int out) {
        return ChatUsage.builder().inputTokens(in).outputTokens(out).build();
    }

    @Test
    @DisplayName("null 与全 0 usage 忽略：不算真实使用")
    void testNullAndZeroIgnored() {
        TurnUsageTracker t = new TurnUsageTracker();
        t.onModelCallEnd(null);
        t.onModelCallEnd(usage(0, 0));

        assertFalse(t.hasRealUsage(), "null/零值不应标记为真实使用");
        assertEquals(0L, t.totalTokens());
    }

    @Test
    @DisplayName("单次 usage：input+output 计入 total")
    void testSingleUsage() {
        TurnUsageTracker t = new TurnUsageTracker();
        t.onModelCallEnd(usage(100, 50));

        assertTrue(t.hasRealUsage());
        assertEquals(150L, t.totalTokens());
    }

    @Test
    @DisplayName("多次 usage 累加（ReAct 多轮迭代各一次）")
    void testMultipleAccumulate() {
        TurnUsageTracker t = new TurnUsageTracker();
        t.onModelCallEnd(usage(100, 50));
        t.onModelCallEnd(usage(200, 80));
        t.onModelCallEnd(usage(0, 10));   // 仅 output 也算

        assertEquals(440L, t.totalTokens(), "100+50 + 200+80 + 0+10 = 440");
    }

    @Test
    @DisplayName("零值出现在已有真实 usage 之后：不影响已累计值，不回落")
    void testZeroAfterRealDoesNotReset() {
        TurnUsageTracker t = new TurnUsageTracker();
        t.onModelCallEnd(usage(100, 50));
        t.onModelCallEnd(usage(0, 0));      // 忽略

        assertTrue(t.hasRealUsage());
        assertEquals(150L, t.totalTokens());
    }
}
