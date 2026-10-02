package com.travelscope.eval;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.config.AppProperties;
import com.travelscope.entity.Conversation;
import com.travelscope.entity.EvaluationRecord;
import com.travelscope.entity.User;
import com.travelscope.service.ChatService;
import com.travelscope.service.ConversationService;
import com.travelscope.service.EvaluationRecordService;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.travelscope.config.AgentConfig.TaskWorkspaceService;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvalCase 双轨评估 runner（FR-S15 / A5）：真实跑完整多智能体链路，
 * 确定性规则 + qwen-max Rubric 双轨判定，结果写入 evaluation_records，作为合并门禁。
 *
 * <p>门控（RagServiceImplTest 组合门控先例）：{@code EVAL_REAL_API=1} 且 API_KEY 为 sk- 前缀，
 * 且本机 PostgreSQL 5432（全上下文启动与落库必需）与 dashscope.aliyuncs.com:443 可达才执行；
 * 否则整类跳过、Spring 上下文不启动，mvn test 不受影响（规则断言器单测见 RuleAssertersTest）。</p>
 *
 * <p>驱动方式：绕过 Controller 直接调 {@link ChatService#streamChat}（MOCK web 环境，
 * 默认 profile——不能用 test profile，会排除 DataSource）；轮次完成信号 = messages 表中
 * 该会话 assistant 消息数达到轮次序号（handleComplete/handleError 均经 persistReply 落库，
 * 链路失败轮不落 → 轮询超时判 FAIL，与慢泳道 300s 上限语义一致）。
 * 首个 @SpringBootTest：启动副作用已核实（KnowledgeIngestRunner 默认关闭、MinIO/Redis/MCP 失败均降级）。</p>
 */
@SpringBootTest
@EnabledIf(value = "com.travelscope.eval.EvalCaseRunnerTest#realApiEnabled",
        disabledReason = "需 EVAL_REAL_API=1 且 API_KEY=sk-.. 且本机 PostgreSQL(5432)、dashscope.aliyuncs.com:443 可达"
                + "（docker-compose up + 本地 PG 后重跑）")
class EvalCaseRunnerTest {

    @Autowired
    private ChatService chatService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private EvaluationRecordService evaluationRecordService;
    @Autowired
    private TaskWorkspaceService taskWorkspaceService;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectProvider<Tracer> tracerProvider;
    /** qwen-max 评分模型（复用 dashscopeReviewerModel Bean，即 travelscope.dashscope.reviewer-model 配置） */
    @Autowired
    @Qualifier("dashscopeReviewerModel")
    private DashScopeChatModel reviewerModel;

    /** 轮询间隔 */
    private static final long POLL_INTERVAL_MS = 2000;

    /** 门控条件（静态方法，Spring 上下文启动前执行，只碰环境变量与 Socket） */
    static boolean realApiEnabled() {
        if (!"1".equals(System.getenv("EVAL_REAL_API"))) {
            return false;
        }
        String apiKey = System.getenv("API_KEY");
        if (apiKey == null || !apiKey.startsWith("sk-")) {
            return false;
        }
        return portReachable("localhost", 5432)
                && portReachable("dashscope.aliyuncs.com", 443);
    }

    private static boolean portReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("EvalCase 双轨回归门禁：10 条用例真实执行，规则 + Rubric 全绿才通过")
    void runAllEvalCasesAsRegressionGate() {
        ensureEvaluationTable();
        List<EvalCase> cases = EvalCaseLoader.loadAll();
        System.out.printf("[EVAL] 开始执行 %d 条 EvalCase（reviewer 模型: %s）%n",
                cases.size(), appProperties.getDashscope().getReviewerModel());

        List<CaseReport> reports = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            CaseReport report = runCase(evalCase);
            reports.add(report);
            System.out.printf("[EVAL] %s %s(%s) 规则:%s Rubric:%s%s%n",
                    report.passed() ? "PASS" : "FAIL",
                    report.caseId(), report.category(),
                    ruleSummary(report.ruleResults()),
                    report.rubric() != null ? report.rubric().normalizedTotal() + "/100（门槛 " + report.minTotal() + "）" : "未评分",
                    report.error() != null ? " 原因: " + report.error() : "");
        }

        List<CaseReport> failed = reports.stream().filter(r -> !r.passed()).toList();
        List<CaseReport> notPersisted = reports.stream().filter(r -> !r.persisted()).toList();
        assertTrue(notPersisted.isEmpty(),
                "存在未写入 evaluation_records 的用例结果: " + notPersisted.stream().map(CaseReport::caseId).toList());
        assertTrue(failed.isEmpty(),
                "EvalCase 回归门禁未通过（" + failed.size() + "/" + reports.size() + " 条 FAIL）: " + formatFailures(failed));
    }

    // ==================== 单用例执行 ====================

    /**
     * 执行单个用例：开 eval-case span（chat-turn 自动挂其下，同一 trace）→ 逐轮驱动对话 →
     * 采集产物 → 规则轨 + Rubric 轨 → 落库。任何环节失败都落库留痕并返回 FAIL 报告。
     */
    private CaseReport runCase(EvalCase evalCase) {
        List<RuleAssertion> ruleResults = new ArrayList<>();
        RubricScores rubric = null;
        String traceId = null;
        String error = null;
        Tracer tracer = tracerProvider.getIfAvailable();
        Span span = tracer != null ? tracer.nextSpan().name("eval-case").start() : null;
        try (Tracer.SpanInScope scope = span != null ? tracer.withSpan(span) : null) {
            if (span != null) {
                span.tag("caseId", evalCase.caseId());
                span.tag("category", evalCase.category().name());
                traceId = span.context().traceId();
            }
            User user = conversationService.getOrCreateGuestUser();
            Conversation conversation = conversationService.createConversation(user.getId());
            error = driveTurns(evalCase, conversation, user.getId());
            if (error == null) {
                EvalRunArtifact artifact = collectArtifact(user.getId(), conversation.getId());
                for (EvalCase.Rule rule : evalCase.expectations().rules()) {
                    ruleResults.add(RuleAsserters.assertRule(rule, artifact));
                }
                String plan = artifact.itineraryOrReply();
                if (plan != null && !plan.isBlank()) {
                    rubric = new RubricScorer(reviewerModel).score(evalCase, artifact);
                } else {
                    error = "无行程产物（itinerary_draft.md 与最终回复均为空），无法评分";
                }
            }
        } catch (Exception e) {
            // RubricScorer 解析失败等：按 A5 应急判 FAIL；已采集的规则结果保留
            error = error != null ? error : ("执行异常: " + e.getMessage());
        } finally {
            if (span != null) {
                span.end();
            }
        }
        boolean rulesPassed = ruleResults.stream().noneMatch(RuleAssertion::countsAsFailure);
        int minTotal = evalCase.expectations().rubric().minTotal();
        boolean rubricPassed = rubric != null && rubric.normalizedTotal() >= minTotal;
        boolean passed = error == null && rulesPassed && rubricPassed;
        boolean persisted = persistRecord(evalCase, traceId, ruleResults, rubric, passed);
        return new CaseReport(evalCase.caseId(), evalCase.category(), passed, persisted,
                error, List.copyOf(ruleResults), rubric, minTotal);
    }

    /** 逐轮发送用户消息并等待回复落库；返回 null=全部完成，非 null=失败原因 */
    private String driveTurns(EvalCase evalCase, Conversation conversation, Long userId) {
        long turnTimeoutMs = (appProperties.getLlmGateway().getSlowLaneTimeoutSeconds() + 60) * 1000L;
        List<String> messages = evalCase.userMessages();
        for (int i = 0; i < messages.size(); i++) {
            System.out.printf("[EVAL] %s 第 %d/%d 轮: %s%n",
                    evalCase.caseId(), i + 1, messages.size(), abbreviate(messages.get(i), 60));
            chatService.streamChat(conversation, userId, messages.get(i));
            if (!awaitAssistantReply(conversation.getId(), i + 1, turnTimeoutMs)) {
                return "第 " + (i + 1) + " 轮在 " + (turnTimeoutMs / 1000)
                        + "s 内未产出回复（链路失败或回复为空，查应用日志定位）";
            }
        }
        return null;
    }

    /** 轮询该会话 assistant 消息数达到 expectedCount（persistReply 落库即本轮完成） */
    private boolean awaitAssistantReply(Long conversationId, int expectedCount, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND role = 'assistant'",
                    Integer.class, conversationId);
            if (count != null && count >= expectedCount) {
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ==================== 产物采集与落库 ====================

    /** 采集最终回复与协作目录产物（PLANNING 轮 done 载荷即 itinerary_draft.md 全文） */
    private EvalRunArtifact collectArtifact(Long userId, Long conversationId) {
        Path taskDir = taskWorkspaceService.getTaskDir(String.valueOf(userId), "conv-" + conversationId);
        return new EvalRunArtifact(
                lastAssistantReply(conversationId),
                readFileOrNull(taskDir.resolve(TaskWorkspaceService.FILE_ITINERARY_DRAFT)),
                readFileOrNull(taskDir.resolve(TaskWorkspaceService.FILE_POI_SHORTLIST)),
                readFileOrNull(taskDir.resolve(TaskWorkspaceService.FILE_ROUTE_PLAN)),
                taskDir);
    }

    private String lastAssistantReply(Long conversationId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT content FROM messages WHERE conversation_id = ? AND role = 'assistant' ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getString(1), conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String readFileOrNull(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 结果落库 evaluation_records（trace_id 关联 Jaeger 的 eval-case 链路）；返回是否落库成功 */
    private boolean persistRecord(EvalCase evalCase, String traceId, List<RuleAssertion> ruleResults,
                                  RubricScores rubric, boolean passed) {
        try {
            EvaluationRecord record = new EvaluationRecord();
            record.setTraceId(traceId);
            record.setCaseId(evalCase.caseId());
            record.setRuleResults(ruleResultsJson(ruleResults));
            record.setRubricScores(rubricScoresJson(rubric));
            record.setTotalScore(rubric != null ? BigDecimal.valueOf(rubric.normalizedTotal()) : null);
            record.setIsBadCase(!passed);
            evaluationRecordService.save(record);
            return true;
        } catch (Exception e) {
            System.err.println("[EVAL] " + evalCase.caseId() + " 落库 evaluation_records 失败: " + e.getMessage());
            return false;
        }
    }

    private static String ruleResultsJson(List<RuleAssertion> ruleResults) {
        JSONArray array = new JSONArray();
        for (RuleAssertion assertion : ruleResults) {
            JSONObject item = new JSONObject();
            item.put("type", assertion.type());
            item.put("result", assertion.result());
            item.put("details", assertion.details());
            array.add(item);
        }
        return array.toJSONString();
    }

    private static String rubricScoresJson(RubricScores rubric) {
        if (rubric == null) {
            return null;
        }
        JSONObject json = new JSONObject();
        json.put("routeCoherence", rubric.routeCoherenceScore());
        json.put("timeDensity", rubric.timeDensityScore());
        json.put("preferenceMatch", rubric.preferenceMatchScore());
        json.put("sum", rubric.sum());
        json.put("normalizedTotal", rubric.normalizedTotal());
        return json.toJSONString();
    }

    /** 防御：存量库未执行过 schema.sql 第 6 节时建表（幂等，DDL 与 schema.sql 一致） */
    private void ensureEvaluationTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS evaluation_records (
                    id              BIGSERIAL PRIMARY KEY,
                    trace_id        VARCHAR(64),
                    case_id         VARCHAR(64),
                    rule_results    JSONB,
                    rubric_scores   JSONB,
                    total_score     NUMERIC(5,2),
                    is_bad_case     BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
    }

    // ==================== 报告 ====================

    private record CaseReport(String caseId, EvalCategory category, boolean passed, boolean persisted,
                              String error, List<RuleAssertion> ruleResults, RubricScores rubric, int minTotal) {
    }

    /** 规则小结："3PASS/1SKIP" 或 "FAIL[FIELD_COMPLETE, ...]" */
    private static String ruleSummary(List<RuleAssertion> ruleResults) {
        long passed = ruleResults.stream().filter(RuleAssertion::passed).count();
        long skipped = ruleResults.stream().filter(RuleAssertion::skipped).count();
        String failed = ruleResults.stream()
                .filter(RuleAssertion::countsAsFailure)
                .map(RuleAssertion::type)
                .reduce((a, b) -> a + "," + b)
                .map(types -> " FAIL[" + types + "]")
                .orElse("");
        return passed + "PASS/" + skipped + "SKIP" + failed;
    }

    private static String formatFailures(List<CaseReport> failed) {
        StringBuilder sb = new StringBuilder();
        for (CaseReport report : failed) {
            sb.append("\n== ").append(report.caseId()).append("（").append(report.category()).append("）==");
            if (report.error() != null) {
                sb.append("\n  执行异常: ").append(report.error());
            }
            for (RuleAssertion assertion : report.ruleResults()) {
                if (assertion.countsAsFailure()) {
                    sb.append("\n  规则FAIL ").append(assertion.type()).append(": ").append(assertion.details());
                }
            }
            if (report.rubric() == null) {
                sb.append("\n  Rubric: 未评分");
            } else if (report.rubric().normalizedTotal() < report.minTotal()) {
                sb.append("\n  Rubric FAIL: 总分 ").append(report.rubric().normalizedTotal())
                        .append("/100 低于门槛 ").append(report.minTotal())
                        .append("（三维: 路线连贯 ").append(report.rubric().routeCoherenceScore())
                        .append("/10, 时间密度 ").append(report.rubric().timeDensityScore())
                        .append("/10, 偏好匹配 ").append(report.rubric().preferenceMatchScore()).append("/10）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
