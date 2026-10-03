package com.travelscope.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReviewerAgent 分段审核真实验收测试（FR-S08 v3.2 / B1）。
 * <p>
 * 不经 Spring 上下文，直接用 ReviewerAgent.SYS_PROMPT + qwen-max 构建 HarnessAgent
 * （IntentCascadeRealApiTest 门控先例 + RubricScorer 单 Agent 直跑骨架），把一份
 * 真实超预算的 itinerary_draft.md（杭州 2 日 500 元预算、总计 642 超支 142，
 * 取自 conv-52 实测样本）喂给 reviewer，验证分段审核契约：
 * review_report.md 含分段结论表/总分行（SCORE_PATTERN 兼容）/改进建议，
 * 回复首行带扩展标记 REVIEW_RESULT: FAIL 总分=xx 失败段=…（预算段必失败）。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "API_KEY", matches = "sk-.+")
class ReviewerSegmentedReviewRealApiTest {

    /** 与 AgentConfig 中 reviewer 声明一致的最大迭代 */
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(5);

    /** conv-52 实测超预算草案（500 元预算 / 2 人，总计 642 元超支 142 元） */
    private static final String OVER_BUDGET_ITINERARY = """
            # 杭州2日游行程草案（预算500元/2人）

            ## 第1天（10月25日 周六）
            | 时间 | 行程 | 地点 | 费用 | 点间通勤 |
            |---|---|---|---|---|
            | 08:00-09:30 | 高铁 G7315 上海虹桥→杭州东 | 杭州东站 | ¥73/人 | - |
            | 10:00-12:00 | 西湖环湖（断桥→白堤→苏堤，步行） | 西湖 | 免费 | 步行 |
            | 12:10-13:00 | 午餐（新丰小吃湖滨店） | 湖滨 | ¥25/人 | 步行 10分钟 |
            | 13:30-15:30 | 河坊街+南宋御街 | 河坊街 | 免费 | 地铁+步行 20分钟 |
            | 16:00-17:00 | 杭州博物馆（免费预约） | 粮道山 | 免费 | 步行 10分钟 |
            | 17:30 | 入住青旅（床位） | 南山路 | ¥70/床 | 公交 15分钟 |

            ## 第2天（10月26日 周日）
            | 时间 | 行程 | 地点 | 费用 | 点间通勤 |
            |---|---|---|---|---|
            | 09:00-11:00 | 太子湾公园+苏堤晨线 | 太子湾 | 免费 | 步行 10分钟 |
            | 11:10-12:10 | 午餐（河坊街小吃） | 河坊街 | ¥30/人 | 公交 15分钟 |
            | 13:00-15:00 | 中国丝绸博物馆（免费预约） | 玉皇山路 | 免费 | 公交 20分钟 |
            | 15:40-17:00 | 京杭大运河博物馆+拱宸桥 | 拱墅区 | 免费 | 地铁 25分钟 |
            | 18:26-19:56 | 高铁 G165 杭州东→上海虹桥 | 杭州东站 | ¥73/人 | 地铁 20分钟 |

            ## 费用汇总（2人合计）
            - 城际高铁往返：¥292（¥73×2×2）
            - 住宿：¥140（青旅床位 ¥70×2）
            - 餐饮：¥150
            - 市内交通：¥60
            - **总计：¥642**——超出预算 500 元达 ¥142。

            > 天气提示：出行两日晴，12~22℃，适合户外游览。
            """;

    @Test
    @DisplayName("超预算草案 → review_report.md 含分段结论表与失败段标记，首行 REVIEW_RESULT: FAIL 总分=xx 失败段含 3")
    void segmentedReviewOnOverBudgetItinerary() throws Exception {
        // 自管临时目录（不用 @TempDir：harness 工作区在 Windows 下句柄未释放，TempDir 强制删除会误报失败）
        Path workspaceRoot = Files.createTempDirectory("reviewer-seg-test-");
        try {
            runSegmentedReview(workspaceRoot);
        } finally {
            deleteRecursivelyBestEffort(workspaceRoot);
        }
    }

