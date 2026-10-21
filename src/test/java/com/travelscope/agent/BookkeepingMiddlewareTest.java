package com.travelscope.agent;

import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.TaskRegistry;
import com.travelscope.service.TaskResultCache;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BookkeepingMiddleware 单测（延迟优化①：记账类调用去 LLM 化）
 * <p>
 * 真实 TaskRegistry（@TempDir 工作区）+ 内存降级 TaskResultCache，驱动工具事件流，
 * 断言：①工具名 → 任务状态机映射正确；②结果缓存自动登记；③ERROR 文本不误标 DONE；
 * ④T3 的 DONE 语义=写 poi_shortlist.json 而非检索成功。
 * </p>
 */
class BookkeepingMiddlewareTest {

    /** Redis 全抛异常 → TaskResultCache 内存降级（同 PoiRagToolsTest 风格） */
    static class ThrowingRedisTemplate extends org.springframework.data.redis.core.StringRedisTemplate {
        @Override
        public org.springframework.data.redis.core.ValueOperations<String, String> opsForValue() {
            throw new IllegalStateException("redis down");
        }
    }

    private static final String USER = "u-test";
    private static final String SESSION = "conv-test";

    private TaskRegistry registry;
    private TaskResultCache cache;
    private BookkeepingMiddleware middleware;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TaskWorkspace workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        registry = new TaskRegistry(workspace);
        cache = new TaskResultCache(new ThrowingRedisTemplate());
        middleware = new BookkeepingMiddleware(registry, cache, workspace);
        String result = registry.createBacklog(USER, SESSION, "["
                + "{\"taskId\":\"T1\",\"description\":\"查询火车票\",\"suggestedTool\":\"train-ticket-query\",\"priority\":\"P0\"},"
                + "{\"taskId\":\"T2\",\"description\":\"查询天气预报\",\"suggestedTool\":\"weather-query\",\"priority\":\"P0\"},"
                + "{\"taskId\":\"T3\",\"description\":\"检索景点 POI 候选池\",\"suggestedTool\":\"poi-retrieval\",\"priority\":\"P0\"},"
                + "{\"taskId\":\"T4\",\"description\":\"搜索酒店住宿\",\"suggestedTool\":\"hotel-search\",\"priority\":\"P1\"},"
                + "{\"taskId\":\"T5\",\"description\":\"规划逐日路线\",\"suggestedTool\":\"route-planner\",\"priority\":\"P1\"}"
                + "]");
        assertTrue(result.startsWith("任务清单已登记"), "清单登记前置失败: " + result);
    }

    // ==================== mapTool 静态映射 ====================

    @Test
    @DisplayName("mapTool：数据工具名 → 任务ID/缓存类型映射（含 mcp__ 前缀）")
    void mapToolMapping() {
        assertEquals("T2", BookkeepingMiddleware.mapTool("getWeatherForecast").taskId());
        assertEquals(TaskResultCache.TaskType.WEATHER, BookkeepingMiddleware.mapTool("getWeatherForecast").taskType());
        assertEquals("T4", BookkeepingMiddleware.mapTool("searchHotels").taskId());
        assertEquals("T1", BookkeepingMiddleware.mapTool("queryTrainTickets").taskId());
        assertEquals(TaskResultCache.TaskType.TRAIN, BookkeepingMiddleware.mapTool("queryTrainTickets").taskType());
        assertEquals(TaskResultCache.TaskType.FLIGHT, BookkeepingMiddleware.mapTool("queryFlightTickets").taskType());
        assertEquals("T3", BookkeepingMiddleware.mapTool("retrieve_poi_candidates").taskId());
        assertEquals("T5", BookkeepingMiddleware.mapTool("plan_route").taskId());
        assertEquals(TaskResultCache.TaskType.TRAIN, BookkeepingMiddleware.mapTool("mcp__c12306__query").taskType());
        assertEquals(TaskResultCache.TaskType.FLIGHT, BookkeepingMiddleware.mapTool("mcp__variflight__query").taskType());
        assertNull(BookkeepingMiddleware.mapTool("write_collab_file"));
        assertNull(BookkeepingMiddleware.mapTool("assemble_itinerary"));
        assertNull(BookkeepingMiddleware.mapTool(null));
    }

    // ==================== 事件流行为 ====================

    @Test
    @DisplayName("天气工具成功：start→T2 IN_PROGRESS，end→T2 DONE + weather 缓存登记")
    void weatherToolBookkeeping() {
        drive(List.of("call-1"),
                new ToolResultStartEvent("r", "call-1", "getWeatherForecast"),
                new ToolResultTextDeltaEvent("r", "call-1", "getWeatherForecast", "杭州 3 天多云"),
                new ToolResultEndEvent("r", "call-1", "getWeatherForecast", ToolResultState.SUCCESS));

        String progress = registry.progress(USER, SESSION);
        // DONE 任务从「未完成」清单中消失（progressSummary 只列未完成项）
        assertTrue(progress.contains("已完成 1") && !progress.contains("T2("), "T2 应为 DONE: " + progress);
        assertEquals("杭州 3 天多云", cache.get(USER, SESSION, TaskResultCache.TaskType.WEATHER));
        assertTrue(progress.contains("T1(PENDING)"), "未触发的 T1 应仍 PENDING: " + progress);
    }

    @Test
    @DisplayName("T3 语义：检索成功只标 IN_PROGRESS；P2 起 POI 缓存由工具自登记全量，中间件不登记")
    void poiDoneSemantics() {
        drive(List.of("call-1", "call-2"),
                new ToolResultStartEvent("r", "call-1", "retrieve_poi_candidates"),
                new ToolResultTextDeltaEvent("r", "call-1", "retrieve_poi_candidates", "{\"pois\":[]}"),
                new ToolResultEndEvent("r", "call-1", "retrieve_poi_candidates", ToolResultState.SUCCESS));

        String progress = registry.progress(USER, SESSION);
        assertTrue(progress.contains("T3(IN_PROGRESS)"), "检索后 T3 应 IN_PROGRESS: " + progress);
        // P2（v6）：工具返回瘦身视图，全量由 retrieve_poi_candidates 自登记——中间件跳过登记防覆盖
        assertNull(cache.get(USER, SESSION, TaskResultCache.TaskType.POI),
                "中间件不再登记 POI 缓存（防 slim 覆盖全量）");

        // write_collab_file(fileName=poi_shortlist.json) 成功 → T3 DONE
        driveCalls(List.of(new ToolUseBlock("call-2", "write_collab_file",
                        Map.of("fileName", "poi_shortlist.json"))),
                new ToolResultEndEvent("r", "call-2", "write_collab_file", ToolResultState.SUCCESS));
        String after = registry.progress(USER, SESSION);
        assertTrue(after.contains("已完成 1") && !after.contains("T3("), "T3 应为 DONE: " + after);
    }

    @Test
    @DisplayName("ERROR 文本结果：不误标 DONE、不登记缓存")
    void errorTextNotMarkedDone() {
        drive(List.of("call-1"),
                new ToolResultEndEvent("r", "call-1", "searchHotels", ToolResultState.SUCCESS));
        // 无累计文本时结果为空串，不视为 ERROR——先补一个 ERROR 文本场景
        drive(List.of("call-2"),
                new ToolResultStartEvent("r", "call-2", "queryTrainTickets"),
                new ToolResultTextDeltaEvent("r", "call-2", "queryTrainTickets", "ERROR: 车票查询超时"),
                new ToolResultEndEvent("r", "call-2", "queryTrainTickets", ToolResultState.SUCCESS));

        String progress = registry.progress(USER, SESSION);
        assertTrue(progress.contains("T1(IN_PROGRESS)"), "ERROR 结果不应标 DONE: " + progress);
        assertNull(cache.get(USER, SESSION, TaskResultCache.TaskType.TRAIN), "ERROR 结果不应登记缓存");
    }

    @Test
    @DisplayName("plan_route 成功：T5 DONE + ROUTE 缓存登记")
    void planRouteBookkeeping() {
        drive(List.of("call-1"),
                new ToolResultTextDeltaEvent("r", "call-1", "plan_route", "{\"days\":2}"),
                new ToolResultEndEvent("r", "call-1", "plan_route", ToolResultState.SUCCESS));
        String progress = registry.progress(USER, SESSION);
        assertTrue(progress.contains("已完成 1") && !progress.contains("T5("), "T5 应为 DONE: " + progress);
        assertEquals("{\"days\":2}", cache.get(USER, SESSION, TaskResultCache.TaskType.ROUTE));
    }

    @Test
    @DisplayName("空 sessionId/userId 上下文：直通不处理")
    void missingContextPassThrough() {
        RuntimeContext empty = RuntimeContext.builder().build();
        var input = new ActingInput(List.of());
        var events = middleware.onActing(null, empty, input,
                in -> Flux.just(new ToolResultEndEvent("r", "c", "getWeatherForecast", ToolResultState.SUCCESS)));
        events.blockLast(); // 不抛异常即通过
        assertNull(cache.get(USER, SESSION, TaskResultCache.TaskType.WEATHER));
    }

    @Test
    @DisplayName("往返双查：两段车票都保留（append 不覆盖）；同内容重复登记被去重")
    void roundTripTrainAppendsNotOverwrites() {
        drive(List.of("call-1"),
                new ToolResultStartEvent("r", "call-1", "queryTrainTickets"),
                new ToolResultTextDeltaEvent("r", "call-1", "queryTrainTickets", "去程: 上海→苏州 G8302 ¥41"),
                new ToolResultEndEvent("r", "call-1", "queryTrainTickets", ToolResultState.SUCCESS));
        drive(List.of("call-2"),
                new ToolResultStartEvent("r", "call-2", "queryTrainTickets"),
                new ToolResultTextDeltaEvent("r", "call-2", "queryTrainTickets", "返程: 苏州→上海 D351 ¥31"),
                new ToolResultEndEvent("r", "call-2", "queryTrainTickets", ToolResultState.SUCCESS));

        String cached = cache.get(USER, SESSION, TaskResultCache.TaskType.TRAIN);
        assertTrue(cached.contains("G8302") && cached.contains("D351"),
                "往返两段都应保留: " + cached);

        // 同内容重复登记（缓存命中后又查/重试）→ 去重不叠加
        drive(List.of("call-3"),
                new ToolResultStartEvent("r", "call-3", "queryTrainTickets"),
                new ToolResultTextDeltaEvent("r", "call-3", "queryTrainTickets", "去程: 上海→苏州 G8302 ¥41"),
                new ToolResultEndEvent("r", "call-3", "queryTrainTickets", ToolResultState.SUCCESS));
        String after = cache.get(USER, SESSION, TaskResultCache.TaskType.TRAIN);
        assertEquals(cached, after, "重复内容不应追加: " + after);
    }

    @Test
    @DisplayName("框架 JSON 字符串壳剥除：带壳结果登记为纯文本（缓存不污染下游）")
    void jsonStringShellStripped() {
        drive(List.of("call-1"),
                new ToolResultStartEvent("r", "call-1", "getWeatherForecast"),
                new ToolResultTextDeltaEvent("r", "call-1", "getWeatherForecast", "\"杭州 3 天多云\""),
                new ToolResultEndEvent("r", "call-1", "getWeatherForecast", ToolResultState.SUCCESS));

        assertEquals("杭州 3 天多云", cache.get(USER, SESSION, TaskResultCache.TaskType.WEATHER));
    }

    @Test
    @DisplayName("plan_route 无溢出自动链：返回含组装标记 → 补 ITINERARY 缓存登记")
    void planRouteAutoChainRegistersItinerary() {
        // 自动链内已落盘草案（工具内不产生 assemble_itinerary 事件，中间件凭标记补登记）
        new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader())
                .writeItineraryDraft(USER, SESSION, new com.travelscope.collab.ItineraryDraft(
                        List.of(new com.travelscope.collab.ItineraryDraft.DraftDay(1, "2026-10-24", "主题",
                                List.of(new com.travelscope.collab.ItineraryDraft.DraftItem(
                                        "09:00-11:00", "游览", "西湖", "免费", null)))),
                        null, List.of(), List.of(), "# 草案正文"));

        drive(List.of("call-1"),
                new ToolResultStartEvent("r", "call-1", "plan_route"),
                new ToolResultTextDeltaEvent("r", "call-1", "plan_route",
                        "{\"solverMeta\":{}}\n\n已自动完成组装落盘 itinerary_draft.json：已组装并写入…"),
                new ToolResultEndEvent("r", "call-1", "plan_route", ToolResultState.SUCCESS));

        assertEquals("# 草案正文", cache.get(USER, SESSION, TaskResultCache.TaskType.ITINERARY));
    }

    /** 驱动一条事件流（callIds 仅用于构造 ActingInput 占位） */
    private void drive(List<String> callIds, io.agentscope.core.event.AgentEvent... events) {
        List<ToolUseBlock> calls = callIds.stream()
                .map(id -> new ToolUseBlock(id, "noop", Map.of()))
                .toList();
        driveCalls(calls, events);
    }

    private void driveCalls(List<ToolUseBlock> calls, io.agentscope.core.event.AgentEvent... events) {
        RuntimeContext ctx = RuntimeContext.builder().userId(USER).sessionId(SESSION).build();
        middleware.onActing(null, ctx, new ActingInput(calls), in -> Flux.just(events)).blockLast();
    }
}
