package com.travelscope.common;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 活动流用户友好化（agent_status 事件的最后一道出口，2026-10-04 前端可见性改造）。
 * <p>
 * 把内部 agent 名 / 工具名映射为面向用户的中文角色与动作；纯工程内部操作
 * （缓存 / 文件 / 任务容器等）抑制不推送；THINKING 叙述句消毒——含工程细节
 * （工具名 / 任务ID / 会话ID / 文件路径 / 系统指令词汇）的句子整句丢弃。
 * 目标：用户能在前端全程看到子任务活动，但<b>不暴露工程结构、思维链与系统提示词</b>。
 * </p>
 * <p>
 * 设计取舍：<b>动作白名单制</b>——只有显式映射过的工具才出现在活动流，未知名一律
 * 抑制（宁可少一条活动，不可漏一个内部工具名）。纯函数、无状态。
 * </p>
 */
public final class ActivityPresenter {

    private ActivityPresenter() {
    }

    /** agent 内部名 → 用户可见角色（未知名回退「助手」） */
    private static final Map<String, String> AGENT_NAMES = Map.of(
            "travel-master", "旅行助手",
            "intake-agent", "需求确认",
            "planning-agent", "行程规划",
            "poi-research", "景点检索",
            "route-optimizer", "路线优化",
            "reviewer-agent", "质量审核");

    /** 工具名 → 用户可见动作（白名单：精确匹配） */
    private static final Map<String, String> ACTION_NAMES = Map.ofEntries(
            Map.entry("searchPois", "检索景点"),
            Map.entry("searchNearbyAttractions", "检索周边景点"),
            Map.entry("search_pois_with_rag", "检索景点攻略"),
            Map.entry("getWeather", "查询天气"),
            Map.entry("getWeatherForecast", "查询天气"),
            Map.entry("getFutureWeatherByAirport", "查询天气"),
            Map.entry("searchHotels", "查找酒店"),
            Map.entry("getDrivingRoute", "计算路线"),
            Map.entry("getTransitRoute", "计算路线"),
            Map.entry("geocode", "定位校验"),
            Map.entry("web_search", "联网搜索"),
            Map.entry("agent_spawn", "委派子任务"));

    /** 工具名前缀 → 用户可见动作（mcp 系列带动态后缀） */
    private static final Map<String, String> ACTION_PREFIXES = Map.of(
            "mcp__c12306__", "查询火车票",
            "mcp__variflight__", "查询机票");

    /**
     * 叙述句工程 token 黑名单：命中即整句丢弃。下划线通吃 snake_case 工具名，
     * conv-/task-/.md/mcp__ 覆盖内部标识与文件路径，中文词覆盖系统指令类表述；
     * 2026-10-04 E2E 漏网补齐：camelCase 工具名（getFutureWeatherByAirport 等无下划线）、
     * 连字符任务类型（train-ticket-query / weather-query 等）、markdown 表格行（|）与反引号引用。
     */
    private static final Pattern DIRTY_TOKEN = Pattern.compile(
            "task_|conv-|\\.md|mcp__|_|系统提示词|系统指令|协作目录|工具调用|工具返回"
                    + "|sessionId|会话ID|缓存|Redis|prompt|Prompt|JSON|qwen"
                    + "|[`|]"
                    + "|\\b(?:get|search|find|query|register|update|create|list|load|wait|ask|send|spawn)[A-Z]\\w*"
                    + "|[a-z]+(?:-[a-z]+){2,}"
                    + "|weather-query|hotel-search|attraction-search|route-planning"
                    + "|train-ticket|flight-ticket");

    /** 叙述句预处理：内部标识替换为空（先剥再查，避免替换后的残句误放行） */
    private static final Pattern STRIP_ID = Pattern.compile(
            "task_[0-9a-zA-Z-]+|conv-[0-9]+|tasks/\\S*|[A-Za-z]:\\\\\\S*");

    public static String agentLabel(String agent) {
        return AGENT_NAMES.getOrDefault(agent, "助手");
    }

    /**
     * 工具名 → 用户可见动作。
     *
     * @return 动作名；内部工具 / 未知名返回 {@code null}（调用方不推送该事件）
     */
    public static String actionLabel(String toolName) {
        if (toolName == null) {
            return null;
        }
        String exact = ACTION_NAMES.get(toolName);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : ACTION_PREFIXES.entrySet()) {
            if (toolName.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * THINKING 叙述句消毒：先替换内部 agent 名为中文角色、剥离任务/会话 ID 与路径，
     * 仍命中工程 token 黑名单的句子返回 {@code null}（整句丢弃，不进活动流）。
     */
    public static String sanitizeSentence(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return null;
        }
        String clean = sentence;
        for (Map.Entry<String, String> e : AGENT_NAMES.entrySet()) {
            clean = clean.replace(e.getKey(), e.getValue());
        }
        clean = STRIP_ID.matcher(clean).replaceAll("");
        clean = clean.replace("  ", " ").strip();
        if (clean.isEmpty() || DIRTY_TOKEN.matcher(clean).find()) {
            return null;
        }
        return clean;
    }
}
