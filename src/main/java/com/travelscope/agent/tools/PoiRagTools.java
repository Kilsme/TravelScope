package com.travelscope.agent.tools;

import com.travelscope.common.TracingHelper;
import com.travelscope.dto.RetrievedFragment;
import com.travelscope.service.RagServiceImpl;
import com.travelscope.service.TaskResultCache;
import com.travelscope.service.TaskResultCache.TaskType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * RAG 双路检索 + 任务结果缓存工具（FR-S06/S11/S14：poi-research 与 planner 的工具面）
 * <ul>
 *   <li>{@code search_pois_with_rag}：双路检索入口（pgvector 语义 + ES BM25 → RRF 融合），
 *       返回带来源标记的知识库片段——poi-research 第一轮召回与换词重查都用它</li>
 *   <li>{@code get_cached_task_result}：二次规划/回炉前查缓存（命中 cache_hit 日志）</li>
 *   <li>{@code register_task_result}：子任务产出登记（POI/路线/酒店 30min / 天气 10min）</li>
 * </ul>
 * userId 经 RuntimeContext 注入（不带 @ToolParam 的参数），sessionId 由委派说明给出
 * （经 RequirementTools 同款 resolveSessionId 逻辑：子代理 ctx.sessionId 是 sub-xxx，
 * 不能作键——从 ctx 的协作目录键解析主会话 ID）。
 */
public class PoiRagTools {

    private static final Logger log = LoggerFactory.getLogger(PoiRagTools.class);

    /** master 注入的协作目录键（值形如 "tasks/conv-29"，值内含主会话 ID） */
    private static final String CTX_COLLAB_DIR_KEY = "travelscope.collab.dir";

    private final RagServiceImpl ragService;
    private final TaskResultCache taskResultCache;
    /** 业务链路打点（task-cache span，2026-10-02） */
    private final TracingHelper tracing;

    public PoiRagTools(RagServiceImpl ragService, TaskResultCache taskResultCache) {
        this(ragService, taskResultCache, TracingHelper.NOOP);
    }

    public PoiRagTools(RagServiceImpl ragService, TaskResultCache taskResultCache, TracingHelper tracing) {
        this.ragService = ragService;
        this.taskResultCache = taskResultCache;
        this.tracing = tracing;
    }

    /**
     * 双路检索（poi-research 专用）：知识库游记攻略片段，带 RAG/ES 来源标记
     */
    @Tool(description = "从旅游知识库双路检索攻略片段（pgvector 语义 + ES BM25 关键词，RRF 融合）。"
            + "返回片段列表，每条带来源标记（RAG=语义命中 / ES=关键词命中 / RAG+ES=双路命中）。"
            + "召回不足时换关键词再调本工具（如「杭州 博物馆」「杭州 夜景」）。"
            + "适合景点候选的第一轮召回与补充检索")
    public String search_pois_with_rag(
            @ToolParam(name = "query", description = "检索词，如「杭州 必去景点」「杭州 带孩子游玩」「西湖周边 攻略」")
            String query,
            @ToolParam(name = "topK", description = "返回条数，建议 5-10")
            int topK,
            RuntimeContext ctx) {
        if (!ragService.isAvailable()) {
            return "知识库暂不可用（pgvector/ES 未就绪），请直接使用 searchPois 等高德工具检索";
        }
        List<RetrievedFragment> fragments = ragService.dualRetrieveWithSource(query, topK);
        if (fragments.isEmpty()) {
            return "知识库检索无结果（query=" + query + "）。建议换关键词重试，或直接使用 searchPois 检索";
        }
        StringBuilder sb = new StringBuilder("双路检索结果（query=").append(query).append("）共 ")
                .append(fragments.size()).append(" 条：\n");
        for (int i = 0; i < fragments.size(); i++) {
            RetrievedFragment f = fragments.get(i);
            sb.append("\n[").append(i + 1).append("] 来源=").append(f.source())
                    .append(" chunk=").append(f.chunkId())
                    .append(" 融合分=").append(String.format("%.4f", f.score()))
                    .append("\n").append(f.content()).append('\n');
        }
        sb.append("\n提示：以上为知识库游记攻略片段（POI 名称/坐标/开放时间等硬字段可能不全），"
                + "用 searchPois 补全硬字段；引用时标注来源（RAG / ES / RAG+ES）");
        return sb.toString();
    }

