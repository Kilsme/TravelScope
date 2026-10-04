package com.travelscope.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 活动流用户友好化（ActivityPresenter）单测：映射 / 白名单抑制 / 叙述句消毒。
 * 契约：前端活动流只见中文角色与动作，任何工程 token（工具名/任务ID/会话ID/路径/
 * 系统指令词汇）不得外泄。
 */
class ActivityPresenterTest {

    @Test
    @DisplayName("agentLabel：内部名 → 中文角色，未知名回退「助手」")
    void agentLabelMapsToChinese() {
        assertEquals("行程规划", ActivityPresenter.agentLabel("planning-agent"));
        assertEquals("质量审核", ActivityPresenter.agentLabel("reviewer-agent"));
        assertEquals("旅行助手", ActivityPresenter.agentLabel("travel-master"));
        assertEquals("助手", ActivityPresenter.agentLabel("unknown-agent"));
    }

    @Test
    @DisplayName("actionLabel：白名单工具 → 中文动作；mcp 前缀匹配；内部/未知名 → null 抑制")
    void actionLabelWhitelist() {
        assertEquals("检索景点", ActivityPresenter.actionLabel("searchPois"));
        assertEquals("查询天气", ActivityPresenter.actionLabel("getWeatherForecast"));
        assertEquals("查询火车票", ActivityPresenter.actionLabel("mcp__c12306__get-tickets"));
        assertEquals("查询机票", ActivityPresenter.actionLabel("mcp__variflight__getFlightPriceByCities"));
        assertEquals("委派子任务", ActivityPresenter.actionLabel("agent_spawn"));

        // 工程内部操作一律抑制（不推送）
        assertNull(ActivityPresenter.actionLabel("get_cached_task_result"));
        assertNull(ActivityPresenter.actionLabel("register_task_result"));
        assertNull(ActivityPresenter.actionLabel("update_task_status"));
        assertNull(ActivityPresenter.actionLabel("read_file"));
        assertNull(ActivityPresenter.actionLabel("write_file"));
        assertNull(ActivityPresenter.actionLabel("load_skill_through_path"));
        assertNull(ActivityPresenter.actionLabel("wait_async_results"));
        assertNull(ActivityPresenter.actionLabel("ask_user"));
        assertNull(ActivityPresenter.actionLabel("some_unknown_tool"));
        assertNull(ActivityPresenter.actionLabel(null));
    }

    @Test
    @DisplayName("sanitizeSentence：干净叙述放行（内部 agent 名换中文），工程句整句丢弃")
    void sanitizeSentenceFiltersEngineeringTokens() {
        // 干净句：放行
        assertEquals("正在为您规划北京一日游。", ActivityPresenter.sanitizeSentence("正在为您规划北京一日游。"));
        // 内部 agent 名替换为中文后放行（保留原句空格）
        assertEquals("已委派 行程规划 开始工作",
                ActivityPresenter.sanitizeSentence("已委派 planning-agent 开始工作"));
        // 任务 ID / 会话 ID / 文件路径：先剥离，剥后干净则放行（ID 两侧残留单空格）
        assertEquals("任务 进行中",
                ActivityPresenter.sanitizeSentence("任务 task_80f60d96-eada 进行中"));
        // 工程 token 黑名单：整句丢弃
        assertNull(ActivityPresenter.sanitizeSentence("已读取 poi_shortlist.md 文件"));
        assertNull(ActivityPresenter.sanitizeSentence("先调 get_cached_task_result 查缓存"));
        assertNull(ActivityPresenter.sanitizeSentence("根据系统指令，我需要先创建任务清单"));
        assertNull(ActivityPresenter.sanitizeSentence("写入协作目录 tasks/conv-29/itinerary_draft.md"));
        assertNull(ActivityPresenter.sanitizeSentence("规划师正在使用缓存复用结果"));
        assertNull(ActivityPresenter.sanitizeSentence(null));
        assertNull(ActivityPresenter.sanitizeSentence("   "));
        // 2026-10-04 E2E 实测漏网句（camelCase 工具名 / 表格行 / 反引号 / 连字符任务类型）
        assertNull(ActivityPresenter.sanitizeSentence(
                "查询2026-10-10北京天气（通过PEK机场代理） | `getFutureWeatherByAirport`"));
        assertNull(ActivityPresenter.sanitizeSentence(
                "| T1 | 查询2026-10-10北京天气（通过PEK机场代理） | `getFutureWeatherByAirp"));
        assertNull(ActivityPresenter.sanitizeSentence("用 `getTransitRoute`（属市内交通）替代。"));
        assertNull(ActivityPresenter.sanitizeSentence(
                "系统再次校验：「交通」维度必须使用 `train-ticket-query` 或 `flight-ticket-query`"));
        // 同批 E2E 流里的干净自然句：表格前缀剥离语境后单独成句应放行
        assertEquals("搜索各景点周边安静型餐饮（卫生评分≥4.5，步行可达）",
                ActivityPresenter.sanitizeSentence("搜索各景点周边安静型餐饮（卫生评分≥4.5，步行可达）"));
    }
}
