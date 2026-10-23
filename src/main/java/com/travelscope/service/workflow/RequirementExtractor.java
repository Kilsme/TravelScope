package com.travelscope.service.workflow;

import com.travelscope.common.TurnCancellation;

import com.alibaba.fastjson2.JSON;
import com.travelscope.config.AppProperties;
import com.travelscope.service.workflow.dto.RequirementExtraction;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * 需求抽取器（S2.1，v4 计划 §6.2）：一次 LLM 调用从用户消息抽取 8 个需求字段，
 * 供 S2.2 RequirementWorkflowService merge 进需求状态机（清单卡片方案：抽取结果
 * 预填表单，全部字段亮给用户确认——抽取失败只意味着表单缺项显示，非致命）。
 * <p>
 * 模型走主池（v5-F3 起 {@code gatewayChatModel}，glm 优先 + 池内 failover；旧
 * gatewayLightModel 轻量档随单 fallback 装饰退役）。结构化输出采用仓库既有惯例
 * {@code agent.call(input, Class, ctx)}（同 LightweightIntentClassifier /
 * ConversationSummaryService / RubricScorer 三先例；当前配置
 * native-structured-output=false → 合成 generate_response 工具路径），
 * {@link #invokeModel} 内做结构化 → 纯文本双路解析。
 * </p>
 * <p>
 * 失败语义（卡片点 4）：模型异常/超时/解析失败一律返回 {@link RequirementExtraction#EMPTY}
 * 不抛——由状态机走表单/反问兜底。<b>单次调用不重试</b>：与 L2 分类器重试的场景不同
 * （分类失败会误路由），抽取失败只是表单少预填几格，用户手填兜底，不值得双倍延迟。
 * </p>
 */
@Service
public class RequirementExtractor {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.travelscope.assessment.ObservationMiddleware observationMiddleware;

    private static final Logger log = LoggerFactory.getLogger(RequirementExtractor.class);

    /** 单次调用超时：抽取失败非致命（表单缺项显示），超时即放弃不恋战 */
    private static final Duration EXTRACT_TIMEOUT = Duration.ofSeconds(15);

    private static final String SYS_PROMPT = """
            你是旅行需求抽取器。从用户消息中抽取以下 8 个字段，每个字段输出 value 与 confidence（0~1 小数）。
            用户没提到或没把握的字段，value 和 confidence 都填 null。

            字段定义：
            - destination：目的地城市（用户要去游玩的城市；途经地不算——「路过杭州去北京」的 destination 是北京）
            - days：游玩天数（数字，如「三天」→ 3）
            - startDate：出发日期（YYYY-MM-DD；相对日期按输入给出的「今天是」推算，如「下周六」）
            - fromCity：出发城市
            - budget：预算（保留用户原文表述，如「人均一千」「5000 以内」）
            - preference：偏好（用户原话关键词，如「自然风景、人文古迹」）
            - people：人数（数字；仅用户明确说了总人数才填——「带爸妈」没说总数则 null）
            - special：特殊需求（用户原话，如「不吃辣」「带老人小孩」）

            规则（宁缺毋滥）：
            - 只填用户明确表达或可直接换算的字段；模糊、推测、联想出来的一律 null
            - 不要编造；不确定就是 null
            """;

    private final Model lightModel;
    private final AppProperties appProperties;

    public RequirementExtractor(@Qualifier("gatewayChatModel") Model mainModelPool,
                                AppProperties appProperties) {
        this.lightModel = mainModelPool;
        this.appProperties = appProperties;
    }

    /**
     * 抽取 8 需求字段。
     *
     * @param userMessage 用户消息原文（首条规划消息，或清单卡片期间的自然语言增量修改）
     * @return 过滤后的字段值 + 模型原始输出；空白输入/模型异常/超时/解析失败
     *         返回 {@link RequirementExtraction#EMPTY}（不抛）
     */
    public RequirementExtraction extract(String userMessage) {
        return extract(userMessage, null);
    }

    public RequirementExtraction extract(String userMessage,
            com.travelscope.assessment.ObservationContext observation) {
        if (userMessage == null || userMessage.isBlank()) {
            return RequirementExtraction.EMPTY;
        }
        try {
            String input = buildInput(userMessage);
            String rawJson = observation == null ? invokeModel(input) : invokeModel(input, observation);
            RequirementExtraction extraction = RequirementExtraction.parse(
                    rawJson, appProperties.getRequirement().getExtractionMinConfidence());
            log.info("需求抽取完成: 字段数={}, 消息长度={}", extraction.fields().size(), userMessage.length());
            return extraction;
        } catch (Exception e) {
            TurnCancellation.rethrow(e, observation);
            log.warn("需求抽取失败（按空结果降级，表单/反问兜底）: {}", e.getMessage());
            return RequirementExtraction.EMPTY;
        }
    }

    /** 抽取输入：今天日期作相对日期换算基准（「明天/下周六」）+ 用户消息 */
    static String buildInput(String userMessage) {
        LocalDate today = LocalDate.now();
        return "今天是 " + today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "（" + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA) + "）。"
                + "\n\n用户消息：" + userMessage;
    }

    /**
     * 单次模型调用（包私有桩点：测试覆写注入 canned JSON / 异常，绕开 ReActAgent 循环——
     * 框架 agent 循环无法在 Model 层简单 stub，见 LightweightIntentClassifierTest 走真实
     * API 门控的既有结论）。返回模型原始 JSON 文本（结构化对象序列化回 JSON，或纯文本兜底）。
     */
    String invokeModel(String input) {
        return invokeModel(input, null);
    }

    String invokeModel(String input, com.travelscope.assessment.ObservationContext observation) {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("requirement-extractor")
                .sessionId("requirement-extraction")
                .build();
        var cancellation = TurnCancellation.from(null);
        if (cancellation != null) ctx.put(TurnCancellation.KEY, cancellation);
        if (observation != null) {
            ctx.put(com.travelscope.assessment.ObservationContext.KEY, observation);
        }
        var builder = ReActAgent.builder()
                .name("requirement-extractor")
                .sysPrompt(SYS_PROMPT)
                .model(lightModel)
                .maxIters(2);
        if (observationMiddleware != null) {
            builder.middlewares(List.of(observationMiddleware));
        }
        var invocation = builder.build().call(input, ExtractedFields.class, ctx);
        if (observation != null) {
            invocation = invocation.contextWrite(c -> c.put(
                    com.travelscope.assessment.ObservationContext.KEY, observation));
        }
        Msg reply = TurnCancellation.await(invocation, EXTRACT_TIMEOUT, observation);
        if (reply == null) {
            log.warn("需求抽取模型返回为空");
            return "";
        }
        // 双路解析（同 LightweightIntentClassifier 惯例）：结构化 → 纯文本兜底
        try {
            ExtractedFields structured = reply.getStructuredData(ExtractedFields.class);
            if (structured != null) {
                return JSON.toJSONString(structured);
            }
        } catch (Exception ignored) {
            // metadata 无 _structured_output 键（轻量模型常见），落纯文本
        }
        return reply.getTextContent() == null ? "" : reply.getTextContent();
    }

    /**
     * 模型结构化输出载体（IntentResult/RubricScores 同款惯例：public 字段 POJO +
     * 无参构造，安全接住模型输出；schema 由框架自动生成）。
     */
    public static class ExtractedFields {
        public FieldVal destination;
        public FieldVal days;
        public FieldVal startDate;
        public FieldVal fromCity;
        public FieldVal budget;
        public FieldVal preference;
        public FieldVal people;
        public FieldVal special;
    }

    /**
     * 单字段值：value 为模型原样的字符串或数字（days/people 的 Integer 转换是
     * S2.2 merge 的职责），confidence 为 0~1 或 null。
     */
    public static class FieldVal {
        public Object value;
        public Double confidence;
    }
}
