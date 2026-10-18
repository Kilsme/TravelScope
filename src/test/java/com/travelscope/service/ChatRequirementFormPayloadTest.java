package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.service.workflow.RequirementFormSpec;
import com.travelscope.service.workflow.dto.CollectOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2.3 验证：requirement_form 事件载荷形状（前端契约锚定）。
 * 前端 RequirementFormCard 按 {fields, hint, errors} 三键解析——此测试锁定键名与
 * 字段规格序列化后的键集合，防止后端改字段名而前端静默失效。
 */
class ChatRequirementFormPayloadTest {

    private static RequirementFormSpec.FieldSpec spec(String name, String type, String value,
                                                      boolean filled, boolean required,
                                                      List<String> options) {
        return new RequirementFormSpec.FieldSpec(name, name + "标签", type, value, filled,
                required, options, "占位");
    }

    @Test
    void payloadHasFieldsHintAndErrorsKeys() {
        CollectOutcome outcome = CollectOutcome.needUserInput(List.of(
                spec("destination", "text", "杭州", true, true, List.of()),
                spec("budget", "select", null, false, false, List.of("人均500内", "500-1000"))),
                "没听懂要改哪项，可直接点卡片修改");
        String json = ChatService.toRequirementFormPayload(outcome);

        JSONObject payload = JSON.parseObject(json);
        assertThat(payload.keySet()).containsExactlyInAnyOrder("fields", "hint", "errors");
        assertThat(payload.getString("hint")).contains("可直接点卡片修改");
        assertThat(payload.getJSONArray("fields")).hasSize(2);
        assertThat(payload.getJSONArray("errors")).isEmpty();

        // 字段规格键集合（前端 RequirementFormField 接口）
        JSONObject field = payload.getJSONArray("fields").getJSONObject(0);
        assertThat(field.keySet()).containsExactlyInAnyOrder(
                "name", "label", "type", "value", "filled", "required", "options", "placeholder");
        assertThat(field.getString("type")).isEqualTo("text");
        assertThat(field.getBooleanValue("filled")).isTrue();
        assertThat(payload.getJSONArray("fields").getJSONObject(1).getJSONArray("options"))
                .containsExactly("人均500内", "500-1000");
    }

    @Test
    void payloadCarriesFieldErrors() {
        CollectOutcome outcome = CollectOutcome.needUserInput(List.of(),
                "以下地点没认出来，请修改",
                List.of(new CollectOutcome.FieldError("fromCity", "没认出这个地点「火星」")));

        JSONObject payload = JSON.parseObject(ChatService.toRequirementFormPayload(outcome));

        assertThat(payload.getJSONArray("errors")).hasSize(1);
        JSONObject error = payload.getJSONArray("errors").getJSONObject(0);
        assertThat(error.keySet()).containsExactlyInAnyOrder("field", "message");
        assertThat(error.getString("field")).isEqualTo("fromCity");
        assertThat(error.getString("message")).contains("火星");
    }
}
