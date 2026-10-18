package com.travelscope.service.workflow;

import com.travelscope.config.AppProperties;
import com.travelscope.service.workflow.dto.RequirementExtraction;
import com.travelscope.testutil.ModelGatewayTestSupport;
import io.agentscope.core.util.JsonSchemaUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2.1 验证：RequirementExtractor 需求抽取。
 * 单元层（常跑）：parse 过滤链（置信度阈值边界/null 空白丢弃/白名单/数字与字符串值/
 * 围栏与片段宽容解析）+ extract 装配（invokeModel 桩点：canned JSON/异常降级）+
 * 输入组装（今天日期锚点）+ schema 生成冒烟（Object 字段不炸）。
 * 真实 API 层（环境变量门控，卡片指定的两条语义用例）：家人出行消息的置信度语义 +
 * 途经地对抗用例——与 LightweightIntentClassifierTest 同款门控（ReActAgent 循环
 * 无法在 Model 层 mock 的既有结论）。
 */
class RequirementExtractorTest {

    private static final String CANNED = """
            {"destination":{"value":"杭州","confidence":0.9},
             "days":{"value":3,"confidence":0.8},
             "startDate":{"value":"2026-10-24","confidence":0.75},
             "people":{"value":2,"confidence":0.5},
             "budget":{"value":" ","confidence":0.9},
             "hobby":{"value":"钓鱼","confidence":0.99}}""";

    /** 桩：覆写包私有 invokeModel，绕开 ReActAgent 循环 */
    static class StubExtractor extends RequirementExtractor {
        String cannedJson;
        boolean boom;
        String capturedInput;

        StubExtractor(String cannedJson, boolean boom) {
            super(null, new AppProperties());
            this.cannedJson = cannedJson;
            this.boom = boom;
        }

        @Override
        String invokeModel(String input) {
            capturedInput = input;
            if (boom) {
                throw new RuntimeException("stub boom");
            }
            return cannedJson;
        }
    }

    // ---------- parse：置信度阈值边界 ----------
    @Test
    void parseFiltersByConfidenceThreshold() {
        String json = """
                {"destination":{"value":"杭州","confidence":0.69},
                 "days":{"value":3,"confidence":0.70}}""";
        RequirementExtraction extraction = RequirementExtraction.parse(json, 0.7);

        assertThat(extraction.fields()).containsOnlyKeys("days");
    }

    // ---------- parse：null/空白 value 丢弃 ----------
    @Test
    void parseDropsNullOrBlankValues() {
        String json = """
                {"destination":{"value":null,"confidence":0.9},
                 "days":{"value":"  ","confidence":0.9},
                 "fromCity":{"value":"上海","confidence":0.9}}""";
        RequirementExtraction extraction = RequirementExtraction.parse(json, 0.7);

        assertThat(extraction.fields()).containsOnlyKeys("fromCity");
    }

    // ---------- parse：8 字段白名单（未知键忽略）+ confidence 缺失丢弃 ----------
    @Test
    void parseWhitelistsEightFields() {
        RequirementExtraction extraction = RequirementExtraction.parse(CANNED, 0.7);

        assertThat(extraction.fields()).containsOnlyKeys("destination", "days", "startDate");
        assertThat(RequirementExtraction.FIELDS).hasSize(8);
    }

    // ---------- parse：数字与字符串值都接受（Integer 转换留给 S2.2） ----------
    @Test
    void parseAcceptsNumberAndStringValues() {
        String json = """
                {"days":{"value":3,"confidence":0.9},
                 "people":{"value":"4","confidence":0.9}}""";
        RequirementExtraction extraction = RequirementExtraction.parse(json, 0.7);

        assertThat(String.valueOf(extraction.fields().get("days"))).isEqualTo("3");
        assertThat(extraction.fields().get("people")).isEqualTo("4");
    }

    // ---------- parse：markdown 围栏与前后缀片段宽容解析 ----------
    @Test
    void parseToleratesMarkdownFencesAndSurroundingText() {
        String fenced = "```json\n" + CANNED + "\n```";
        assertThat(RequirementExtraction.parse(fenced, 0.7).fields())
                .containsOnlyKeys("destination", "days", "startDate");

        String surrounded = "抽取结果如下：" + CANNED + " 以上。";
        assertThat(RequirementExtraction.parse(surrounded, 0.7).fields())
                .containsOnlyKeys("destination", "days", "startDate");
    }

