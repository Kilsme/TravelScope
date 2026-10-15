package com.travelscope.collab;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S0.2 验证：CollabSchemaValidator 三段式校验。
 * 覆盖：合法样例全过 / 版本预检（v3 拒、缺失拒）/ 类型级规则（taskId 重复与空白、
 * ReviewReport 总分偏差、RoutePlan 通勤总超限与容差边界）/ 坏 JSON 带原文片段 /
 * 非法枚举拒 / parse 返回 DTO。
 */
class CollabSchemaValidatorTest {

    private static final String VALID_INTAKE = """
            {"schemaVersion":4,"sessionId":"conv-1","status":"DONE","clarifyCycles":1,
             "fields":{"destination":{"value":"杭州","confirmed":true}},
             "degraded":false,"collectedAt":"2026-10-15T21:30:00"}
            """;

    private static final String VALID_ROUTE = """
            {"schemaVersion":4,"days":[{"day":1,"date":"2026-10-25","pois":[
              {"name":"西湖","lng":120.15,"lat":30.24,"startTime":"09:00","endTime":"12:00"},
              {"name":"灵隐寺","lng":120.10,"lat":30.24,"startTime":"13:30","endTime":"16:00",
               "transitFromPrev":"公交","commuteMinutesFromPrev":45}],
              "commuteMinutesTotal":45}],
             "solverMeta":{"algorithm":"NN+2opt","matrixSource":"amap-transit","durationMs":12,"unplaced":[]}}
            """;

    @Test
    void validFixturesPass() {
        // IntakeResult 合法
        CollabSchemaValidator.validate(VALID_INTAKE, IntakeResult.class);
        // RoutePlan 合法（total == 各段之和）
        CollabSchemaValidator.validate(VALID_ROUTE, RoutePlan.class);
        // TaskBacklog 合法（taskId 不重复）
        String backlog = JSON.toJSONString(new TaskBacklog("conv-1", List.of(
                new TaskBacklog.TaskItem("T1", "火车票", null, "P0", "PENDING", "2026-10-15T21:30:00"),
                new TaskBacklog.TaskItem("T2", "天气", null, "P0", "PENDING", "2026-10-15T21:30:00"))));
        CollabSchemaValidator.validate(backlog, TaskBacklog.class);
        // ReviewReport 合法（total == 五维和）
        String report = JSON.toJSONString(new ReviewReport(
                List.of(new ReviewReport.ReviewSegment(1, true, List.of())),
                new ReviewReport.ReviewScores(18, 17, 15, 18, 17), 85,
                Map.of("1", List.of()), null));
        CollabSchemaValidator.validate(report, ReviewReport.class);
    }

    @Test
    void legacySchemaVersionRejected() {
        String json = """
                {"schemaVersion":3,"sessionId":"conv-1","status":"DONE","clarifyCycles":0,
                 "fields":{},"degraded":false,"collectedAt":"2026-10-15T21:30:00"}
                """;
        assertThatThrownBy(() -> CollabSchemaValidator.validate(json, IntakeResult.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("[IntakeResult]")
                .hasMessageContaining("版本不符");
    }

    @Test
    void missingSchemaVersionRejected() {
        String json = """
                {"sessionId":"conv-1","status":"DONE","clarifyCycles":0,
                 "fields":{},"degraded":false,"collectedAt":"2026-10-15T21:30:00"}
                """;
        assertThatThrownBy(() -> CollabSchemaValidator.validate(json, IntakeResult.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("schemaVersion")
                .hasMessageContaining("缺失");
    }

    @Test
    void reviewReportTotalMismatchRejected() {
        String json = """
                {"schemaVersion":4,"segments":[{"segment":1,"pass":true,"issues":[]}],
                 "scores":{"completeness":10,"feasibility":10,"timeConflict":10,
                           "costBudget":10,"poiReasonableness":10},
                 "total":85,"suggestionsBySegment":{}}
                """;
        assertThatThrownBy(() -> CollabSchemaValidator.validate(json, ReviewReport.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("[ReviewReport]")
                .hasMessageContaining("total")
                .hasMessageContaining("85")
                .hasMessageContaining("50");
    }

    @Test
    void reviewReportTotalWithinTolerancePasses() {
        // 五维和 = 50，total = 51（±1 容差内）
        String json = """
                {"schemaVersion":4,"segments":[{"segment":1,"pass":true,"issues":[]}],
                 "scores":{"completeness":10,"feasibility":10,"timeConflict":10,
                           "costBudget":10,"poiReasonableness":10},
                 "total":51,"suggestionsBySegment":{}}
                """;
        CollabSchemaValidator.validate(json, ReviewReport.class);
    }

    @Test
    void malformedJsonRejectedWithSnippet() {
        String bad = "{\"schemaVersion\":4,\"sessionId\":\"conv-1\",\"tasks\":[截断";
        assertThatThrownBy(() -> CollabSchemaValidator.validate(bad, TaskBacklog.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("[TaskBacklog]")
                .hasMessageContaining("原文前200字符")
                .hasMessageContaining("截断");
    }

    @Test
    void duplicateTaskIdRejected() {
        String json = """
                {"schemaVersion":4,"sessionId":"conv-1","tasks":[
                  {"taskId":"T1","description":"火车票","priority":"P0","status":"PENDING","updatedAt":"t"},
                  {"taskId":"T1","description":"天气","priority":"P0","status":"PENDING","updatedAt":"t"}]}
                """;
        assertThatThrownBy(() -> CollabSchemaValidator.validate(json, TaskBacklog.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("[TaskBacklog]")
                .hasMessageContaining("tasks[1].taskId")
                .hasMessageContaining("重复");
    }

    @Test
    void blankTaskIdRejected() {
        String json = """
                {"schemaVersion":4,"sessionId":"conv-1","tasks":[
                  {"taskId":"  ","description":"火车票","priority":"P0","status":"PENDING","updatedAt":"t"}]}
                """;
        assertThatThrownBy(() -> CollabSchemaValidator.validate(json, TaskBacklog.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("tasks[0].taskId")
                .hasMessageContaining("空白");
    }

    @Test
    void routePlanCommuteTotalExceedingSumRejected() {
        // 各段之和 = 45，total = 47（> 45+1 容差）
        String json = VALID_ROUTE.replace("\"commuteMinutesTotal\":45", "\"commuteMinutesTotal\":47");
        assertThatThrownBy(() -> CollabSchemaValidator.validate(json, RoutePlan.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("[RoutePlan]")
                .hasMessageContaining("days[1].commuteMinutesTotal")
                .hasMessageContaining("47");
    }

    @Test
    void routePlanCommuteTotalWithinTolerancePasses() {
        // 各段之和 = 45，total = 46（容差边界）
        String json = VALID_ROUTE.replace("\"commuteMinutesTotal\":45", "\"commuteMinutesTotal\":46");
        CollabSchemaValidator.validate(json, RoutePlan.class);
    }

    @Test
    void invalidIntakeStatusRejected() {
        String json = VALID_INTAKE.replace("\"status\":\"DONE\"", "\"status\":\"ARCHIVED\"");
        assertThatThrownBy(() -> CollabSchemaValidator.validate(json, IntakeResult.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("[IntakeResult]")
                .hasMessageContaining("原文前200字符");
    }

    @Test
    void parseReturnsDeserializedDto() {
        IntakeResult result = CollabSchemaValidator.parse(VALID_INTAKE, IntakeResult.class);
        assertThat(result.sessionId()).isEqualTo("conv-1");
        assertThat(result.status()).isEqualTo(IntakeResult.Status.DONE);
        assertThat(result.schemaVersion()).isEqualTo(4);
    }
}
