package com.travelscope.agent.tools;

import com.travelscope.dto.TripRequirementState;
import com.travelscope.service.TripRequirementStore;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RequirementTools 单元测试（含 ask_user 反问轮次 / DEGRADED 闭环，检测标准 2/4 的逻辑层验证）
 * <p>
 * 用一个 opsForHash/expire 全抛异常的 StringRedisTemplate 驱动 TripRequirementStore
 * 走内存降级——降级路径与 Redis 路径共用同一套 get/save/increment 逻辑，
 * Redis 真实 hash 结构由 TripRequirementStoreRedisTest（6379 门控）覆盖。
 * </p>
 */
class RequirementToolsTest {

    /** opsForHash/expire 全抛异常：模拟 Redis 彻底不可用（触发内存降级） */
    static class ThrowingRedisTemplate extends StringRedisTemplate {
        @Override
        public <HK, HV> HashOperations<String, HK, HV> opsForHash() {
            throw new IllegalStateException("redis down");
        }

        @Override
        public Boolean expire(String key, Duration timeout) {
            throw new IllegalStateException("redis down");
        }
    }

    private TripRequirementStore store;
    private RequirementTools tools;

    @BeforeEach
    void setUp() {
        store = new TripRequirementStore(new ThrowingRedisTemplate());
        tools = new RequirementTools(store);
    }

    private static RuntimeContext ctx(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId("conv-t").build();
    }

    @Test
    @DisplayName("update 写回 → get_missing_fields 摘要反映已收集字段（不重复追问的事实源）")
    void testUpdateAndQuery() {
        String r1 = tools.update_requirement_state("conv-t",
                "{\"destination\":\"杭州\",\"days\":3}", ctx("u1"));
        assertTrue(r1.contains("已写回 2 个字段"));
        assertTrue(r1.contains("目的地=杭州"));
        assertTrue(r1.contains("天数=3"));

        String r2 = tools.get_missing_fields("conv-t", ctx("u1"));
        assertTrue(r2.contains("startDate") && r2.contains("fromCity"),
                "仍缺 startDate/fromCity 应列出: " + r2);
        assertFalse(r2.contains("仍缺必填项: destination"),
                "已收集的 destination 不应再出现在缺项里（不重复追问）");
    }

    @Test
    @DisplayName("检测标准2：模糊回答一次理解写回多字段（日期/人数/预算风格）")
    void testFuzzyAnswerFields() {
        // intake-agent 理解「下周吧，两三个人，穷游」后的典型写回
        String r = tools.update_requirement_state("conv-t",
                "{\"startDate\":\"下周末\",\"people\":3,\"budget\":\"穷游（低预算）\"}", ctx("u1"));
        assertTrue(r.contains("人数=3"));
        assertTrue(r.contains("预算=穷游"));

        TripRequirementState s = store.get("u1", "conv-t");
        assertEquals("下周末", s.startDate);
        assertEquals(3, s.people);
        // 下轮反问不应再问日期/人数/预算——只缺 destination/fromCity
        String missing = tools.get_missing_fields("conv-t", ctx("u1"));
        assertTrue(missing.contains("destination") && missing.contains("fromCity"));
        assertFalse(missing.contains("startDate"), "已答日期不应再缺: " + missing);
    }

    @Test
    @DisplayName("ask_user 每调用一次轮次 +1，第 3 次触发 DEGRADED（检测标准4 的状态机）")
    void testAskUserCyclesToDegraded() {
        String first = tools.ask_user("想去哪？国内/国外/还没定", "conv-t", ctx("u1"));
        assertTrue(first.startsWith("想去哪"), "返回值应反问开头（SSE clarify 载体）: " + first);
        assertTrue(first.contains(";;[intake]") && first.contains("第 1/3 轮"),
                "分号后应带轮次提示: " + first);
        assertTrue(tools.ask_user("玩几天？2天/3天/5天+", "conv-t", ctx("u1"))
                .contains("第 2/3 轮"));

        String third = tools.ask_user("哪天出发？本周末/下周末", "conv-t", ctx("u1"));
        assertTrue(third.contains("DEGRADED"), "第 3 轮应提示 DEGRADED: " + third);

        TripRequirementState s = store.get("u1", "conv-t");
        assertEquals(3, s.clarifyCycles);
        assertEquals(TripRequirementState.Status.DEGRADED, s.status,
                "3 轮仍缺必填应置 DEGRADED");
    }

    @Test
    @DisplayName("DEGRADED 后 get_missing_fields 显示 DEGRADED（intake 收尾依据）")
    void testDegradedVisibleInQuery() {
        tools.ask_user("q1", "conv-t", ctx("u1"));
        tools.ask_user("q2", "conv-t", ctx("u1"));
        tools.ask_user("q3", "conv-t", ctx("u1"));
        String q = tools.get_missing_fields("conv-t", ctx("u1"));
        assertTrue(q.contains("DEGRADED"), "查询应显示 DEGRADED: " + q);
    }

    @Test
    @DisplayName("必填收齐 → DONE，get_missing_fields 返回已全部收齐（检测标准5：0 反问直接放行）")
    void testDoneWhenComplete() {
        tools.update_requirement_state("conv-t",
                "{\"destination\":\"杭州\",\"days\":3,\"startDate\":\"周六\",\"fromCity\":\"上海\"}", ctx("u1"));
        TripRequirementState s = store.get("u1", "conv-t");
        assertEquals(TripRequirementState.Status.DONE, s.status);
        assertTrue(tools.get_missing_fields("conv-t", ctx("u1")).contains("已全部收齐"));
    }

    @Test
    @DisplayName("非法输入拒绝：days 非正整数 / 未知字段 / 非法 JSON")
    void testInvalidInputs() {
        assertTrue(tools.update_requirement_state("conv-t", "{\"days\":0}", ctx("u1")).startsWith("ERROR"));
        assertTrue(tools.update_requirement_state("conv-t", "{\"foo\":\"bar\"}", ctx("u1")).startsWith("ERROR"));
        assertTrue(tools.update_requirement_state("conv-t", "not-json", ctx("u1")).startsWith("ERROR"));
    }

    @Test
    @DisplayName("会话隔离：不同会话的状态互不可见")
    void testSessionIsolation() {
        tools.update_requirement_state("conv-a", "{\"destination\":\"杭州\"}", ctx("u1"));
        String other = tools.get_missing_fields("conv-b", ctx("u1"));
        assertTrue(other.contains("destination"), "会话 B 不应看到会话 A 的目的地");
    }
}
