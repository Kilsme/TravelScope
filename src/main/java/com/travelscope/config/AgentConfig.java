package com.travelscope.config;

import com.travelscope.agent.ItineraryAgent;
import com.travelscope.agent.TravelMasterAgent;
import com.travelscope.agent.tools.HotelTool;
import com.travelscope.agent.tools.TransportTool;
import com.travelscope.agent.tools.WeatherTool;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AgentScope 多智能体配置类
 * <p>
 * 组装架构（1主1从）：
 * <pre>
 * 主 Agent（TravelMasterAgent）
 *  ├── 工具：WeatherTool / HotelTool / TransportTool
 *  └── 子 Agent → 规划 Agent（ItineraryAgent / planning-agent）
 * </pre>
 * </p>
 * <p>
 * 协作流程：
 * <ol>
 *   <li>主 Agent 意图识别 + 任务拆分</li>
 *   <li>主 Agent 写入 task_backlog.md 到共享任务区间</li>
 *   <li>主 Agent 委派规划 Agent 执行</li>
 *   <li>规划 Agent 读取 task_backlog.md，调用工具，写回 execution_result.md</li>
 *   <li>规划 Agent 综合生成 itinerary_draft.md</li>
 *   <li>主 Agent 读取结果，汇总返回用户</li>
 * </ol>
 * </p>
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private final AppProperties appProperties;
    private final WeatherTool weatherTool;
    private final HotelTool hotelTool;
    private final TransportTool transportTool;

    public AgentConfig(AppProperties appProperties,
                       WeatherTool weatherTool,
                       HotelTool hotelTool,
                       TransportTool transportTool) {
        this.appProperties = appProperties;
        this.weatherTool = weatherTool;
        this.hotelTool = hotelTool;
        this.transportTool = transportTool;
    }

    /**
     * 创建 Toolkit（工具集）
     * <p>
     * 注册天气查询、酒店搜索、交通查询工具，供主 Agent 和规划 Agent 共同使用。
     * </p>
     */
    @Bean
    public Toolkit travelToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTool);
        toolkit.registerTool(hotelTool);
        toolkit.registerTool(transportTool);
        log.info("Toolkit 注册完成: 天气查询、酒店搜索、交通查询");
        return toolkit;
    }

    /**
     * 创建主 Agent（TravelMasterAgent）
     * <p>
     * 主 Agent 负责：
     * - 意图识别（判断是简单查询还是行程规划）
     * - 任务拆分（将复杂需求拆分为具体任务清单）
     * - 写入 task_backlog.md 到共享任务区间
     * - 委派规划 Agent 执行
     * - 读取执行结果，汇总返回用户
     * </p>
     */
    @Bean
    public HarnessAgent travelMasterAgent(Toolkit toolkit) {
        SubagentDeclaration planningSubAgent = SubagentDeclaration.builder()
                .name(ItineraryAgent.AGENT_NAME)
                .description("规划 Agent，负责执行主 Agent 分配的任务清单，调用工具获取实时数据，生成行程方案")
                .inlineAgentsBody(ItineraryAgent.SYS_PROMPT)
                .model(appProperties.getDashscope().getModel())
                .build();

        HarnessAgent agent = HarnessAgent.builder()
                .name(TravelMasterAgent.AGENT_NAME)
                .sysPrompt(TravelMasterAgent.SYS_PROMPT)
                .model(appProperties.getDashscope().getModel())
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .subagent(planningSubAgent)
                .build();

        log.info("主 Agent 构建完成: {} (含 1 个规划子 Agent: {})",
                TravelMasterAgent.AGENT_NAME, ItineraryAgent.AGENT_NAME);
        return agent;
    }

    /**
     * 创建共享任务区间服务 Bean
     */
    @Bean
    public TaskWorkspaceService taskWorkspaceService() {
        return new TaskWorkspaceService(appProperties);
    }

    /**
     * 共享任务区间服务
     * <p>
     * 管理主 Agent 与规划 Agent 之间的共享任务区间（一组 Markdown 文件）。
     * 每个 session 对应一个独立的任务目录：
     * <pre>
     * {workspacePath}/tasks/{sessionId}/
     *   ├── task_backlog.md       主 Agent 写入的任务清单
     *   ├── execution_result.md   规划 Agent 写回的执行结果
     *   ├── itinerary_draft.md    规划 Agent 生成的行程草案
     *   └── session_meta.md       会话元信息
     * </pre>
     * </p>
     */
    @Service
    public static class TaskWorkspaceService {

        private static final Logger wsLog = LoggerFactory.getLogger(TaskWorkspaceService.class);

        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        public static final String FILE_TASK_BACKLOG = "task_backlog.md";
        public static final String FILE_EXECUTION_RESULT = "execution_result.md";
        public static final String FILE_ITINERARY_DRAFT = "itinerary_draft.md";
        public static final String FILE_SESSION_META = "session_meta.md";

        private final String workspaceRoot;

        public TaskWorkspaceService(AppProperties appProperties) {
            this.workspaceRoot = appProperties.getAgentscope().getWorkspacePath();
        }

        // ==================== 核心方法 ====================

        /**
         * 初始化任务区间（创建目录 + 元信息文件）
         *
         * @param sessionId 会话ID
         * @return 任务区间根路径
         */
        public Path initTaskWorkspace(String sessionId) {
            Path dir = getTaskDir(sessionId);
            try {
                Files.createDirectories(dir);
                String meta = """
                        # 会话元信息

                        - 会话ID: %s
                        - 创建时间: %s
                        - 状态: 进行中
                        - 参与者: travel-master, planning-agent
                        """.formatted(sessionId, LocalDateTime.now().format(FMT));
                writeFile(sessionId, FILE_SESSION_META, meta);
                wsLog.info("任务区间初始化: sessionId={}, dir={}", sessionId, dir);
                return dir;
            } catch (IOException e) {
                wsLog.error("初始化任务区间失败: sessionId={}", sessionId, e);
                throw new RuntimeException("初始化任务区间失败: " + e.getMessage(), e);
            }
        }

        /**
         * 写入任务清单（task_backlog.md）—— 主 Agent 调用
         *
         * @param sessionId 会话ID
         * @param content    任务清单 Markdown 内容
         */
        public void writeTaskBacklog(String sessionId, String content) {
            writeFile(sessionId, FILE_TASK_BACKLOG, content);
            wsLog.info("任务清单已写入: sessionId={}", sessionId);
        }

        /**
         * 读取任务清单（task_backlog.md）—— 规划 Agent 调用
         *
         * @param sessionId 会话ID
         * @return 任务清单内容，不存在返回 null
         */
        public String readTaskBacklog(String sessionId) {
            return readFile(sessionId, FILE_TASK_BACKLOG);
        }

        /**
         * 追加执行结果（execution_result.md）—— 规划 Agent 调用
         *
         * @param sessionId 会话ID
         * @param taskId    任务ID
         * @param result    执行结果内容
         */
        public void appendExecutionResult(String sessionId, String taskId, String result) {
            String block = """

                    ## 执行结果: %s
                    - 时间: %s

                    %s

                    ---
                    """.formatted(taskId, LocalDateTime.now().format(FMT), result);

            Path file = getTaskDir(sessionId).resolve(FILE_EXECUTION_RESULT);
            try {
                if (!Files.exists(file)) {
                    String header = "# 执行结果记录\n\n会话ID: " + sessionId + "\n";
                    Files.writeString(file, header + block, StandardCharsets.UTF_8);
                } else {
                    Files.writeString(file, block, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                }
                wsLog.info("执行结果已追加: sessionId={}, taskId={}", sessionId, taskId);
            } catch (IOException e) {
                wsLog.error("写入执行结果失败: sessionId={}, taskId={}", sessionId, taskId, e);
            }
        }

        /**
         * 读取执行结果（execution_result.md）—— 主 Agent 调用
         */
        public String readExecutionResult(String sessionId) {
            return readFile(sessionId, FILE_EXECUTION_RESULT);
        }

        /**
         * 写入行程草案（itinerary_draft.md）—— 规划 Agent 调用
         */
        public void writeItineraryDraft(String sessionId, String content) {
            writeFile(sessionId, FILE_ITINERARY_DRAFT, content);
            updateSessionStatus(sessionId, "已完成");
            wsLog.info("行程草案已写入: sessionId={}", sessionId);
        }

        /**
         * 读取行程草案（itinerary_draft.md）—— 主 Agent 调用
         */
        public String readItineraryDraft(String sessionId) {
            return readFile(sessionId, FILE_ITINERARY_DRAFT);
        }

        // ==================== 工具方法 ====================

        /**
         * 获取任务区间目录路径
         */
        public Path getTaskDir(String sessionId) {
            return Paths.get(workspaceRoot, "tasks", sessionId);
        }

        private void writeFile(String sessionId, String fileName, String content) {
            Path dir = getTaskDir(sessionId);
            try {
                Files.createDirectories(dir);
                Path file = dir.resolve(fileName);
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                wsLog.error("写入文件失败: sessionId={}, file={}", sessionId, fileName, e);
                throw new RuntimeException("写入文件失败: " + e.getMessage(), e);
            }
        }

        private String readFile(String sessionId, String fileName) {
            Path file = getTaskDir(sessionId).resolve(fileName);
            if (!Files.exists(file)) {
                wsLog.warn("文件不存在: sessionId={}, file={}", sessionId, fileName);
                return null;
            }
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                wsLog.error("读取文件失败: sessionId={}, file={}", sessionId, fileName, e);
                return null;
            }
        }

        private void updateSessionStatus(String sessionId, String status) {
            Path file = getTaskDir(sessionId).resolve(FILE_SESSION_META);
            try {
                if (Files.exists(file)) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    content = content.replaceAll("状态: .*", "状态: " + status);
                    content += "\n- 更新时间: " + LocalDateTime.now().format(FMT);
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                wsLog.error("更新会话状态失败: sessionId={}", sessionId, e);
            }
        }
    }
}
