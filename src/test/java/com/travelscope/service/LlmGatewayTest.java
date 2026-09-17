package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.config.AppProperties.LlmGatewayConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmGateway 对话准入层单元测试（FR-S09 检测标准 1 的逻辑层验证）
 * <p>
 * 纯 JUnit5 手写配置，覆盖：单用户并发 2 上限（第 3 条被拒+明确文案）、
 * 全局并发上限、QPS 限流、release 后复用、泳道超时取值、失败路径信号量回滚。
 * </p>
 */
class LlmGatewayTest {

    private static AppProperties props(int perUser, int global, int qps) {
        AppProperties p = new AppProperties();
        LlmGatewayConfig gw = p.getLlmGateway();
        gw.setEnabled(true);
        gw.setPerUserConcurrency(perUser);
        gw.setGlobalConcurrency(global);
        gw.setGlobalQps(qps);
        gw.setAcquireTimeoutMs(300);
        return p;
    }

    @Test
    @DisplayName("检测标准1：单用户并发 2 满 → 第 3 条被拒且有明确文案（不白屏）")
    void testThirdConcurrentRejected_withMessage() {
        LlmGateway gateway = new LlmGateway(props(2, 50, 1000));

        LlmGateway.AcquireResult r1 = gateway.tryAcquire("u1");
        LlmGateway.AcquireResult r2 = gateway.tryAcquire("u1");
        assertTrue(r1.allowed());
        assertTrue(r2.allowed());

        LlmGateway.AcquireResult r3 = gateway.tryAcquire("u1");
        assertFalse(r3.allowed(), "第 3 条并发应被拒");
        assertEquals("USER_BUSY", r3.reason());
        assertNotNull(r3.message());
        assertTrue(r3.message().contains("还在处理中"), "文案应明确告知等待: " + r3.message());
    }

    @Test
    @DisplayName("release 后槽位归还：第 3 条在释放后可重新准入")
    void testReleaseRestoresPermits() {
        LlmGateway gateway = new LlmGateway(props(2, 50, 1000));
        gateway.tryAcquire("u1");
        gateway.tryAcquire("u1");
        assertFalse(gateway.tryAcquire("u1").allowed());

        gateway.release("u1");
        assertTrue(gateway.tryAcquire("u1").allowed(), "释放后应可再进");
    }

    @Test
    @DisplayName("全局并发上限：多用户共享 50 槽位，满后新用户被拒（GLOBAL_BUSY）")
    void testGlobalConcurrencyLimit() {
        LlmGateway gateway = new LlmGateway(props(10, 3, 1000));   // 全局 3 方便测试
        assertTrue(gateway.tryAcquire("u1").allowed());
        assertTrue(gateway.tryAcquire("u2").allowed());
        assertTrue(gateway.tryAcquire("u3").allowed());

        LlmGateway.AcquireResult r4 = gateway.tryAcquire("u4");
        assertFalse(r4.allowed());
        assertEquals("GLOBAL_BUSY", r4.reason());
        assertTrue(r4.message().contains("使用人数较多"));
    }

    @Test
    @DisplayName("QPS 限流：超频请求被拒（QPS_LIMIT），有明确文案")
    void testQpsLimit() {
        // QPS=1：同一秒窗口内第 2 个请求被拒（先 release 避免并发限制干扰）
        LlmGateway gateway = new LlmGateway(props(5, 50, 1));
        assertTrue(gateway.tryAcquire("u1").allowed());
        gateway.release("u1");

        LlmGateway.AcquireResult r2 = gateway.tryAcquire("u2");
        assertFalse(r2.allowed(), "同一刷新周期内第 2 个准入应被 QPS 拒绝");
        assertEquals("QPS_LIMIT", r2.reason());
        assertTrue(r2.message().contains("频繁"));
    }

    @Test
    @DisplayName("失败路径信号量回滚：QPS 拒绝后全局槽位不泄漏")
    void testSemaphoreRollbackOnReject() throws InterruptedException {
        // 全局 2；u1 占 1 个，u2 被 QPS 拒（全局应回滚）；
        // 等待 QPS 刷新周期（1s）后 u3 仍能进（证明全局槽位没有因 QPS 拒绝而泄漏）
        LlmGateway gateway = new LlmGateway(props(5, 2, 1));
        assertTrue(gateway.tryAcquire("u1").allowed());
        assertFalse(gateway.tryAcquire("u2").allowed());   // QPS 拒（u1 已消耗本周期配额）

        Thread.sleep(1100);   // 等待 QPS 窗口刷新，隔离 QPS 变量
        assertTrue(gateway.tryAcquire("u3").allowed(), "QPS 拒绝路径的全局槽位应已回滚（不泄漏）");
    }

    @Test
    @DisplayName("泳道超时：PLANNING → 慢泳道（60s），其他 → 快泳道（25s）")
    void testLaneTimeouts() {
        LlmGateway gateway = new LlmGateway(props(2, 50, 10));
        assertEquals(java.time.Duration.ofSeconds(60), gateway.laneTimeout(true));
        assertEquals(java.time.Duration.ofSeconds(25), gateway.laneTimeout(false));
    }

    @Test
    @DisplayName("enabled=false：准入直通（保持旧行为）")
    void testDisabledBypass() {
        AppProperties p = new AppProperties();
        p.getLlmGateway().setEnabled(false);
        LlmGateway gateway = new LlmGateway(p);
        for (int i = 0; i < 10; i++) {
            assertTrue(gateway.tryAcquire("u1").allowed(), "关闭时不应限流");
        }
    }
}