    // ---------- parse：扁平形容忍 + 垃圾输入返回 EMPTY ----------
    @Test
    void parseFlatShapeToleratedAndGarbageReturnsEmpty() {
        // 扁平形（模型无视 schema 直出的偏差）：视为满置信
        assertThat(RequirementExtraction.parse("{\"destination\":\"杭州\"}", 0.7).fields())
                .containsEntry("destination", "杭州");
        // 垃圾/空白/非 JSON
        assertThat(RequirementExtraction.parse(null, 0.7)).isEqualTo(RequirementExtraction.EMPTY);
        assertThat(RequirementExtraction.parse("   ", 0.7)).isEqualTo(RequirementExtraction.EMPTY);
        assertThat(RequirementExtraction.parse("这不是JSON", 0.7)).isEqualTo(RequirementExtraction.EMPTY);
        assertThat(RequirementExtraction.parse("{\"people\":{\"value\":2,\"confidence\":0.1}}", 0.7)
                .fields()).isEmpty();
    }

    // ---------- extract 装配：桩点委托 + 异常降级不抛 ----------
    @Test
    void extractDelegatesToParseAndDegradesOnFailure() {
        StubExtractor stub = new StubExtractor(CANNED, false);
        RequirementExtraction extraction = stub.extract("帮我规划杭州三日游");

        assertThat(extraction.fields()).containsOnlyKeys("destination", "days", "startDate");
        assertThat(extraction.rawJson()).isEqualTo(CANNED);
        // 输入经 buildInput 组装（含用户消息）
        assertThat(stub.capturedInput).contains("帮我规划杭州三日游");

        StubExtractor boomer = new StubExtractor(CANNED, true);
        assertThat(boomer.extract("任何消息")).isEqualTo(RequirementExtraction.EMPTY);
        // 空白输入短路
        assertThat(new StubExtractor(CANNED, false).extract("  "))
                .isEqualTo(RequirementExtraction.EMPTY);
    }

    // ---------- 输入组装：今天日期锚点（相对日期换算基准） ----------
    @Test
    void buildInputContainsTodayAndMessage() {
        String input = RequirementExtractor.buildInput("下周去杭州");

        assertThat(input).contains("今天是 " + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        assertThat(input).contains("用户消息：下周去杭州");
        // 星期中文（如「星期六」）存在——「下周六」换算需要
        assertThat(input).contains("星期");
    }

    // ---------- schema 生成冒烟：Object 字段不炸框架结构化输出 ----------
    @Test
    void schemaGenerationSmoke() {
        Map<String, Object> schema =
                JsonSchemaUtils.generateSchemaFromClass(RequirementExtractor.ExtractedFields.class);
        assertThat(schema).as("8 字段 schema 非空").isNotEmpty();
    }

    // ==================== 真实 API（卡片指定语义用例，网关 key 门控） ====================

    @Test
    @Timeout(60)
    @EnabledIfEnvironmentVariable(named = ModelGatewayTestSupport.API_KEY_ENV, matches = ".+")
    void realApiFamilyTripExtraction() {
        RequirementExtractor extractor = new RequirementExtractor(
                ModelGatewayTestSupport.lightModel(), new AppProperties());

        RequirementExtraction extraction = extractor.extract("下周带爸妈去杭州玩3天");

        assertThat(extraction.fields())
                .as("目的地与天数应抽出（置信度过门槛）")
                .containsEntry("destination", "杭州");
        assertThat(String.valueOf(extraction.fields().get("days"))).isEqualTo("3");
        assertThat(extraction.fields())
                .as("「带爸妈」总人数不明确，people 宁缺毋滥应丢弃")
                .doesNotContainKey("people");
    }

    @Test
    @Timeout(60)
    @EnabledIfEnvironmentVariable(named = ModelGatewayTestSupport.API_KEY_ENV, matches = ".+")
    void realApiAdversarialPassThroughCity() {
        RequirementExtractor extractor = new RequirementExtractor(
                ModelGatewayTestSupport.lightModel(), new AppProperties());

        RequirementExtraction extraction = extractor.extract("我路过杭州去北京，顺便规划下北京的行程");

        assertThat(extraction.fields())
                .as("途经地不算目的地（对抗用例）")
                .containsEntry("destination", "北京")
                .doesNotContainEntry("destination", "杭州");
    }
}
