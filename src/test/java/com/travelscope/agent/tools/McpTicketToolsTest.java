package com.travelscope.agent.tools;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 票务工具测试用例（12306 火车票 + 飞常准飞机票）
 * <p>
 * 测试策略（与项目其他工具测试一致）：
 * 1. 集成测试：真实拉起 npx MCP 子进程并调用远程工具，用 @EnabledIfEnvironmentVariable 控制
 *    - 12306：无需 Key，但需网络，由 MCP_TEST_12306=true 显式开启
 *    - 飞常准：由 VARIFLIGHT_API_KEY 存在与否自动控制
 * 2. 所有测试直接通过 McpClientWrapper.callTool 调用，验证 MCP 通道与工具可用性
 * </p>
 */
@DisplayName("MCP 票务工具测试（12306 火车票 / 飞常准飞机票）")
class McpTicketToolsTest {

    private static final Logger log = LoggerFactory.getLogger(McpTicketToolsTest.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== 12306 火车票（集成测试，MCP_TEST_12306=true 开启） ====================

    @Test
    @DisplayName("12306 MCP 连接并列出工具（集成测试 - 需 MCP_TEST_12306=true）")
    @EnabledIfEnvironmentVariable(named = "MCP_TEST_12306", matches = "true")
    void test12306_listTools() {
        try (McpClientWrapper client = build12306Client()) {
            List<McpSchema.Tool> tools = client.listTools().block();
            assertNotNull(tools, "工具列表不应为 null");
            log.info("12306 MCP 工具列表: {}", tools.stream().map(McpSchema.Tool::name).toList());

            List<String> names = tools.stream().map(McpSchema.Tool::name).toList();
            assertTrue(names.contains("get-tickets"), "应包含 get-tickets 工具");
            assertTrue(names.contains("get-current-date"), "应包含 get-current-date 工具");
        }
    }

    @Test
    @DisplayName("查询北京→上海明日火车票（集成测试 - 需 MCP_TEST_12306=true）")
    @EnabledIfEnvironmentVariable(named = "MCP_TEST_12306", matches = "true")
    void test12306_queryTickets_beijingToShanghai() {
        String date = LocalDate.now().plusDays(1).format(FMT);

        try (McpClientWrapper client = build12306Client()) {
            McpSchema.CallToolResult result = client.callTool("get-tickets", Map.of(
                    "date", date,
                    "fromStation", "北京",
                    "toStation", "上海",
                    "format", "json"
            )).block();

            assertNotNull(result, "调用结果不应为 null");
            String text = extractText(result);
            log.info("北京→上海 ({}) 火车票查询结果:\n{}", date, text);

            assertFalse(text.isBlank(), "返回文本不应为空");
            assertFalse(Boolean.TRUE.equals(result.isError()), "调用不应返回错误: " + text);
        }
    }

    @Test
    @DisplayName("解析相对日期并查询杭州→成都高铁（集成测试 - 需 MCP_TEST_12306=true）")
    @EnabledIfEnvironmentVariable(named = "MCP_TEST_12306", matches = "true")
    void test12306_queryTickets_hangzhouToChengdu_gdFilter() {
        String date = LocalDate.now().plusDays(2).format(FMT);

        try (McpClientWrapper client = build12306Client()) {
            McpSchema.CallToolResult result = client.callTool("get-tickets", Map.of(
                    "date", date,
                    "fromStation", "杭州",
                    "toStation", "成都",
                    "trainFilterFlags", "GD",
                    "sortFlag", "startTime",
                    "format", "json"
            )).block();

            assertNotNull(result, "调用结果不应为 null");
            String text = extractText(result);
            log.info("杭州→成都 ({}, GD 筛选) 火车票查询结果:\n{}", date, text);

            assertFalse(text.isBlank(), "返回文本不应为空");
        }
    }

    // ==================== 飞常准飞机票（集成测试，需 VARIFLIGHT_API_KEY） ====================

    @Test
    @DisplayName("飞常准 MCP 连接并列出工具（集成测试 - 需 VARIFLIGHT_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "VARIFLIGHT_API_KEY", matches = ".+")
    void testVariflight_listTools() {
        try (McpClientWrapper client = buildVariflightClient()) {
            List<McpSchema.Tool> tools = client.listTools().block();
            assertNotNull(tools, "工具列表不应为 null");
            log.info("飞常准 MCP 工具列表: {}", tools.stream().map(McpSchema.Tool::name).toList());

            List<String> names = tools.stream().map(McpSchema.Tool::name).toList();
            assertTrue(names.contains("searchFlightsByDepArr"), "应包含 searchFlightsByDepArr 工具");
            assertTrue(names.contains("getFlightPriceByCities"), "应包含 getFlightPriceByCities 工具");
        }
    }

    @Test
    @DisplayName("查询北京→上海明日航班票价（集成测试 - 需 VARIFLIGHT_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "VARIFLIGHT_API_KEY", matches = ".+")
    void testVariflight_queryFlightPrice_beijingToShanghai() {
        String date = LocalDate.now().plusDays(1).format(FMT);

        try (McpClientWrapper client = buildVariflightClient()) {
            McpSchema.CallToolResult result = client.callTool("getFlightPriceByCities", Map.of(
                    "dep_city", "BJS",
                    "arr_city", "SHA",
                    "dep_date", date
            )).block();

            assertNotNull(result, "调用结果不应为 null");
            String text = extractText(result);
            log.info("北京→上海 ({}) 航班票价查询结果:\n{}", date, text);

            assertFalse(text.isBlank(), "返回文本不应为空");
        }
    }

    // ==================== 辅助方法（与 AgentConfig 的注册逻辑保持一致） ====================

    private McpClientWrapper build12306Client() {
        McpClientWrapper client = McpClientBuilder.create("c12306-test")
                .stdioTransport(npxCommand(), npxArgs("-y", "12306-mcp"), Map.of())
                .buildSync();
        ensureInitialized(client);
        return client;
    }

    private McpClientWrapper buildVariflightClient() {
        String apiKey = System.getenv("VARIFLIGHT_API_KEY");
        McpClientWrapper client = McpClientBuilder.create("variflight-test")
                .stdioTransport(npxCommand(), npxArgs("-y", "@variflight-ai/variflight-mcp"),
                        Map.of("VARIFLIGHT_API_KEY", apiKey))
                .buildSync();
        ensureInitialized(client);
        return client;
    }

    private void ensureInitialized(McpClientWrapper client) {
        if (!client.isInitialized()) {
            client.initialize().block();
        }
    }

    private String npxCommand() {
        return isWindows() ? "cmd" : "npx";
    }

    private List<String> npxArgs(String... npxArgs) {
        java.util.List<String> args = new java.util.ArrayList<>();
        if (isWindows()) {
            args.add("/c");
            args.add("npx");
        }
        args.addAll(List.of(npxArgs));
        return args;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String extractText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                sb.append(textContent.text());
            }
        }
        return sb.toString();
    }
}