    /**
     * 查任务结果缓存（二次规划/回炉前调用）
     */
    @Tool(description = "查询本会话某类子任务的历史产出缓存（poi=景点清单 / route=路线 / weather=天气 / "
            + "hotel=酒店 / itinerary=行程草案）。命中返回缓存内容（可直接复用，跳过重跑）；"
            + "未命中返回 CACHE_MISS。二次规划或质检回炉前必须先查，避免重复执行子任务")
    public String get_cached_task_result(
            @ToolParam(name = "taskType", description = "任务类型：poi / route / weather / hotel / itinerary")
            String taskType,
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用路由指令中给出的值")
            String sessionId,
            RuntimeContext ctx) {
        TaskType type = TaskType.of(taskType);
        if (type == null) {
            return "ERROR: 未知 taskType=" + taskType + "（允许: poi/route/weather/hotel/itinerary）";
        }
        sessionId = resolveSessionId(sessionId, ctx);
        String cached = taskResultCache.get(ctx.getUserId(), sessionId, type);
        // 业务打点（task-cache，2026-10-02）：与 cache_miss 日志同锚点，hit=是否命中；
        // 经会话键显式挂到 chat-turn 下（框架工具线程无 trace 上下文）
        tracing.recordChildSpan(ctx.getUserId(), sessionId, "task-cache", Map.of(
                "taskType", type.name().toLowerCase(),
                "hit", String.valueOf(cached != null)));
        if (cached == null) {
            log.info("cache_miss type={} 用户={}, 会话={}", type.name().toLowerCase(), ctx.getUserId(), sessionId);
            return "CACHE_MISS: " + type.name().toLowerCase() + " 无缓存（或已过期），需正常执行该子任务";
        }
        return "缓存命中（" + type.name().toLowerCase() + "），内容如下，可直接复用（写入对应文件后跳过重跑）：\n\n"
                + cached;
    }

    /**
     * 登记任务产出（子任务完成后调用，供二次规划/回炉复用）
     */
    @Tool(description = "登记子任务产出进缓存（30 分钟内二次规划/回炉可直接复用）。"
            + "poi-research 完成后登记 poi（内容=poi_shortlist.md 全文）；route-optimizer 完成后登记 route；"
            + "天气/酒店查询完成后登记 weather/hotel。taskId 传关联任务 ID（如 T3，无则传 -）")
    public String register_task_result(
            @ToolParam(name = "taskType", description = "任务类型：poi / route / weather / hotel / itinerary")
            String taskType,
            @ToolParam(name = "taskId", description = "关联任务 ID，如 T3；无则传 -")
            String taskId,
            @ToolParam(name = "content", description = "产出内容全文（如 poi_shortlist.md 的内容）")
            String content,
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用路由指令中给出的值")
            String sessionId,
            RuntimeContext ctx) {
        TaskType type = TaskType.of(taskType);
        if (type == null) {
            return "ERROR: 未知 taskType=" + taskType;
        }
        if (content == null || content.isBlank()) {
            return "ERROR: content 为空，没有可登记的产出";
        }
        sessionId = resolveSessionId(sessionId, ctx);
        taskResultCache.register(ctx.getUserId(), sessionId, type, "-".equals(taskId) ? null : taskId, content);
        // 业务打点（task-cache，2026-10-02）：登记即缓存写入事件，hit=true（写入成功）
        tracing.recordChildSpan(ctx.getUserId(), sessionId, "task-cache", Map.of(
                "taskType", type.name().toLowerCase(),
                "hit", "true"));
        return "已登记 " + type.name().toLowerCase() + " 产出（TTL " + type.ttl.toMinutes() + " 分钟），"
                + "二次规划或回炉时 get_cached_task_result 可直接复用";
    }

    /**
     * 解析主会话 ID：参数为 conv- 前缀则信任；否则从 ctx 协作目录键解析（与 RequirementTools 同款）
     */
    private static String resolveSessionId(String sessionId, RuntimeContext ctx) {
        if (sessionId != null && sessionId.startsWith("conv-")) {
            return sessionId;
        }
        Object collabDir = ctx.get(CTX_COLLAB_DIR_KEY);
        if (collabDir != null) {
            String dir = String.valueOf(collabDir);
            int idx = dir.lastIndexOf('/');
            if (idx >= 0 && idx < dir.length() - 1) {
                return dir.substring(idx + 1);
            }
        }
        return sessionId;
    }
}