    private void runSegmentedReview(Path workspaceRoot) throws IOException, InterruptedException {
        // ---- 准备协作目录与超预算草案（文件工具把相对路径解析到 {workspace}/{userId}/ 之下）----
        String userId = "1";
        String collabDir = "tasks/conv-9001";
        Path taskDir = workspaceRoot.resolve(userId).resolve("tasks").resolve("conv-9001");
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("itinerary_draft.md"), OVER_BUDGET_ITINERARY, StandardCharsets.UTF_8);

        // ---- 构建 reviewer：同 SYS_PROMPT + qwen-max；核验工具用确定性桩（本测试验证提示词契约：
        // 分段结构/产物文件/标记行，不验证真实核验工具质量。真实 AMAP/和风 API 有配额限流，
        // 限流时模型进入降级态会让测试随外部环境抖动；桩数据合理且恒定，模型保持工具调用行为）----
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new VerificationStubs());

        HarnessAgent reviewer = HarnessAgent.builder()
                .name(ReviewerAgent.AGENT_NAME)
                .sysPrompt(ReviewerAgent.SYS_PROMPT)
                .model(DashScopeChatModel.builder()
                        .apiKey(System.getenv("API_KEY"))
                        .modelName("qwen-max")
                        .stream(true)
                        .build())
                .toolkit(toolkit)
                .maxIters(ReviewerAgent.MAX_ITERS)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS).build())
                .workspace(workspaceRoot.toString())
                .stateStore(new InMemoryAgentStateStore())
                .build();

        // ---- 模拟 planner 的委派任务说明（协作目录 + 需求摘要，SYS_PROMPT 第一节的输入约定）----
        String task = "协作目录: " + collabDir + "\n"
                + "用户需求摘要：杭州 2 日游（10月25日~26日），2 人同行，总预算 500 元（硬约束，含往返高铁）。\n"
                + "请审核行程草案。";
        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId("conv-9001").build();

        StringBuilder replyBuf = new StringBuilder();
        reviewer.streamEvents(new UserMessage(task), ctx)
                .doOnNext(event -> {
                    // 与生产口径一致：planner 收到的 spawn 结果是 reviewer 的【最终消息】，
                    // 不含 ReAct 中间轮的核验叙述——工具轮结束即重置缓冲，只保留最后
                    // 一个工具结果之后那轮推理的文本（首行即 REVIEW_RESULT 标记）。
                    if (event instanceof ToolResultEndEvent) {
                        replyBuf.setLength(0);
                    } else if (event instanceof TextBlockDeltaEvent e
                            && (e.getSource() == null || !e.getSource().contains("/"))) {
                        replyBuf.append(e.getDelta());
                    }
                })
                .blockLast(RUN_TIMEOUT);

        // ---- 契约断言：最终回复含可解析的 REVIEW_RESULT 标记行 ----
        // B2 planner 按段路由的机器输入。硬契约 = 标记行存在且结构精确（LLM 读全文消费）；
        // 「字面首行」是提示词目标但 qwen-max 遵从非 100%，作为可观测项打印不作为硬断言，
        // 防止门禁随模型方差随机抖动。文件里的「- 失败段:」行是机器解析通道（下方断言）。
        String reply = replyBuf.toString().strip();
        System.out.println("[REVIEW-TEST] reviewer 回复:\n" + reply);
        assertFalse(reply.isBlank(), "reviewer 应有最终回复");
        Pattern markerLine = Pattern.compile(
                "^[\\p{Punct}【】（）“”‘’]*REVIEW_RESULT: FAIL 总分=(\\d+) 失败段=(无|[123](,[123])*)[\\p{Punct}【】（）“”‘’]*$");
        Matcher marker = null;
        for (String line : reply.split("\n")) {
            Matcher candidate = markerLine.matcher(line.strip());
            if (candidate.matches()) {
                marker = candidate;
                break;
            }
        }
        assertTrue(marker != null,
                "最终回复应含 REVIEW_RESULT: FAIL 总分=xx 失败段=… 标记行，实际回复:\n" + reply);
        boolean markerIsFirstLine = markerLine.matcher(reply.split("\n", 2)[0].strip()).matches();
        System.out.println("[REVIEW-TEST] 标记行: REVIEW_RESULT: FAIL 总分=" + marker.group(1)
                + " 失败段=" + marker.group(2) + (markerIsFirstLine ? "（字面首行 ✓）" : "（非字面首行，提示词遵从提示项）"));
        assertTrue(marker.group(2).contains("3"),
                "预算 642 超支 142 元，失败段应含 3（预算+偏好匹配），实际: " + marker.group(2));

        // ---- 契约断言：review_report.md 分段格式 + 兼容性 ----
        Path report = taskDir.resolve("review_report.md");
        assertTrue(Files.exists(report), "不通过分支应产出 review_report.md");
        String reportText = Files.readString(report, StandardCharsets.UTF_8);
        System.out.println("[REVIEW-TEST] review_report.md:\n" + reportText);

        assertTrue(reportText.contains("分段结论"), "报告应含分段结论表");
        assertTrue(reportText.contains("失败段"), "报告应含失败段行（planner 路由依据）");
        assertTrue(Pattern.compile("总分[:：]\\s*\\d+").matcher(reportText).find(),
                "总分行格式须可被 ReviewerRetryMiddleware 的 SCORE_PATTERN 命中（兼容性）");
        assertTrue(reportText.contains("改进建议"), "报告应含改进建议节（planner 回炉指令）");
        assertFalse(Files.exists(taskDir.resolve("review_passed.md")),
                "FAIL 分支不应写 review_passed.md（二选一契约）");
    }

    /**
     * 确定性核验工具桩（与真实工具同名同语义，数据恒定合理，不依赖 AMAP/和风外部配额）：
     * 让模型保持真实生产中的工具调用行为（读文件→核验→写文件→输出标记），
     * 同时消除外部 API 限流导致的降级态抖动。
     */
    @SuppressWarnings("unused")
    static final class VerificationStubs {

        @Tool(description = "地址 → 坐标（返回 JSON：formatted_address/location）")
        public String geocode(String address) {
            long h = Math.abs(address.hashCode());
            double lng = 120.10 + (h % 100) / 1000.0;
            double lat = 30.20 + (h / 100 % 100) / 1000.0;
            return "{\"status\":\"1\",\"geocodes\":[{\"formatted_address\":\"浙江省杭州市" + address
                    + "\",\"location\":\"" + String.format(java.util.Locale.ROOT, "%.6f,%.6f", lng, lat) + "\"}]}";
        }

        @Tool(description = "公共交通路线核验：返回 duration（秒）/ distance（米）")
        public String getTransitRoute(String origin, String destination, String city) {
            return "{\"status\":\"1\",\"route\":{\"duration\":1200,\"distance\":5000}}";
        }

        @Tool(description = "驾车路线核验：返回 duration（秒）/ distance（米）")
        public String getDrivingRoute(String origin, String destination) {
            return "{\"status\":\"1\",\"route\":{\"duration\":900,\"distance\":4000}}";
        }

        @Tool(description = "查询指定城市实时天气（温度/天气状况）")
        public String getWeather(String city) {
            return "{\"now\":{\"text\":\"晴\",\"temp\":\"20\"}}";
        }
    }

    /** 尽力清理（harness 工作区句柄未释放时残留无害，测试已不依赖目录删除） */
    private static void deleteRecursivelyBestEffort(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Windows 下句柄未释放，残留临时目录无害
                }
            });
        } catch (IOException ignored) {
            // 根目录不可访问，放弃清理
        }
    }
}
