package com.travelscope.service;

import com.travelscope.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * LoadShedService 单测（2026-09-23 并发改造：三级降级判定）
 * <p>
 * 覆盖：GREEN/YELLOW/RED 判定矩阵、RED 滞回（堆 85~90% 之间维持 RED）、
 * tryAdmit 的准入语义（GREEN 全放 / YELLOW 只挡 PLANNING / RED 全挡）、
 * enabled=false 直通。
 * </p>
 */
class LoadShedServiceTest {

    private static LoadShedService service(double heapYellow, double heapRed, double permitsRatio) {
        AppProperties props = new AppProperties();
        props.getLoadShed().setEnabled(true);
        props.getLoadShed().setHeapYellowThreshold(heapYellow);
        props.getLoadShed().setHeapRedThreshold(heapRed);
        props.getLoadShed().setPermitsYellowRatio(permitsRatio);
        return new LoadShedService(props, new LlmGateway(props));
    }

    @Test
    @DisplayName("健康指标 → GREEN")
    void evaluate_green() {
        LoadShedService s = service(0.75, 0.90, 0.10);
        assertEquals(LoadShedService.Level.GREEN, s.evaluate(0.50, 0.80, false));
    }

    @Test
    @DisplayName("堆超黄线 → YELLOW；并发剩余占比过低 → YELLOW")
    void evaluate_yellow() {
        LoadShedService s = service(0.75, 0.90, 0.10);
        assertEquals(LoadShedService.Level.YELLOW, s.evaluate(0.78, 0.50, false));
        assertEquals(LoadShedService.Level.YELLOW, s.evaluate(0.50, 0.05, false));
    }

    @Test
    @DisplayName("堆超红线 / 并发耗尽 → RED")
    void evaluate_red() {
        LoadShedService s = service(0.75, 0.90, 0.10);
        assertEquals(LoadShedService.Level.RED, s.evaluate(0.92, 0.80, false));
        assertEquals(LoadShedService.Level.RED, s.evaluate(0.50, 0.80, true));
    }

    @Test
    @DisplayName("RED 滞回：堆回落到 85%（黄线上方）仍维持 RED，低于 85% 才回 YELLOW")
    void evaluate_redHysteresis() throws Exception {
        LoadShedService s = service(0.75, 0.90, 0.10);
        setLevel(s, LoadShedService.Level.RED);
        // 堆 0.87：低于 RED 阈值 0.90 但仍在滞回带（≥0.85）→ 维持 RED
        assertEquals(LoadShedService.Level.RED, s.evaluate(0.87, 0.50, false));
        // 堆 0.80：跌出滞回带 → 降回 YELLOW
        assertEquals(LoadShedService.Level.YELLOW, s.evaluate(0.80, 0.50, false));
    }

    @Test
    @DisplayName("tryAdmit：GREEN 全放 / YELLOW 挡 PLANNING 放快请求 / RED 全挡")
    void tryAdmit_levels() throws Exception {
        LoadShedService s = service(0.75, 0.90, 0.10);
        setLevel(s, LoadShedService.Level.GREEN);
        assertNull(s.tryAdmit(true));
        assertNull(s.tryAdmit(false));

        setLevel(s, LoadShedService.Level.YELLOW);
        assertNotNull(s.tryAdmit(true), "YELLOW 应拒绝 PLANNING");
        assertNull(s.tryAdmit(false), "YELLOW 应放行快请求");

        setLevel(s, LoadShedService.Level.RED);
        assertNotNull(s.tryAdmit(true));
        assertNotNull(s.tryAdmit(false));
    }

    @Test
    @DisplayName("enabled=false 全放（降级关闭直通）")
    void tryAdmit_disabled() throws Exception {
        AppProperties props = new AppProperties();
        props.getLoadShed().setEnabled(false);
        LoadShedService s = new LoadShedService(props, new LlmGateway(props));
        setLevel(s, LoadShedService.Level.RED);
        assertNull(s.tryAdmit(true));
        assertNull(s.tryAdmit(false));
    }

    /** 测试钩子：直接设置当前级别（evaluate 的滞回判断依赖当前态） */
    private static void setLevel(LoadShedService s, LoadShedService.Level level) throws Exception {
        Field f = LoadShedService.class.getDeclaredField("level");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<LoadShedService.Level> ref =
                (java.util.concurrent.atomic.AtomicReference<LoadShedService.Level>) f.get(s);
        ref.set(level);
    }
}
