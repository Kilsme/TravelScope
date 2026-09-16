package com.travelscope.service;

import com.travelscope.config.AgentConfig.TaskWorkspaceService;
import com.travelscope.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskRegistry 四维覆盖校验单测（FR-S03：交通/住宿/景点/天气缺维报错重拆）
 * <p>
 * TaskWorkspaceService 的 workspaceRoot 指向 @TempDir，落盘副作用不污染仓库。
 * </p>
 */
class TaskRegistryDimensionTest {

    private TaskRegistry registry;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getAgentscope().setWorkspacePath(tempDir.toString());
        registry = new TaskRegistry(new TaskWorkspaceService(props));
    }

    // ==================== 工具方法 ====================

    private String backlog(String... tasks) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < tasks.length; i++) {
            String[] parts = tasks[i].split("\\|");
            json.append(String.format(
                    "{\"taskId\":\"T%d\",\"description\":\"%s\",\"suggestedTool\":\"%s\",\"priority\":\"P0\"}",
                    i + 1, parts[0], parts[1]));
            if (i < tasks.length - 1) {
                json.append(",");
            }
        }
        return json.append("]").toString();
    }

    /** 四维齐全的标准清单 */
    private String fullBacklog() {
        return backlog(
                "查询杭州 3 天天气预报|weather-query",
                "查询杭州西湖周边酒店|hotel-search",
                "搜索杭州热门景点|attraction-search",
                "查询上海到杭州高铁票|train-ticket-query");
    }

    // ==================== 全维通过 ====================

    @Test
    @DisplayName("四维齐全（suggestedTool 精确命中）→ 登记成功，hasBacklog 为真")
    void testAllDimensions_coveredBySuggestedTool() {
        String result = registry.createBacklog("u1", "conv-d1", fullBacklog());

        assertTrue(result.startsWith("任务清单已登记"), "应登记成功: " + result);
        assertTrue(result.contains("4 项任务"));
        assertTrue(registry.hasBacklog("u1", "conv-d1"));
    }

    // ==================== 缺维报错 ====================

    @Test
    @DisplayName("缺天气维 → ERROR 并指出缺失维度，不落盘不注册")
    void testMissingWeather_rejected() {
        String result = registry.createBacklog("u1", "conv-d2", backlog(
                "查询杭州西湖周边酒店|hotel-search",
                "搜索杭州热门景点|attraction-search",
                "查询上海到杭州高铁票|train-ticket-query"));

        assertTrue(result.startsWith("ERROR"), "缺天气维应被拒绝: " + result);
        assertTrue(result.contains("天气"));
        assertFalse(registry.hasBacklog("u1", "conv-d2"), "校验失败不应注册内存容器");
        assertNull(new TaskRegistryReader().readBacklog(tempDir, "u1", "conv-d2"),
                "校验失败不应落盘 task_backlog.md");
    }

    @Test
    @DisplayName("缺住宿维 → ERROR")
    void testMissingHotel_rejected() {
        String result = registry.createBacklog("u1", "conv-d3", backlog(
                "查询杭州天气|weather-query",
                "搜索杭州热门景点|attraction-search",
                "查询上海到杭州高铁票|train-ticket-query"));
        assertTrue(result.startsWith("ERROR") && result.contains("住宿"));
    }

    @Test
    @DisplayName("缺景点维 → ERROR")
    void testMissingAttraction_rejected() {
        String result = registry.createBacklog("u1", "conv-d4", backlog(
                "查询杭州天气|weather-query",
                "查询杭州酒店|hotel-search",
                "查询上海到杭州高铁票|train-ticket-query"));
        assertTrue(result.startsWith("ERROR") && result.contains("景点"));
    }

    @Test
    @DisplayName("缺交通维 → ERROR")
    void testMissingTransport_rejected() {
        String result = registry.createBacklog("u1", "conv-d5", backlog(
                "查询杭州天气|weather-query",
                "查询杭州酒店|hotel-search",
                "搜索杭州热门景点|attraction-search"));
        assertTrue(result.startsWith("ERROR") && result.contains("交通"));
    }

    @Test
    @DisplayName("多维缺失 → ERROR 一次列出全部缺失维度并给建议技能名")
    void testMultipleMissing_allListed() {
        String result = registry.createBacklog("u1", "conv-d6", backlog(
                "搜索杭州热门景点|attraction-search"));

        assertTrue(result.startsWith("ERROR"));
        assertTrue(result.contains("交通") && result.contains("住宿") && result.contains("天气"),
                "应列出全部缺失维度: " + result);
        assertTrue(result.contains("train-ticket-query") && result.contains("hotel-search")
                        && result.contains("weather-query"),
                "应给出建议技能名: " + result);
    }

    // ==================== description 关键词兜底 ====================

    @Test
    @DisplayName("suggestedTool 空但描述含关键词 → 双路映射兜底覆盖")
    void testDescriptionKeywordFallback() {
        // 四个任务的 suggestedTool 都不是标准技能名，但描述带维度关键词
        String result = registry.createBacklog("u1", "conv-d7", backlog(
                "查一下那几天的天气怎么样|none",
                "帮我看看住哪里合适|none",
                "找一些值得去的景区|none",
                "确定怎么坐高铁过去|none"));

        assertTrue(result.startsWith("任务清单已登记"),
                "描述关键词应兜底覆盖四维: " + result);
        assertTrue(registry.hasBacklog("u1", "conv-d7"));
    }

    @Test
    @DisplayName("MCP 工具前缀匹配：mcp__c12306__get-tickets 覆盖交通维")
    void testMcpToolPrefix_matchesTransport() {
        String result = registry.createBacklog("u1", "conv-d8", backlog(
                "查询杭州天气|weather-query",
                "查询杭州酒店|hotel-search",
                "搜索杭州热门景点|attraction-search",
                "查上海到杭州的车次|mcp__c12306__get-tickets"));
        assertTrue(result.startsWith("任务清单已登记"), "MCP 前缀应命中交通维: " + result);
    }

    @Test
    @DisplayName("缺维报错后补齐重登 → 成功（纠错闭环）")
    void testRetryAfterMissing_succeeds() {
        String first = registry.createBacklog("u1", "conv-d9", backlog(
                "查询杭州酒店|hotel-search",
                "搜索杭州热门景点|attraction-search",
                "查询上海到杭州高铁票|train-ticket-query"));
        assertTrue(first.startsWith("ERROR"));

        // master 按 ERROR 补齐天气任务后重新登记
        String second = registry.createBacklog("u1", "conv-d9", fullBacklog());
        assertTrue(second.startsWith("任务清单已登记"), "补齐后应登记成功: " + second);
        assertTrue(registry.hasBacklog("u1", "conv-d9"));
    }

    /** 校验失败不落盘的断言辅助（直接读临时目录） */
    private static class TaskRegistryReader {
        String readBacklog(Path root, String userId, String sessionId) {
            Path file = root.resolve(userId).resolve("tasks").resolve(sessionId)
                    .resolve("task_backlog.md");
            return java.nio.file.Files.exists(file) ? "exists" : null;
        }
    }
}
